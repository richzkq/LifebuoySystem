import os
import json
import struct
import shutil
import time
import logging
import asyncio
import websockets
import queue
import re

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s"
)
logger = logging.getLogger(__name__)

with open("IPconfig.json", "r", encoding="utf-8") as f:
    config = json.load(f)

WS_PUSH_URL = (
    f"ws://{config['server_ip']}:"
    f"{config['server_port']}"
    f"/ws-frame"
)

DEVICE_ID = config["device_id"]

AI_MODEL_OUTPUT = "/home/elf/demo/make/frames_int8_pixel_box"
RAMDISK_DIR     = "/dev/shm/frames_int8_pixel_box"

os.makedirs(RAMDISK_DIR, exist_ok=True)

if os.path.islink(AI_MODEL_OUTPUT):
    target = os.readlink(AI_MODEL_OUTPUT)
    if target != RAMDISK_DIR:
        logger.warning("软链接目标不匹配: %s → %s", AI_MODEL_OUTPUT, target)
elif os.path.isdir(AI_MODEL_OUTPUT):
    logger.info("移动现有帧到内存盘...")
    for f in os.listdir(AI_MODEL_OUTPUT):
        src = os.path.join(AI_MODEL_OUTPUT, f)
        dst = os.path.join(RAMDISK_DIR, f)
        try:
            if os.path.isfile(src):
                shutil.move(src, dst)
        except Exception:
            pass
    try:
        os.rmdir(AI_MODEL_OUTPUT)
    except OSError:
        shutil.rmtree(AI_MODEL_OUTPUT, ignore_errors=True)
    os.symlink(RAMDISK_DIR, AI_MODEL_OUTPUT)
    logger.info("已建立软链接: %s → %s", AI_MODEL_OUTPUT, RAMDISK_DIR)
elif not os.path.exists(AI_MODEL_OUTPUT):
    os.symlink(RAMDISK_DIR, AI_MODEL_OUTPUT)
    logger.info("已建立软链接: %s → %s", AI_MODEL_OUTPUT, RAMDISK_DIR)

FRAME_DIR = RAMDISK_DIR
KEEP_RECENT = 5       # 目录里只保留最新 N 帧
QUEUE_SIZE  = 1        # 队列只保留最新帧路径

new_frame_queue = queue.Queue(maxsize=QUEUE_SIZE)
RE_FRAME_FILE = re.compile(r"^frame_(\d+)\.jpg$")


def extract_frame_no(filename):
    m = RE_FRAME_FILE.match(filename)
    return int(m.group(1)) if m else None


def read_and_pack(path):
    try:
        with open(path, "rb") as f:
            jpg = f.read()
        if len(jpg) < 500:
            return None
        dev_bytes = DEVICE_ID.encode("utf
        -8")
        header = struct.pack(">I", len(dev_bytes))
        return header + dev_bytes + jpg
    except OSError:
        return None


def cleanup_dir(keep_count):
    """删旧帧，只保留最新 keep_count 张（运行在线程池，不阻塞协程）"""
    try:
        items = []
        for f in os.listdir(FRAME_DIR):
            fn = extract_frame_no(f)
            if fn is not None:
                items.append((fn, os.path.join(FRAME_DIR, f)))
        if len(items) <= keep_count:
            return
        items.sort(key=lambda x: x[0])
        for _, path in items[:-keep_count]:
            try:
                os.remove(path)
            except OSError:
                pass
    except Exception as e:
        logger.error("清理失败: %s", e)


async def file_watcher():
    logger.info("文件轮询启动，目录: %s (内存盘)", FRAME_DIR)
    last_frame_no = -1

    while True:
        try:
            latest_no = -1
            latest_path = None

            for f in os.listdir(FRAME_DIR):
                frame_no = extract_frame_no(f)
                if frame_no is None:
                    continue
                if frame_no > latest_no:
                    latest_no = frame_no
                    latest_path = os.path.join(FRAME_DIR, f)

            if latest_path is None or latest_no <= last_frame_no:
                await asyncio.sleep(0.008)
                continue

            last_frame_no = latest_no

            # 清空队列，只保留最新
            while True:
                try:
                    new_frame_queue.get_nowait()
                    new_frame_queue.task_done()
                except queue.Empty:
                    break
            try:
                new_frame_queue.put_nowait(latest_path)
            except queue.Full:
                pass

        except Exception as e:
            logger.error("文件轮询异常: %s", e)

        await asyncio.sleep(0.008)


async def push_loop():
    loop = asyncio.get_running_loop()
    send_count = 0

    while True:
        try:
            async with websockets.connect(
                WS_PUSH_URL,
                ping_interval=20,
                ping_timeout=10,
                max_size=512 * 1024,
            ) as ws:
                logger.info("WS推流连接成功")
                send_count = 0

                while True:
                    try:
                        path = await loop.run_in_executor(
                            None,
                            lambda: new_frame_queue.get(timeout=2)
                        )
                    except Exception:
                        try:
                            await ws.ping()
                        except Exception:
                            break
                        continue

                    # 清空队列，只保留最新
                    while True:
                        try:
                            newer = new_frame_queue.get_nowait()
                            new_frame_queue.task_done()
                            path = newer
                        except queue.Empty:
                            break

                    if not os.path.exists(path):
                        continue

                    try:
                        payload = await loop.run_in_executor(None, read_and_pack, path)
                        if payload is None:
                            continue

                        try:
                            await asyncio.wait_for(ws.send(payload), timeout=0.25)
                        except asyncio.TimeoutError:
                            logger.warning("WS发送超时，重连")
                            break

                        send_count += 1

                    except websockets.ConnectionClosed:
                        logger.warning("WS断开，重连...")
                        break
                    except Exception as e:
                        logger.error("推图异常: %s", e)

        except Exception as e:
            logger.error("WS连接失败: %s，3秒后重试", e)
            await asyncio.sleep(3)


async def cleanup_loop():
    """每 2 秒清理一次旧帧，与推送速率完全解耦"""
    loop = asyncio.get_running_loop()
    while True:
        await asyncio.sleep(2)
        await loop.run_in_executor(None, cleanup_dir, KEEP_RECENT)


async def main():
    # 启动时清空旧帧，避免旧编号阻塞新帧检测
    for f in os.listdir(FRAME_DIR):
        if f.endswith(".jpg"):
            try:
                os.remove(os.path.join(FRAME_DIR, f))
            except OSError:
                pass
    logger.info("启动时清空旧帧完成")

    await asyncio.gather(file_watcher(), push_loop(), cleanup_loop())


if __name__ == "__main__":
    asyncio.run(main())
