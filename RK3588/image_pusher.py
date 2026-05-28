import os
import time
import json
import glob
import threading
import logging
import requests

logging.basicConfig(level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)

with open("IPconfig.json", "r", encoding="utf-8") as f:
    config = json.load(f)

# 注意:推到服务器的 /device/frame,不是 /device/upload
FRAME_URL = f"http://{config['server_ip']}:{config['server_port']}/device/frame"
DEVICE_ID = config["device_id"]

FRAME_DIR = "/home/elf/demo/make/frames_int8_pixel_box"

PUSH_INTERVAL = 0.1      # 推流间隔,约 10fps,可调
CLEAN_INTERVAL = 30      # 每 30 秒清理一次
KEEP_RECENT = 20         # 每次清理后只保留最新的 20 张

session = requests.Session()

def get_latest_jpg():
    try:
        files = glob.glob(os.path.join(FRAME_DIR, "*.jpg"))
        if not files:
            return None
        return max(files, key=os.path.getmtime)
    except Exception:
        return None

# ============ 推图线程 ============
def push_worker():
    last_path = None
    last_mtime = 0
    while True:
        path = get_latest_jpg()
        if path:
            try:
                mtime = os.path.getmtime(path)
                if path != last_path or mtime != last_mtime:
                    with open(path, "rb") as fp:
                        img_bytes = fp.read()
                    files = {"file": ("frame.jpg", img_bytes, "image/jpeg")}
                    session.post(FRAME_URL,
                                 data={"deviceId": DEVICE_ID},
                                 files=files, timeout=5)
                    last_path = path
                    last_mtime = mtime
            except Exception as e:
                logger.error("推图失败: %s", e)
        time.sleep(PUSH_INTERVAL)

# ============ 清理线程 ============
def clean_worker():
    while True:
        time.sleep(CLEAN_INTERVAL)
        try:
            files = glob.glob(os.path.join(FRAME_DIR, "*.jpg"))
            if len(files) <= KEEP_RECENT:
                continue
            # 按修改时间排序,删掉旧的,只留最新 KEEP_RECENT 张
            files.sort(key=os.path.getmtime)
            to_delete = files[:-KEEP_RECENT]
            for f in to_delete:
                try:
                    os.remove(f)
                except Exception:
                    pass
            logger.info("清理图片 %d 张,剩余 %d 张",
                        len(to_delete), KEEP_RECENT)
        except Exception as e:
            logger.error("清理失败: %s", e)

if __name__ == "__main__":
    threading.Thread(target=push_worker, daemon=True).start()
    threading.Thread(target=clean_worker, daemon=True).start()
    logger.info("图片推送+清理服务启动,目标 %s", FRAME_URL)
    while True:
        time.sleep(60)