import subprocess
import re
import requests
import os
import json
import logging
import time
import glob
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
RE_FRAME    = re.compile(r'=== 第\s*(\d+)\s*帧 ===')
RE_COUNT_1  = re.compile(r'检测数量:\s*(\d+)')
RE_COUNT_2  = re.compile(r'检测到\s*(\d+)\s*个目标')
# [\w\u4e00-\u9fff]+ 同时兼容英文和中文标签
RE_TARGET   = re.compile(
    r'目标\[(\d+)\]:\s*([\w\u4e00-\u9fff]+)\s*\(([\d.]+)\)'
    r'\s*位置:\s*\((\d+),(\d+)\)-\((\d+),(\d+)\)'
)
RE_IMAGE    = re.compile(r'保存结果图像:\s*(result_\d+\.jpg)')

def cleanup_old_images():
    """删除旧图片，防止异常情况下堆积"""
    files = glob.glob("result_*.jpg")

    # 只保留最新20张
    if len(files) <= 20:
        return

    files.sort(key=os.path.getmtime)

    old_files = files[:-20]

    for f in old_files:
        try:
            os.remove(f)
            logger.info("清理旧图片: %s", f)
        except Exception as e:
            logger.warning("清理失败 %s: %s", f, e)

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

logger.info("模型进程已启动，PID: %d", process.pid)

frame_data: dict | None = None

# ========= 主循环 ==========
try:
    for line in process.stdout:
        line = line.strip()
        if not line:
            continue
        logger.debug(">> %s", line)

        # 新帧开始 —— 先上传上一帧
        m = RE_FRAME.search(line)
        if m:
            upload(frame_data)
            cleanup_old_images()
            frame_data = {
                "frameNo":      int(m.group(1)),
                "detectCount":  0,
                "targets":      [],
                "image":        "",
            }
            continue

        if frame_data is None:
            continue

        # 检测数量（兼容两种格式）
        m = RE_COUNT_1.search(line) or RE_COUNT_2.search(line)
        if m:
            frame_data["detectCount"] = int(m.group(1))
            continue

        # 检测目标
        m = RE_TARGET.search(line)
        if m:
            frame_data["targets"].append({
                "index": int(m.group(1)),
                "label": m.group(2),
                "score": float(m.group(3)),
                "x1":    int(m.group(4)),
                "y1":    int(m.group(5)),
                "x2":    int(m.group(6)),
                "y2":    int(m.group(7)),
            })
            continue

        # 图片路径
        m = RE_IMAGE.search(line)
        if m:
            frame_data["image"] = m.group(1)

finally:
    # ========= 修复：上传最后一帧 ==========
    upload(frame_data)
    cleanup_old_images()

    process.wait()
    exit_code = process.returncode

    if exit_code != 0:
        logger.error("模型进程异常退出，退出码: %d", exit_code)
    else:
        logger.info("模型进程正常退出")