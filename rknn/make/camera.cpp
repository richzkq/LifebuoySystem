#include "camera.h"
#include "draw.h"      // for yuyv_to_rgb
#include <cstring>
#include <unistd.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <linux/videodev2.h>
#include <sys/ioctl.h>
#include <cstdio> 

int cam_fd = -1;
void *cam_buf = NULL;
size_t cam_buf_len = 0;

int camera_init(void)
{
    cam_fd = open(CAMERA_DEVICE, O_RDWR | O_NONBLOCK);
    if (cam_fd < 0) {
        printf("打开摄像头失败 %s\n", CAMERA_DEVICE);
        return -1;
    }

    struct v4l2_format fmt;
    memset(&fmt, 0, sizeof(fmt));

    fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    fmt.fmt.pix.width = CAPTURE_WIDTH;
    fmt.fmt.pix.height = CAPTURE_HEIGHT;
    fmt.fmt.pix.pixelformat = V4L2_PIX_FMT_YUYV;
    fmt.fmt.pix.field = V4L2_FIELD_ANY;

    if (ioctl(cam_fd, VIDIOC_S_FMT, &fmt) < 0) {
        perror("设置格式失败");
        close(cam_fd);
        cam_fd = -1;
        return -1;
    }

    struct v4l2_requestbuffers req;
    memset(&req, 0, sizeof(req));

    req.count = 1;
    req.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    req.memory = V4L2_MEMORY_MMAP;

    if (ioctl(cam_fd, VIDIOC_REQBUFS, &req) < 0) {
        perror("请求缓冲失败");
        close(cam_fd);
        cam_fd = -1;
        return -1;
    }

    struct v4l2_buffer buf;
    memset(&buf, 0, sizeof(buf));

    buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    buf.memory = V4L2_MEMORY_MMAP;
    buf.index = 0;

    if (ioctl(cam_fd, VIDIOC_QUERYBUF, &buf) < 0) {
        perror("查询缓冲失败");
        close(cam_fd);
        cam_fd = -1;
        return -1;
    }

    cam_buf_len = buf.length;
    cam_buf = mmap(NULL, buf.length, PROT_READ, MAP_SHARED, cam_fd, buf.m.offset);

    if (cam_buf == MAP_FAILED) {
        perror("mmap 失败");
        close(cam_fd);
        cam_fd = -1;
        return -1;
    }

    if (ioctl(cam_fd, VIDIOC_QBUF, &buf) < 0) {
        perror("QBUF 失败");
        return -1;
    }

    enum v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (ioctl(cam_fd, VIDIOC_STREAMON, &type) < 0) {
        perror("STREAMON 失败");
        return -1;
    }

    printf("摄像头初始化成功：%s %dx%d YUYV\n", CAMERA_DEVICE, CAPTURE_WIDTH, CAPTURE_HEIGHT);
    return 0;
}

int camera_wait_frame(int timeout_ms)
{
    fd_set fds;
    struct timeval tv;

    FD_ZERO(&fds);
    FD_SET(cam_fd, &fds);

    tv.tv_sec = timeout_ms / 1000;
    tv.tv_usec = (timeout_ms % 1000) * 1000;

    return select(cam_fd + 1, &fds, NULL, NULL, &tv);
}

int camera_get_rgb(unsigned char *rgb)
{
    if (camera_wait_frame(1000) <= 0) {
        printf("等待摄像头超时\n");
        return -1;
    }

    struct v4l2_buffer buf;
    memset(&buf, 0, sizeof(buf));

    buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    buf.memory = V4L2_MEMORY_MMAP;
    buf.index = 0;

    if (ioctl(cam_fd, VIDIOC_DQBUF, &buf) < 0) {
        perror("取帧失败");
        return -1;
    }

    yuyv_to_rgb((unsigned char*)cam_buf, rgb, CAPTURE_WIDTH, CAPTURE_HEIGHT);

    if (ioctl(cam_fd, VIDIOC_QBUF, &buf) < 0) {
        perror("QBUF 失败");
        return -1;
    }

    return 0;
}

void camera_release()
{
    if (cam_fd >= 0) {
        enum v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        ioctl(cam_fd, VIDIOC_STREAMOFF, &type);
    }

    if (cam_buf && cam_buf != MAP_FAILED) {
        munmap(cam_buf, cam_buf_len);
        cam_buf = NULL;
    }

    if (cam_fd >= 0) {
        close(cam_fd);
        cam_fd = -1;
    }
}