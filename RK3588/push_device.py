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

# ========= 日志配置 ==========
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[
        logging.StreamHandler(),
        logging.FileHandler("uploader.log", encoding="utf-8"),
    ],
)

logger = logging.getLogger(__name__)

# ========= 读取配置 ==========
with open("IPconfig.json", "r", encoding="utf-8") as f:
    config = json.load(f)

SERVER_URL = (
    f"http://{config['server_ip']}:"
    f"{config['server_port']}"
    f"{config['upload_path']}"
)

DEVICE_ID = config["device_id"]

logger.info("服务器地址: %s", SERVER_URL)

# ========= 路径 ==========
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MODEL_PATH = os.path.join(SCRIPT_DIR, "model", "yolov8.rknn")
DEMO_PATH  = os.path.join(SCRIPT_DIR, "rknn_yolov8_demo")

logger.info("脚本目录: %s", SCRIPT_DIR)
logger.info("模型文件存在: %s -> %s", MODEL_PATH, os.path.exists(MODEL_PATH))
logger.info("可执行文件存在: %s -> %s", DEMO_PATH, os.path.exists(DEMO_PATH))

# ========= 上传队列 ==========
upload_queue = queue.Queue(maxsize=200)

# ========= HTTP Session（自动重试） ==========
session = requests.Session()

retry_strategy = Retry(
    total=3,
    backoff_factor=1,
    status_forcelist=[500, 502, 503, 504],
    allowed_methods=["POST"],
)

adapter = HTTPAdapter(max_retries=retry_strategy)
session.mount("http://", adapter)
session.mount("https://", adapter)

# ========= 正则 ==========
RE_FRAME    = re.compile(r'=+\s*帧\s*(\d+)\s*=+')
RE_CONF     = re.compile(r'人体置信度:\s*([\d.]+)')
RE_CENTER   = re.compile(r'中心点:\s*\((\d+),\s*(\d+)\)')
RE_IMAGE    = re.compile(r'已保存:\s*(frame_\d+\.jpg)')
RE_DETECTED = re.compile(r'检测到人:\s*(是|否)')


# ========= 上传函数 ==========
def upload(data: dict | None):

    if not data:
        return

    img_path = data.get("image", "")

    if not img_path:
        logger.warning("帧 %s 无图片路径", data["frameNo"])
        return

    if not os.path.isabs(img_path):
        img_path = os.path.join(SCRIPT_DIR, img_path)

    if not os.path.exists(img_path):
        logger.warning("图片不存在: %s", img_path)
        return

    try:
        with open(img_path, "rb") as img_file:

            files = {"file": img_file}

            form = {
                "deviceId":    DEVICE_ID,
                "frameNo":     str(data["frameNo"]),
                "detectCount": str(data["detectCount"]),
                "targets":     json.dumps(data["targets"], ensure_ascii=False),
            }

            r = session.post(
                SERVER_URL,
                data=form,
                files=files,
                timeout=10,
            )
            r.raise_for_status()

            logger.info(
                "上传成功: 帧 %s 目标数 %s",
                data["frameNo"],
                data["detectCount"],
            )

        try:
            os.remove(img_path)
        except OSError as e:
            logger.warning("删除图片失败: %s", e)

    except requests.exceptions.Timeout:
        logger.error("上传超时: 帧 %s", data["frameNo"])
    except requests.exceptions.ConnectionError as e:
        logger.error("网络连接失败: %s", e)
    except requests.exceptions.HTTPError as e:
        logger.error("HTTP错误: %s", e)
    except Exception as e:
        logger.error("上传异常: %s", e)


# ========= 上传线程 ==========
def upload_worker():

    while True:
        data = upload_queue.get()
        try:
            if data is None:
                upload_queue.task_done()
                break
            upload(data)
        except Exception as e:
            logger.error("上传线程异常: %s", e)
        finally:
            if data is not None:
                upload_queue.task_done()


# # ========= 环境变量（清除干扰项） ==========
# env = os.environ.copy()
# env.pop("LD_PRELOAD",      None)
# env.pop("LD_LIBRARY_PATH", None)
# env.pop("LD_DEBUG",        None)
# env.pop("LD_AUDIT",        None)
# env.pop("LD_BIND_NOW",     None)


# ========= 环境变量 ==========
env = os.environ.copy()
# 仅对子进程使用 glibc 2.38
env["LD_LIBRARY_PATH"] = (
    "/opt/glibc-2.38/lib:"
    "/usr/lib/aarch64-linux-gnu:"
    "/lib/aarch64-linux-gnu"
)

# ========= stderr 读取线程 ==========
def stderr_reader(proc: subprocess.Popen):
    for line in proc.stderr:
        line = line.strip()
        if line:
            logger.error("[CHILD STDERR] %s", line)


# ========= GLIBC Loader ==========
GLIBC_LD = "/opt/glibc-2.38/lib/ld-linux-aarch64.so.1"

# ========= 启动 RKNN ==========
process = subprocess.Popen(
    [
        GLIBC_LD,
        DEMO_PATH,
        MODEL_PATH,
    ],
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    text=True,
    encoding="utf-8",
    errors="ignore",
    bufsize=1,
    env=env,
    cwd=SCRIPT_DIR,
)

logger.info("模型进程已启动 PID=%d", process.pid)

stderr_thread = threading.Thread(
    target=stderr_reader,
    args=(process,),
    daemon=True,
)
stderr_thread.start()

# ========= 启动上传线程 ==========
worker = threading.Thread(
    target=upload_worker,
    daemon=True,
)
worker.start()

# ========= 当前帧 ==========
frame_data = None

# ========= 主循环 ==========
try:

    while True:

        line = process.stdout.readline()

        if not line:
            if process.poll() is not None:
                logger.warning(
                    "子进程已退出，退出码: %s",
                    process.returncode,
                )
                break
            time.sleep(0.001)
            continue

        line = line.strip()
        logger.debug(line)

        # ========= 新帧 ==========
        m = RE_FRAME.search(line)
        if m:
            if frame_data:
                try:
                    upload_queue.put_nowait(frame_data.copy())
                except queue.Full:
                    logger.warning(
                        "上传队列满，丢弃帧 %s",
                        frame_data["frameNo"],
                    )

            frame_data = {
                "frameNo":     int(m.group(1)),
                "detectCount": 0,
                "targets":     [],
                "image":       "",
            }
            continue

        if frame_data is None:
            continue

        # ========= 置信度 ==========
        m = RE_CONF.search(line)
        if m:
            frame_data["targets"].append({
                "index":   len(frame_data["targets"]),
                "label":   "person",
                "score":   float(m.group(1)),
                "centerX": 0,
                "centerY": 0,
            })
            continue

        # ========= 中心点 ==========
        m = RE_CENTER.search(line)
        if m and frame_data["targets"]:
            frame_data["targets"][-1]["centerX"] = int(m.group(1))
            frame_data["targets"][-1]["centerY"] = int(m.group(2))
            continue

        # ========= 检测状态 ==========
        m = RE_DETECTED.search(line)
        if m:
            if m.group(1) == "是":
                frame_data["detectCount"] = len(frame_data["targets"])
            else:
                frame_data["detectCount"] = 0
                frame_data["targets"]     = []
            continue

        # ========= 图片 ==========
        m = RE_IMAGE.search(line)
        if m:
            frame_data["image"] = m.group(1)
            continue

# ========= 退出 ==========
finally:

    logger.info("程序退出中...")

    if frame_data:
        try:
            upload_queue.put_nowait(frame_data.copy())
        except queue.Full:
            logger.warning("最后一帧上传失败，队列已满")

    upload_queue.put(None)
    worker.join()
    stderr_thread.join(timeout=3)
    process.wait()

    logger.info("模型进程结束，退出码: %s", process.returncode)