import subprocess
import re
import requests
import os
import json
import logging
import time
import threading
import queue

from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

# ================= 日志 =================

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[
        logging.StreamHandler(),
        logging.FileHandler("push_device.log", encoding="utf-8")
    ]
)

logger = logging.getLogger(__name__)

# ================= 配置 =================

with open("IPconfig.json", "r", encoding="utf-8") as f:
    config = json.load(f)

SERVER_URL = (
    f"http://{config['server_ip']}:"
    f"{config['server_port']}"
    f"{config['upload_path']}"
)

DEVICE_ID = config["device_id"]

logger.info("服务器地址: %s", SERVER_URL)

# ================= 路径 =================

ROOT_DIR = "/home/elf/demo/make"

MODEL_EXEC = os.path.join(ROOT_DIR, "rknn_vision")

DROWNING_MODEL = "/home/elf/demo/best_int8_fixed.rknn"

PERSON_MODEL = "/home/elf/demo/yolov8m_int8_fixed.rknn"

FRAME_DIR = "/home/elf/demo/make/frames_int8_pixel_box"

logger.info("ROOT_DIR = %s", ROOT_DIR)
logger.info("FRAME_DIR = %s", FRAME_DIR)

# ================= HTTP =================

session = requests.Session()

retry_strategy = Retry(
    total=3,
    backoff_factor=1,
    status_forcelist=[500, 502, 503, 504],
    allowed_methods=["POST"]
)

adapter = HTTPAdapter(max_retries=retry_strategy)

session.mount("http://", adapter)
session.mount("https://", adapter)

# ================= 队列 =================

upload_queue = queue.Queue(maxsize=100)

# ================= 正则 =================

RE_FRAME = re.compile(r"=+\s*帧\s*(\d+)\s*=+")

RE_DROWNING_COUNT = re.compile(
    r"Drowning=(\d+)"
)

RE_CALL = re.compile(
    r"CallforHelp\s*=\s*(\d+)"
)

RE_PRESSURE = re.compile(
    r"Pressure\s*=\s*(\d+)"
)

RE_TARGET = re.compile(
    r"Drowning:\s*conf=([\d.]+)\s*center=\((\d+),(\d+)\)"
)

# ================= 最新图片 =================

def get_latest_image():

    try:

        files = [
            os.path.join(FRAME_DIR, f)
            for f in os.listdir(FRAME_DIR)
            if f.endswith(".jpg")
        ]

        if not files:
            return None

        latest = max(files, key=os.path.getmtime)

        return latest

    except Exception as e:

        logger.error("获取图片失败: %s", e)

        return None

# ================= 上传 =================

def upload(data):

    if not data:
        return

    img_path = data.get("image")

    files = {}

    if img_path and os.path.exists(img_path):

        try:
            files["file"] = open(img_path, "rb")
        except:
            pass

    form = {

        "deviceId": DEVICE_ID,

        "frameNo": str(data["frameNo"]),

        "drowningCount": str(data["drowningCount"]),

        "heardCall": str(data["heardCall"]),

        "pressure": str(data["pressure"]),

        "alarm": str(data["alarm"]),

        "targets": json.dumps(
            data["targets"],
            ensure_ascii=False
        )
    }

    try:

        r = session.post(
            SERVER_URL,
            data=form,
            files=files,
            timeout=10
        )

        r.raise_for_status()

        logger.info(
            "上传成功 frame=%s drowning=%s call=%s pressure=%s alarm=%s",
            data["frameNo"],
            data["drowningCount"],
            data["heardCall"],
            data["pressure"],
            data["alarm"]
        )

    except Exception as e:

        logger.error("上传失败: %s", e)

    finally:

        if "file" in files:
            files["file"].close()

# ================= 上传线程 =================

def upload_worker():

    while True:

        data = upload_queue.get()

        try:

            if data is None:
                break

            upload(data)

        except Exception as e:

            logger.error("上传线程异常: %s", e)

        finally:

            upload_queue.task_done()

# ================= stderr =================

def stderr_reader(proc):

    for line in proc.stderr:

        line = line.strip()

        if line:
            logger.error("[STDERR] %s", line)

# ================= 启动模型 =================

process = subprocess.Popen(

    [
        MODEL_EXEC,
        DROWNING_MODEL,
        PERSON_MODEL
    ],

    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,

    text=True,
    encoding="utf-8",
    errors="ignore",

    bufsize=1,

    cwd=ROOT_DIR
)

logger.info("模型进程启动 PID=%d", process.pid)

# ================= 启动线程 =================

threading.Thread(
    target=stderr_reader,
    args=(process,),
    daemon=True
).start()

threading.Thread(
    target=upload_worker,
    daemon=True
).start()

# ================= 当前帧 =================

frame_data = None

# ================= 主循环 =================

try:

    while True:

        line = process.stdout.readline()

        if not line:

            if process.poll() is not None:

                logger.warning(
                    "模型退出 code=%s",
                    process.returncode
                )

                break

            time.sleep(0.001)

            continue

        line = line.strip()

        print(line)

        # ========= 新帧 =========

        m = RE_FRAME.search(line)

        if m:

            if frame_data:

                frame_data["image"] = get_latest_image()

                try:
                    upload_queue.put_nowait(
                        frame_data.copy()
                    )
                except queue.Full:
                    logger.warning("队列已满")

            frame_data = {

                "frameNo": int(m.group(1)),

                "drowningCount": 0,

                "heardCall": 0,

                "pressure": 0,

                "alarm": 0,

                "targets": [],

                "image": None
            }

            continue

        if frame_data is None:
            continue

        # ========= 溺水人数 =========

        m = RE_DROWNING_COUNT.search(line)

        if m:

            frame_data["drowningCount"] = int(m.group(1))

            continue

        # ========= 呼救 =========

        m = RE_CALL.search(line)

        if m:

            frame_data["heardCall"] = int(m.group(1))

            continue

        # ========= 压力 =========

        m = RE_PRESSURE.search(line)

        if m:

            frame_data["pressure"] = int(m.group(1))

            continue

        # ========= 目标 =========

        m = RE_TARGET.search(line)

        if m:

            score = float(m.group(1))

            center_x = int(m.group(2))

            center_y = int(m.group(3))

            frame_data["targets"].append({

                "index": len(frame_data["targets"]),

                "label": "drowning",

                "score": score,

                "centerX": center_x,

                "centerY": center_y
            })

            continue

        # ========= 综合报警 =========

        # Pressure=1 优先级最高
        # 已经救到人 => 强制关闭报警

        if frame_data["pressure"] == 1:

            frame_data["alarm"] = 0

        else:

            if (
                frame_data["drowningCount"] > 0
                or
                frame_data["heardCall"] > 0
            ):

                frame_data["alarm"] = 1

            else:

                frame_data["alarm"] = 0

# ================= 退出 =================

finally:

    logger.info("程序退出")

    if frame_data:

        frame_data["image"] = get_latest_image()

        try:

            upload_queue.put_nowait(
                frame_data.copy()
            )

        except:
            pass

    upload_queue.put(None)

    process.wait()

    logger.info("模型结束")