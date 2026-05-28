import os
import time
from flask import Flask, Response

FRAME_DIR = "/home/elf/demo/make/frames_int8_pixel_box"

app = Flask(__name__)

def get_latest_jpg():
    """取目录里修改时间最新的 jpg 路径"""
    try:
        files = [
            os.path.join(FRAME_DIR, f)
            for f in os.listdir(FRAME_DIR)
            if f.endswith(".jpg")
        ]
        if not files:
            return None
        return max(files, key=os.path.getmtime)
    except Exception:
        return None

def mjpeg_stream():
    last_path = None
    last_mtime = 0
    last_bytes = None
    while True:
        path = get_latest_jpg()
        if path:
            try:
                mtime = os.path.getmtime(path)
                # 只在图片变化时才重新读盘,减少 IO
                if path != last_path or mtime != last_mtime:
                    with open(path, "rb") as f:
                        last_bytes = f.read()
                    last_path = path
                    last_mtime = mtime
            except Exception:
                pass

        if last_bytes:
            yield (
                b"--frame\r\n"
                b"Content-Type: image/jpeg\r\n\r\n"
                + last_bytes
                + b"\r\n"
            )
        # 控制推流帧率,约 10fps,可调
        time.sleep(0.1)

@app.route("/stream")
def stream():
    return Response(
        mjpeg_stream(),
        mimetype="multipart/x-mixed-replace; boundary=frame"
    )

@app.route("/snapshot")
def snapshot():
    """单张最新图,给报警抓拍用"""
    path = get_latest_jpg()
    if not path:
        return "no image", 404
    with open(path, "rb") as f:
        return Response(f.read(), mimetype="image/jpeg")

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8081, threaded=True)