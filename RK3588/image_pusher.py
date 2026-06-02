import os
import json
import logging
import asyncio
import base64
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
FRAME_DIR   = "/home/elf/demo/make/frames_int8_pixel_box"
KEEP_RECENT = 10

logger.info("WS推流地址: %s", WS_PUSH_URL)

# 只保留最新1帧
new_frame_queue = queue.Queue(maxsize=1)


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
        deleted = 0
        for f in files[:-KEEP_RECENT]:
            try:
                os.remove(f)
                deleted += 1
            except OSError:
                pass
        if deleted:
            logger.info("清理旧帧 %d 张", deleted)
    except Exception as e:
        logger.error("清理失败: %s", e)


# =========================================================
# 动态等待文件写入完成
# 间隔从 5ms 开始，逐步增大
# 文件大小连续两次相同即认为写入完成
# =========================================================
async def wait_file_complete(path, max_wait_ms=200):
    max_wait  = max_wait_ms / 1000
    elapsed   = 0
    last_size = -1

    # 动态间隔：5ms → 10ms → 20ms → 20ms → ...
    # 文件写得快时，5ms 就能检测到稳定，延迟最小
    intervals = [0.005, 0.010, 0.020, 0.020, 0.020,
                 0.020, 0.020, 0.020, 0.020, 0.020]

    for interval in intervals:
        await asyncio.sleep(interval)
        elapsed += interval

        try:
            size = os.path.getsize(path)
        except OSError:
            # 文件被删了
            return False

        if size > 0 and size == last_size:
            # 文件大小稳定，写入完成
            logger.debug(
                "文件写入完成 %s size=%d elapsed=%.0fms",
                os.path.basename(path), size, elapsed * 1000
            )
            return True

        last_size = size

        if elapsed >= max_wait:
            logger.warning(
                "等待写入超时 %s elapsed=%.0fms last_size=%d",
                os.path.basename(path), elapsed * 1000, last_size
            )
            break

    # 超时但有内容，仍然尝试推送
    return last_size > 0


# =========================================================
# Base64 编码放线程池，不阻塞事件循环
# =========================================================
def read_and_encode(path):
    with open(path, "rb") as f:
        img_bytes = f.read()
    if len(img_bytes) == 0:
        return None
    return "data:image/jpeg;base64," + base64.b64encode(img_bytes).decode("utf-8")


# =========================================================
# 文件监听协程
# watchfiles 底层用 Rust inotify，批量合并事件
# 同一文件多次写入只触发一次
# =========================================================
async def file_watcher():
    logger.info("文件监听启动，监听目录: %s", FRAME_DIR)

    async for changes in awatch(FRAME_DIR):
        for change_type, path in changes:

            # 只处理新建和修改，忽略删除
            if change_type not in (Change.added, Change.modified):
                continue

            if not path.endswith(".jpg"):
                continue

            # 等文件写入完成
            ok = await wait_file_complete(path)
            if not ok:
                logger.warning(
                    "文件不可用，跳过: %s",
                    os.path.basename(path)
                )
                continue

            # 只保留最新帧，丢弃积压的旧帧
            while not new_frame_queue.empty():
                try:
                    new_frame_queue.get_nowait()
                except queue.Empty:
                    break
            try:
                new_frame_queue.put_nowait(path)
            except queue.Full:
                pass


# =========================================================
# WebSocket 推流协程
# =========================================================
async def push_loop():
    clean_counter = 0

    while True:
        try:
            async with websockets.connect(
                WS_PUSH_URL,
                ping_interval=20,
                ping_timeout=10,
                max_size=10 * 1024 * 1024,
            ) as ws:
                logger.info("WS推流连接成功")

                while True:

                    # 阻塞等待新帧，无新帧完全休眠不占 CPU
                    try:
                        loop = asyncio.get_running_loop()
                        path = await loop.run_in_executor(
                            None,
                            lambda: new_frame_queue.get(timeout=2)
                        )
                    except Exception:
                        # 2秒无新帧，发心跳保活
                        try:
                            await ws.ping()
                        except Exception:
                            break
                        continue

                    if not os.path.exists(path):
                        continue

                    try:
                        # 文件读取 + Base64 编码放线程池
                        loop = asyncio.get_running_loop()
                        image_data = await loop.run_in_executor(
                            None,
                            read_and_encode,
                            path
                        )

                        if image_data is None:
                            logger.warning(
                                "空文件跳过: %s",
                                os.path.basename(path)
                            )
                            continue

                        payload = json.dumps({
                            "deviceId":    DEVICE_ID,
                            "imageBase64": image_data,
                        })

                        await ws.send(payload)

                        logger.info(
                            "推图成功 %s (%.1fKB)",
                            os.path.basename(path),
                            len(payload) / 1024
                        )

                        clean_counter += 1
                        if clean_counter >= 20:
                            loop = asyncio.get_running_loop()
                            await loop.run_in_executor(
                                None,
                                clean_old_frames
                            )
                            clean_counter = 0

                    except websockets.ConnectionClosed:
                        logger.warning("WS连接已关闭，准备重连")
                        break
                    except Exception as e:
                        logger.error("推图异常: %s", e)

        except Exception as e:
            logger.error("WS断开: %s，3秒后重连", e)
            await asyncio.sleep(3)


# =========================================================
# 启动：监听和推流并发运行
# =========================================================
async def main():
    await asyncio.gather(
        file_watcher(),
        push_loop(),
    )


if __name__ == "__main__":
    asyncio.run(main())