import subprocess
import re
import os
import json
import logging
import time
import threading
import queue
import asyncio
import aiohttp

from concurrent.futures import ThreadPoolExecutor

# =========================================================
# 日志
# =========================================================
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[
        logging.StreamHandler(),
        logging.FileHandler("push_device.log", encoding="utf-8")
    ]
)
logger = logging.getLogger(__name__)

# =========================================================
# 读取配置
# =========================================================
with open("IPconfig.json", "r", encoding="utf-8") as f:
    config = json.load(f)

SERVER_URL = (
    f"http://{config['server_ip']}:"
    f"{config['server_port']}"
    f"{config['upload_path']}"
)
DEVICE_ID = config["device_id"]
logger.info("服务器地址: %s", SERVER_URL)

# =========================================================
# RK3588 项目目录
# =========================================================
ROOT_DIR       = "/home/elf/demo/make"
MODEL_EXEC     = os.path.join(ROOT_DIR, "rknn_vision")
DROWNING_MODEL = "/home/elf/demo/best_int8_fixed.rknn"
PERSON_MODEL   = "/home/elf/demo/yolov8m_int8_fixed.rknn"
logger.info("ROOT_DIR = %s", ROOT_DIR)

# =========================================================
# 上传队列
# 核心优化：丢旧帧保新帧
# 队列满时弹出最旧的帧，插入最新的帧
# 保证服务器收到的永远是最新数据
# =========================================================
QUEUE_SIZE   = 30
upload_queue = queue.Queue(maxsize=QUEUE_SIZE)
_stop_event  = threading.Event()

# =========================================================
# 正则
# =========================================================
RE_FRAME          = re.compile(r"=+\s*帧\s*(\d+)\s*=+")
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
# 丢旧帧保新帧入队
# =========================================================
def enqueue_drop_old(data):
    while True:
        try:
            upload_queue.put_nowait(data)
            return
        except queue.Full:
            # 队列满，弹出最旧的帧
            try:
                dropped = upload_queue.get_nowait()
                upload_queue.task_done()
                logger.warning(
                    "队列满，丢弃旧帧 %s 保留新帧 %s",
                    dropped["frameNo"],
                    data["frameNo"]
                )
            except queue.Empty:
                pass


# =========================================================
# 异步上传单帧
# =========================================================
async def upload_once(session, data):
    form = aiohttp.FormData()
    form.add_field("deviceId",      DEVICE_ID)
    form.add_field("frameNo",       str(data["frameNo"]))
    form.add_field("drowningCount", str(data["drowningCount"]))
    form.add_field("personCount",   str(data["drowningCount"] + data["personOutOfWater"]))
    form.add_field("callForHelp",   str(data["callForHelp"]))
    form.add_field("pressure",      str(data["pressure"]))
    form.add_field("alarm",         str(data["alarm"]))
    form.add_field("targets",       json.dumps(data["targets"], ensure_ascii=False))

    try:
        async with session.post(
            SERVER_URL,
            data=form,
            timeout=aiohttp.ClientTimeout(total=5)
        ) as resp:
            resp.raise_for_status()
            logger.info(
                "上传成功 frame=%s drowning=%s call=%s alarm=%s",
                data["frameNo"],
                data["drowningCount"],
                data["callForHelp"],
                data["alarm"],
            )
    except asyncio.TimeoutError:
        logger.error("上传超时 frame=%s", data["frameNo"])
    except Exception as e:
        logger.error("上传失败 frame=%s: %s", data["frameNo"], e)


# =========================================================
# 异步上传主循环
# 核心优化：
# 1. aiohttp 异步 HTTP，不阻塞
# 2. 并发上传，最多同时 3 个请求
# 3. 用信号量控制并发数，防止服务器过载
# =========================================================
async def upload_loop():
    # 连接池复用，减少 TCP 握手开销
    connector = aiohttp.TCPConnector(
        limit=10,           # 最大连接数
        keepalive_timeout=30,
        enable_cleanup_closed=True,
    )

    # 并发信号量：最多同时 3 个上传请求
    semaphore = asyncio.Semaphore(3)

    async with aiohttp.ClientSession(connector=connector) as session:
        loop = asyncio.get_event_loop()

        async def upload_with_sem(data):
            async with semaphore:
                await upload_once(session, data)

        while not _stop_event.is_set():
            # 从队列取数据（放线程池避免阻塞事件循环）
            try:
                data = await loop.run_in_executor(
                    None,
                    lambda: upload_queue.get(timeout=1)
                )
            except queue.Empty:
                continue

            if data is None:
                upload_queue.task_done()
                break

            # 异步并发上传，不等待结果直接取下一帧
            asyncio.create_task(upload_with_sem(data))
            upload_queue.task_done()

        # 等待所有未完成的上传任务
        pending = [t for t in asyncio.all_tasks()
                   if t is not asyncio.current_task()]
        if pending:
            logger.info("等待 %d 个上传任务完成...", len(pending))
            await asyncio.gather(*pending, return_exceptions=True)

    logger.info("上传协程退出")


# =========================================================
# 异步上传线程入口
# =========================================================
def start_upload_loop():
    asyncio.run(upload_loop())


# =========================================================
# stderr 读取线程
# =========================================================
def stderr_reader(proc):
    for line in proc.stderr:
        line = line.strip()
        if line:
            logger.error("[STDERR] %s", line)


# =========================================================
# 启动模型进程
# =========================================================
process = subprocess.Popen(
    [MODEL_EXEC, DROWNING_MODEL, PERSON_MODEL],
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    text=True,
    encoding="utf-8",
    errors="ignore",
    bufsize=1,
    cwd=ROOT_DIR,
)
logger.info("模型进程启动 PID=%d", process.pid)

# =========================================================
# 启动线程
# =========================================================
stderr_thread = threading.Thread(
    target=stderr_reader,
    args=(process,),
    daemon=True
)
stderr_thread.start()

upload_thread = threading.Thread(
    target=start_upload_loop,
    daemon=True
)
upload_thread.start()

# =========================================================
# 主循环
# =========================================================
frame_data = None

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
        print(line)

        # ── 新帧 ──────────────────────────────────────────
        m = RE_FRAME.search(line)
        if m:
            if frame_data:
                frame_data["alarm"] = compute_alarm(frame_data)
                enqueue_drop_old(frame_data.copy())

            frame_data = {
                "frameNo":          int(m.group(1)),
                "drowningCount":    0,
                "personOutOfWater": 0,
                "callForHelp":      0,
                "pressure":         0,
                "alarm":            0,
                "targets":          [],
            }
            continue

        if frame_data is None:
            continue

        if line.startswith("[FPS]"):
            continue

        # ── 字段解析 ─────────────────────────────────────
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

        m = RE_TARGET.search(line)
        if m:
            frame_data["targets"].append({
                "index":   len(frame_data["targets"]),
                "label":   "drowning",
                "score":   float(m.group(1)),
                "centerX": int(m.group(2)),
                "centerY": int(m.group(3)),
            })

# =========================================================
# 退出
# =========================================================
finally:
    logger.info("程序退出中...")

    if frame_data:
        frame_data["alarm"] = compute_alarm(frame_data)
        try:
            enqueue_drop_old(frame_data.copy())
        except Exception:
            pass

    # 通知上传协程退出
    _stop_event.set()
    upload_queue.put(None)

    # 等待上传线程完成，最多10秒
    upload_thread.join(timeout=10)
    if upload_thread.is_alive():
        logger.warning("上传线程未能在10秒内退出")

    stderr_thread.join(timeout=3)

    # 终止模型进程
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            logger.warning("模型进程强制终止")

    logger.info("模型结束，退出码: %s", process.returncode)