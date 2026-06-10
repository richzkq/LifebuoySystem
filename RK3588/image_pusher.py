import os
import json
import struct
import shutil
import logging
import asyncio
import websockets
import queue
import time
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
        logger.warning(
            "软链接目标不匹配: %s → %s (期望 → %s)",
            AI_MODEL_OUTPUT,
            target,
            RAMDISK_DIR
        )
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

# 保留最近几张即可，不能保留太多旧帧
KEEP_RECENT = 5

# 只保留最新帧路径
QUEUE_SIZE = 1
new_frame_queue = queue.Queue(maxsize=QUEUE_SIZE)

RE_FRAME_FILE = re.compile(r"^frame_(\d+)\.jpg$")


def extract_frame_no(filename):
    m = RE_FRAME_FILE.match(filename)
    if not m:
        return None
    try:
        return int(m.group(1))
    except Exception:
        return None


def clean_old_frames():
    try:
        items = []

        for f in os.listdir(FRAME_DIR):
            frame_no = extract_frame_no(f)
            if frame_no is None:
                continue

            path = os.path.join(FRAME_DIR, f)
            if os.path.isfile(path):
                items.append((frame_no, path))

        if len(items) <= KEEP_RECENT:
            return

        items.sort(key=lambda x: x[0])

        for _, path in items[:-KEEP_RECENT]:
            try:
                os.remove(path)
            except OSError:
                pass

    except Exception as e:
        logger.error("清理失败: %s", e)


def is_jpg_complete(path):
    """
    判断 JPG 是否写完整。
    不能只判断文件存在，因为 C++ 可能刚创建文件但还没写完。
    """
    try:
        if not os.path.exists(path):
            return False

        size1 = os.path.getsize(path)
        if size1 < 1000:
            return False

        # 等 4ms，再看大小是否稳定
        time.sleep(0.004)

        if not os.path.exists(path):
            return False

        size2 = os.path.getsize(path)
        if size2 != size1 or size2 < 1000:
            return False

        with open(path, "rb") as f:
            head = f.read(2)
            if len(head) != 2:
                return False

            f.seek(-2, os.SEEK_END)
            tail = f.read(2)

        # JPEG 开头 FF D8，结尾 FF D9
        if head != b"\xff\xd8":
            return False

        if tail != b"\xff\xd9":
            return False

        return True

    except Exception:
        return False


def read_and_pack(path):
    """直接读 JPG（/dev/shm 内存盘上文件写完才轮询到，无需等）"""
    try:
        with open(path, "rb") as f:
            jpg = f.read()
        if len(jpg) < 500:
            return None
        dev_bytes = DEVICE_ID.encode("utf-8")
        header = struct.pack(">I", len(dev_bytes))
        return header + dev_bytes + jpg
    except OSError:
        return None


async def file_watcher():
    logger.info("文件轮询启动，目录: %s (内存盘)", FRAME_DIR)

    last_frame_no = -1

    while True:
        try:
            latest_no = -1
            latest_path = None

            # 只按 frame 编号找最新帧，不再按 mtime
            for f in os.listdir(FRAME_DIR):
                frame_no = extract_frame_no(f)
                if frame_no is None:
                    continue

                if frame_no > latest_no:
                    latest_no = frame_no
                    latest_path = os.path.join(FRAME_DIR, f)

            if latest_path is None:
                await asyncio.sleep(0.008)
                continue

            # 永远不回头推旧编号
            if latest_no <= last_frame_no:
                await asyncio.sleep(0.008)
                continue

            # 不入队时检查完整性——留给 read_and_pack（线程池）去做
            # file_watcher 是 async 协程，不能调 time.sleep()
            last_frame_no = latest_no

            # 新帧来了，清空队列，只保留最新
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
    clean_counter = 0
    loop = asyncio.get_running_loop()

    send_count = 0
    fps_t0 = time.time()

    while True:
        try:
            async with websockets.connect(
                WS_PUSH_URL,
                ping_interval=20,
                ping_timeout=10,
                max_size=512 * 1024,
            ) as ws:
                logger.info("WS推流连接成功")

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

                    # 如果队列里有更新的路径，全部丢掉，只保留最后一个
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
                            # 不完整帧直接跳过，不推给网页，避免闪一下
                            continue

                        try:
                            await asyncio.wait_for(ws.send(payload), timeout=0.25)
                        except asyncio.TimeoutError:
                            logger.warning("WS发送超过0.25秒，准备重连")
                            break

                        send_count += 1
                        clean_counter += 1

                        now = time.time()
                        if now - fps_t0 >= 1.0:
                            fps = send_count / (now - fps_t0)
                            logger.info(
                                "推流FPS=%.2f 最新帧=%s 大小=%.1fKB",
                                fps,
                                os.path.basename(path),
                                len(payload) / 1024
                            )
                            send_count = 0
                            fps_t0 = now

                        if clean_counter >= 300:
                            await loop.run_in_executor(None, clean_old_frames)
                            clean_counter = 0

                    except websockets.ConnectionClosed:
                        logger.warning("WS断开，重连...")
                        break

                    except Exception as e:
                        logger.error("推图异常: %s", e)

        except Exception as e:
            logger.error("WS连接失败: %s，3秒后重试", e)
            await asyncio.sleep(3)


async def main():
    await asyncio.gather(
        file_watcher(),
        push_loop(),
    )


if __name__ == "__main__":
    asyncio.run(main())