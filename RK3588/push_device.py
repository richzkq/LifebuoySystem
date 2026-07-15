import subprocess
import re
import os
import json
import time
import logging
import threading
from logging.handlers import RotatingFileHandler
from concurrent.futures import ThreadPoolExecutor

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

# =========================================================
# 日志
# =========================================================
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[
        logging.StreamHandler(),
        RotatingFileHandler("push_device.log", maxBytes=10*1024*1024, backupCount=3, encoding="utf-8")
    ]
)
logger = logging.getLogger(__name__)

# =========================================================
# 配置
# =========================================================
with open("IPconfig.json", "r", encoding="utf-8") as f:
    config = json.load(f)

SERVER_URL = f"http://{config['server_ip']}:{config['server_port']}{config['upload_path']}"
DEVICE_ID  = config["device_id"]
logger.info("服务器地址: %s", SERVER_URL)

# =========================================================
# HTTP（带重试）
# =========================================================
session = requests.Session()
retry = Retry(total=2, backoff_factor=0.5, status_forcelist=[500, 502, 503, 504])
session.mount("http://", HTTPAdapter(max_retries=retry))

# =========================================================
# 模型路径
# =========================================================
ROOT_DIR       = "/home/elf/demo/make"
MODEL_EXEC     = os.path.join(ROOT_DIR, "rknn_vision")
DROWNING_MODEL = "/home/elf/demo/new_dummy_int8.rknn"
PERSON_MODEL   = "/home/elf/demo/yolov8n_int8.rknn"

# =========================================================
# 正则
# =========================================================
RE_FRAME          = re.compile(r"=+\s*帧\s*(\d+)\s*=+")
RE_TEMP_LINE      = re.compile(r"\[TEMP\]\s*([\d.]+)")      # 帧间温度： [TEMP] 33.3°C state=NORMAL
RE_TEMP           = re.compile(r"温度:\s*([\d.]+)")          # 帧内温度： 温度: 33.3°C
RE_DROWNING_COUNT = re.compile(r"Drowning=(\d+)")
RE_PERSON_COUNT   = re.compile(r"Person out of water=(\d+)")
RE_CALL           = re.compile(r"CallforHelp\s*=\s*(\d+)")
RE_PRESSURE       = re.compile(r"Pressure\s*=\s*(\d+)")
RE_TARGET         = re.compile(r"Drowning:\s*conf=([\d.]+)\s*center=\((\d+),(\d+)\)")

# =========================================================
# 综合报警
# =========================================================
def compute_alarm(data):
    if data["pressure"] == 1:
        return 0
    if data["drowningCount"] > 0 or data["callForHelp"] > 0:
        return 1
    return 0

# =========================================================
# 上传（异步，不阻塞主循环）
# =========================================================
_upload_pool = ThreadPoolExecutor(max_workers=1)

def do_upload(data):
    def _post():
        form = {
            "deviceId":      DEVICE_ID,
            "frameNo":       str(data["frameNo"]),
            "drowningCount": str(data["drowningCount"]),
            "personCount":   str(data["drowningCount"] + data["personOutOfWater"]),
            "callForHelp":   str(data["callForHelp"]),
            "pressure":      str(data["pressure"]),
            "alarm":         str(data["alarm"]),
            "temperature":   str(data.get("temperature", 0.0)),
            "targets":       json.dumps(data["targets"], ensure_ascii=False),
        }
        try:
            r = session.post(SERVER_URL, data=form, timeout=10)
            r.raise_for_status()
        except Exception as e:
            logger.error("上传失败 frame=%s: %s", data["frameNo"], e)

    _upload_pool.submit(_post)

# =========================================================
# 状态变量
# =========================================================
last_call_for_help = 0      # 上一帧呼救声
last_pressure      = 0      # 上一帧压力
last_temp_upload   = 0      # 上次温度心跳上传时间戳

def upload_decision(frame_data):
    """每帧解析完成时调用。溺水直接上传 alarm=1，防重复由服务端 countPending 负责"""
    global last_call_for_help, last_pressure

    # ──── 1. 呼救声：状态变化即上传 ────
    call = frame_data["callForHelp"]
    if call != last_call_for_help:
        frame_data["alarm"] = compute_alarm(frame_data)
        do_upload(frame_data)
        last_call_for_help = call

    # ──── 2. 压力传感器：状态变化即上传 ────
    pres = frame_data["pressure"]
    if pres != last_pressure:
        frame_data["alarm"] = compute_alarm(frame_data)
        do_upload(frame_data)
        last_pressure = pres

    # ──── 3. 溺水报警：模型输出 Drowning>0 就上传 ────
    drowning = frame_data["drowningCount"]
    if drowning > 0:
        frame_data["alarm"] = 1
        do_upload(frame_data)

# =========================================================
# 启动模型
# =========================================================
process = subprocess.Popen(
    [MODEL_EXEC, DROWNING_MODEL, PERSON_MODEL],
    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    text=True, encoding="utf-8", errors="ignore",
    bufsize=1, cwd=ROOT_DIR,
)
logger.info("模型进程启动 PID=%d", process.pid)

threading.Thread(target=lambda: [logger.error("[STDERR] %s", l.strip())
                                  for l in process.stderr if l.strip()],
                 daemon=True).start()

# =========================================================
# 主循环
# =========================================================
frame_data = None
global_temp = 0.0

try:
    while True:
        line = process.stdout.readline()
        if not line:
            if process.poll() is not None:
                logger.warning("模型退出 code=%s", process.returncode)
                break
            time.sleep(0.001)
            continue

        line = line.strip()

        # ── 帧间温度：[TEMP] 33.3°C state=NORMAL ──
        m = RE_TEMP_LINE.search(line)
        if m:
            global_temp = float(m.group(1))
            now = time.time()
            if now - last_temp_upload >= 5.0:
                temp_only = {
                    "frameNo":         0,
                    "drowningCount":   0,
                    "personOutOfWater": 0,
                    "callForHelp":     0,
                    "pressure":        0,
                    "alarm":           0,
                    "temperature":     global_temp,
                    "targets":         [],
                }
                do_upload(temp_only)
                last_temp_upload = now
            continue

        # ── 新帧标记 ──
        m = RE_FRAME.search(line)
        if m:
            if frame_data is not None:
                if frame_data["temperature"] == 0.0:
                    frame_data["temperature"] = global_temp
                upload_decision(frame_data)

            frame_data = {
                "frameNo":          int(m.group(1)),
                "drowningCount":    0,
                "personOutOfWater": 0,
                "callForHelp":      0,
                "pressure":         0,
                "alarm":            0,
                "temperature":      global_temp,
                "targets":          [],
            }
            continue

        if frame_data is None:
            continue
        if line.startswith("[FPS]"):
            continue

        # ── 逐字段解析 ──
        m = RE_DROWNING_COUNT.search(line)
        if m:
            frame_data["drowningCount"] = int(m.group(1))

        m = RE_PERSON_COUNT.search(line)
        if m:
            frame_data["personOutOfWater"] = int(m.group(1))

        m = RE_CALL.search(line)
        if m:
            frame_data["callForHelp"] = int(m.group(1))

        m = RE_PRESSURE.search(line)
        if m:
            frame_data["pressure"] = int(m.group(1))

        m = RE_TEMP.search(line)
        if m:
            frame_data["temperature"] = float(m.group(1))
            global_temp = frame_data["temperature"]

        m = RE_TARGET.search(line)
        if m:
            frame_data["targets"].append({
                "index":   len(frame_data["targets"]),
                "label":   "drowning",
                "score":   float(m.group(1)),
                "centerX": int(m.group(2)),
                "centerY": int(m.group(3)),
            })

finally:
    logger.info("程序退出中...")
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
    logger.info("程序已退出")
