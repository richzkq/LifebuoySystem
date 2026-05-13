import subprocess
import re
import requests
import os
import json
import logging
import time
import glob
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

SERVER_URL = f"http://{config['server_ip']}:{config['server_port']}{config['upload_path']}"
DEVICE_ID = config["device_id"]

logger.info("服务器地址: %s", SERVER_URL)
upload_queue = queue.Queue(maxsize=200)


# ========= HTTP Session（含自动重试）==========
session = requests.Session()
retry_strategy = Retry(
    total=3,                        # 最多重试 3 次
    backoff_factor=1,               # 每次重试间隔：1s, 2s, 4s
    status_forcelist=[500, 502, 503, 504],
    allowed_methods=["POST"],
)
adapter = HTTPAdapter(max_retries=retry_strategy)
session.mount("http://", adapter)
session.mount("https://", adapter)

# ========= 正则预编译 ==========
RE_FRAME = re.compile(r'=+\s*帧\s*(\d+)\s*=+')
RE_CONF       = re.compile(r'人体置信度:\s*([\d.]+)')
RE_CENTER     = re.compile(r'中心点:\s*\((\d+),\s*(\d+)\)')
RE_IMAGE      = re.compile(r'已保存:\s*(frame_\d+\.jpg)')
RE_DETECTED   = re.compile(r'检测到人:\s*(是|否)')

# def cleanup_old_images():
#     """删除旧图片，防止异常情况下堆积"""
#     files = glob.glob("frame_*.jpg")
#
#     # 只保留最新20张
#     if len(files) <= 20:
#         return
#
#     files.sort(key=os.path.getmtime)
#
#     old_files = files[:-20]
#
#     for f in old_files:
#         try:
#             os.remove(f)
#             logger.info("清理旧图片: %s", f)
#         except Exception as e:
#             logger.warning("清理失败 %s: %s", f, e)

# ========= 上传函数 ==========
def upload(data: dict | None) -> None:
    """上传一帧数据到服务器，含文件句柄安全关闭和重试。"""
    if not data:
        return

    img_path = data.get("image", "")
    if not img_path:
        # 有帧数据但没有图片路径，说明 C++ 端可能未输出图片行
        logger.warning("帧 %s 无图片路径，跳过上传", data["frameNo"])
        return

    if not os.path.exists(img_path):
        logger.warning("帧 %s 图片不存在: %s", data["frameNo"], img_path)
        return

    try:
        # with 语句确保文件句柄一定被关闭
        with open(img_path, "rb") as img_file:
            files = {"file": img_file}
            form = {
                "deviceId":     DEVICE_ID,
                "frameNo":      str(data["frameNo"]),
                "detectCount":  str(data["detectCount"]),
                "targets":      json.dumps(data["targets"], ensure_ascii=False),
            }
            r = session.post(SERVER_URL, data=form, files=files, timeout=10)
            r.raise_for_status()   # 4xx/5xx 视为异常

            logger.info("上传成功: 帧 %s，目标数 %s", data["frameNo"], data["detectCount"])

            # 上传成功后删除本地图片
            try:
                os.remove(img_path)
                logger.info("已删除图片: %s", img_path)
            except OSError as e:
                logger.warning("删除图片失败: %s — %s", img_path, e)
    except requests.exceptions.Timeout:
        logger.error("上传超时: 帧 %s", data["frameNo"])
    except requests.exceptions.ConnectionError as e:
        logger.error("网络连接失败: 帧 %s — %s", data["frameNo"], e)
    except requests.exceptions.HTTPError as e:
        logger.error("服务器返回错误: 帧 %s — %s", data["frameNo"], e)
    except OSError as e:
        logger.error("文件读取失败: 帧 %s — %s", data["frameNo"], e)


# ========= 启动模型进程 ==========

shell_cmd = """
export LD_LIBRARY_PATH=/opt/glibc-2.38/lib:/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu
/opt/glibc-2.38/lib/ld-linux-aarch64.so.1 ./rknn_yolov8_demo model/yolov8.rknn
"""


process = subprocess.Popen(
    shell_cmd,
    shell=True,
    executable="/bin/bash",
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    text=True,
    encoding="utf-8",
    errors="ignore",
    bufsize=1,
)


#新增上传线程
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

logger.info("模型进程已启动，PID: %d", process.pid)
worker = threading.Thread(target=upload_worker, daemon=True)
worker.start()

frame_data: dict | None = None

# ========= 主循环 ==========
try:
    while True:
        line = process.stdout.readline()

        if not line:
            if process.poll() is not None:
                break

            time.sleep(0.001)
            continue
        logger.debug(">> %s", line)

        # 新帧开始 —— 先上传上一帧
        m = RE_FRAME.search(line)
        if m:
            if frame_data:
                try:
                    upload_queue.put_nowait(frame_data.copy())
                except queue.Full:
                    logger.warning("上传队列已满，丢弃帧 %s", frame_data["frameNo"])

#             if frame_data and frame_data["frameNo"] % 50 == 0:
#                 cleanup_old_images()

            frame_data = {
                "frameNo": int(m.group(1)),
                "detectCount": 0,
                "targets": [],
                "image": "",
            }
            continue

        if frame_data is None:
            continue

        # 人体置信度
        m = RE_CONF.search(line)
        if m:
            score = float(m.group(1))

            target = {
                "index": len(frame_data["targets"]),
                "label": "person",
                "score": score,
                "centerX": 0,
                "centerY": 0,
            }

            frame_data["targets"].append(target)
            continue


        # 中心点
        m = RE_CENTER.search(line)
        if m and frame_data["targets"]:
            cx = int(m.group(1))
            cy = int(m.group(2))

            frame_data["targets"][-1]["centerX"] = cx
            frame_data["targets"][-1]["centerY"] = cy
            continue


        # 是否检测到人
        m = RE_DETECTED.search(line)
        if m:
            detected = m.group(1)

            if detected == "是":
                frame_data["detectCount"] = len(frame_data["targets"])
            else:
                frame_data["detectCount"] = 0
                frame_data["targets"] = []

            continue
        # 图片路径
        m = RE_IMAGE.search(line)
        if m:
            frame_data["image"] = m.group(1)

finally:
    if frame_data:
        upload_queue.put(frame_data.copy())

    upload_queue.put(None)

    worker.join()

    process.wait()

    exit_code = process.returncode

    if exit_code != 0:
        logger.error("模型进程异常退出，退出码: %d", exit_code)
    else:
        logger.info("模型进程正常退出")