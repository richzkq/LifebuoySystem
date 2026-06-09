import os
import json
import struct
import shutil
import logging
import asyncio
import websockets
import queue

from watchfiles import awatch, Change

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
DEVICE_ID   = config["device_id"]

# =========================================================
# 帧目录初始化（自动配置内存盘，零磁盘 IO）
# AI 模型写这里 → 软链接指向内存盘 → 读写都是 RAM，延迟 <1ms
# =========================================================
AI_MODEL_OUTPUT = "/home/elf/demo/make/frames_int8_pixel_box"
RAMDISK_DIR     = "/dev/shm/frames_int8_pixel_box"

os.makedirs(RAMDISK_DIR, exist_ok=True)

# 自动建立软链接：AI 模型路径 → 内存盘
if os.path.islink(AI_MODEL_OUTPUT):
    # 已经是软链接，检查是否指向正确位置
    target = os.readlink(AI_MODEL_OUTPUT)
    if target != RAMDISK_DIR:
        logger.warning("软链接目标不匹配: %s → %s (期望 → %s)", AI_MODEL_OUTPUT, target, RAMDISK_DIR)
elif os.path.isdir(AI_MODEL_OUTPUT):
    # 是真实目录 → 移动内容到内存盘，替换为软链接
    logger.info("移动现有帧到内存盘...")
    for f in os.listdir(AI_MODEL_OUTPUT):
        shutil.move(os.path.join(AI_MODEL_OUTPUT, f), RAMDISK_DIR)
    os.rmdir(AI_MODEL_OUTPUT)
    os.symlink(RAMDISK_DIR, AI_MODEL_OUTPUT)
    logger.info("已建立软链接: %s → %s", AI_MODEL_OUTPUT, RAMDISK_DIR)
elif not os.path.exists(AI_MODEL_OUTPUT):
    # 不存在 → 直接建软链接
    os.symlink(RAMDISK_DIR, AI_MODEL_OUTPUT)
    logger.info("已建立软链接: %s → %s", AI_MODEL_OUTPUT, RAMDISK_DIR)

FRAME_DIR   = RAMDISK_DIR
KEEP_RECENT = 10
# awatch 批量通知会一次传 5-10 个文件，队列太小导致大量丢帧
QUEUE_SIZE  = 30

new_frame_queue = queue.Queue(maxsize=QUEUE_SIZE)


# =========================================================
# 清理旧帧
# =========================================================
def clean_old_frames():
    try:
        files = [
            os.path.join(FRAME_DIR, f)
            for f in os.listdir(FRAME_DIR)
            if f.endswith(".jpg")
        ]
        if len(files) <= KEEP_RECENT:
            return
        files.sort(key=os.path.getmtime)
        for f in files[:-KEEP_RECENT]:
            try:
                os.remove(f)
            except OSError:
                pass
        if len(files) - KEEP_RECENT > 0:
            logger.debug("清理旧帧 %d 张", len(files) - KEEP_RECENT)
    except Exception as e:
        logger.error("清理失败: %s", e)


# =========================================================
# 读取 JPEG + 构建二进制帧
# 内存盘上文件写完即完整，无需等待
# =========================================================
def read_and_pack(path):
    try:
        with open(path, "rb") as f:
            jpg = f.read()
        if len(jpg) == 0:
            return None
        dev_bytes = DEVICE_ID.encode("utf-8")
        header = struct.pack(">I", len(dev_bytes))
        return header + dev_bytes + jpg
    except OSError:
        return None


# =========================================================
# 文件监听协程
# =========================================================
async def file_watcher():
    logger.info("文件监听启动，目录: %s (内存盘)", FRAME_DIR)

    async for changes in awatch(FRAME_DIR):
        for change_type, path in changes:
            if change_type not in (Change.added, Change.modified):
                continue
            if not path.endswith(".jpg"):
                continue

            # 内存盘：文件写完即完整，直接入队无需等待
            # 队列满时丢弃最旧的帧（不阻塞）
            try:
                new_frame_queue.put_nowait(path)
            except queue.Full:
                try:
                    old = new_frame_queue.get_nowait()
                    new_frame_queue.task_done()
                except queue.Empty:
                    pass
                try:
                    new_frame_queue.put_nowait(path)
                except queue.Full:
                    pass


# =========================================================
# WebSocket 推流协程
# =========================================================
async def push_loop():
    clean_counter = 0
    loop = asyncio.get_running_loop()

    while True:
        try:
            async with websockets.connect(
                WS_PUSH_URL,
                ping_interval=20,
                ping_timeout=10,
                max_size=512 * 1024,   # 512KB
            ) as ws:
                logger.info("WS推流连接成功")

                while True:
                    # 等新帧
                    try:
                        path = await loop.run_in_executor(
                            None,
                            lambda: new_frame_queue.get(timeout=2)
                        )
                    except Exception:
                        # 超时无帧，发 ping 保活
                        try:
                            await ws.ping()
                        except Exception:
                            break
                        continue

                    if not os.path.exists(path):
                        continue

                    try:
                        # 读文件 + 打包放线程池（内存盘读取 < 1ms）
                        payload = await loop.run_in_executor(None, read_and_pack, path)
                        if payload is None:
                            logger.warning("空文件跳过: %s", os.path.basename(path))
                            continue

                        await ws.send(payload)
                        logger.info("推图成功 %s (%.1fKB)",
                                     os.path.basename(path), len(payload) / 1024)

                        clean_counter += 1
                        if clean_counter >= 100:
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


# =========================================================
# 启动
# =========================================================
async def main():
    await asyncio.gather(
        file_watcher(),
        push_loop(),
    )


if __name__ == "__main__":
    asyncio.run(main())
