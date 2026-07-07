#include "draw.h"
#include "system.h"    // for clamp_int, ensure_dir, clip_u8
#include <cstring>
#include <cstdio>
#include <opencv2/opencv.hpp>
#include <cstdio>

// YUYV -> RGB + 翻转镜像
void yuyv_to_rgb(unsigned char* yuyv, unsigned char* rgb, int width, int height)
{
    int i, j;

    for (i = 0, j = 0; i < width * height * 2; i += 4, j += 6)
    {
        int y0 = yuyv[i + 0];
        int u  = yuyv[i + 1];
        int y1 = yuyv[i + 2];
        int v  = yuyv[i + 3];

        int c0 = y0 - 16;
        int c1 = y1 - 16;
        int d = u - 128;
        int e = v - 128;

        int r = (298 * c0 + 409 * e + 128) >> 8;
        int g = (298 * c0 - 100 * d - 208 * e + 128) >> 8;
        int b = (298 * c0 + 516 * d + 128) >> 8;

        rgb[j + 0] = clip_u8(r);
        rgb[j + 1] = clip_u8(g);
        rgb[j + 2] = clip_u8(b);

        r = (298 * c1 + 409 * e + 128) >> 8;
        g = (298 * c1 - 100 * d - 208 * e + 128) >> 8;
        b = (298 * c1 + 516 * d + 128) >> 8;

        rgb[j + 3] = clip_u8(r);
        rgb[j + 4] = clip_u8(g);
        rgb[j + 5] = clip_u8(b);
    }

    int stride = width * 3;
    unsigned char* temp_line = (unsigned char*)malloc(stride);
    unsigned char temp_pixel[3];

    if (!temp_line) return;

    // 上下翻转
    for (int y = 0; y < height / 2; y++) {
        int top = y * stride;
        int bot = (height - 1 - y) * stride;
        memcpy(temp_line, &rgb[top], stride);
        memcpy(&rgb[top], &rgb[bot], stride);
        memcpy(&rgb[bot], temp_line, stride);
    }

    // 左右镜像
    for (int y = 0; y < height; y++) {
        int row = y * stride;
        for (int x = 0; x < width / 2; x++) {
            int left = x * 3;
            int right = (width - 1 - x) * 3;
            memcpy(temp_pixel, &rgb[row + left], 3);
            memcpy(&rgb[row + left], &rgb[row + right], 3);
            memcpy(&rgb[row + right], temp_pixel, 3);
        }
    }

    free(temp_line);
}

void draw_rect_rgb(
    unsigned char *rgb,
    int w,
    int h,
    int left,
    int top,
    int right,
    int bottom,
    unsigned char r,
    unsigned char g,
    unsigned char b,
    int thickness
)
{
    left = clamp_int(left, 0, w - 1);
    right = clamp_int(right, 0, w - 1);
    top = clamp_int(top, 0, h - 1);
    bottom = clamp_int(bottom, 0, h - 1);

    if (right <= left || bottom <= top) return;

    for (int t = 0; t < thickness; t++) {
        int y1 = top + t;
        int y2 = bottom - t;

        if (y1 >= 0 && y1 < h) {
            for (int x = left; x <= right; x++) {
                unsigned char *p = rgb + (y1 * w + x) * 3;
                p[0] = r;
                p[1] = g;
                p[2] = b;
            }
        }

        if (y2 >= 0 && y2 < h) {
            for (int x = left; x <= right; x++) {
                unsigned char *p = rgb + (y2 * w + x) * 3;
                p[0] = r;
                p[1] = g;
                p[2] = b;
            }
        }

        int x1 = left + t;
        int x2 = right - t;

        if (x1 >= 0 && x1 < w) {
            for (int y = top; y <= bottom; y++) {
                unsigned char *p = rgb + (y * w + x1) * 3;
                p[0] = r;
                p[1] = g;
                p[2] = b;
            }
        }

        if (x2 >= 0 && x2 < w) {
            for (int y = top; y <= bottom; y++) {
                unsigned char *p = rgb + (y * w + x2) * 3;
                p[0] = r;
                p[1] = g;
                p[2] = b;
            }
        }
    }
}

void draw_label_rgb(
    unsigned char *rgb,
    int w,
    int h,
    const Detection &d
)
{
    cv::Mat img(h, w, CV_8UC3, rgb);

    int font = cv::FONT_HERSHEY_SIMPLEX;
    double font_scale = 0.55;
    int thickness = 1;
    int baseline = 0;

    cv::Size text_size = cv::getTextSize(d.label, font, font_scale, thickness, &baseline);

    int x1 = clamp_int(d.left, 0, w - 1);
    int y_text = d.top - 6;

    int box_x1 = x1;
    int box_y1 = y_text - text_size.height - baseline - 4;
    int box_x2 = x1 + text_size.width + 8;
    int box_y2 = y_text + baseline;

    // 如果框上方空间不够，就把标签放到框内部上沿
    if (box_y1 < 0) {
        box_y1 = clamp_int(d.top, 0, h - 1);
        box_y2 = clamp_int(d.top + text_size.height + baseline + 8, 0, h - 1);
        y_text = box_y1 + text_size.height + 3;
    }

    box_x2 = clamp_int(box_x2, 0, w - 1);
    box_y1 = clamp_int(box_y1, 0, h - 1);
    box_y2 = clamp_int(box_y2, 0, h - 1);

    // 当前 Mat 内存是 RGB，所以 Scalar 的 3 个值按 R,G,B 写入。
    cv::Scalar bg_color(d.r, d.g, d.b);
    cv::Scalar text_color(255, 255, 255);

    cv::rectangle(
        img,
        cv::Point(box_x1, box_y1),
        cv::Point(box_x2, box_y2),
        bg_color,
        cv::FILLED
    );

    cv::putText(
        img,
        d.label,
        cv::Point(box_x1 + 4, y_text),
        font,
        font_scale,
        text_color,
        thickness,
        cv::LINE_AA
    );
}

int save_rgb_jpg(const uint8_t *rgb, int w, int h, int idx)
{
    ensure_dir(SAVE_DIR);

    char path[256];
    snprintf(path, sizeof(path), "%s/frame_%06d.jpg", SAVE_DIR, idx + 1);

    cv::Mat rgb_mat(h, w, CV_8UC3, (void*)rgb);
    cv::Mat bgr_mat;
    cv::cvtColor(rgb_mat, bgr_mat, cv::COLOR_RGB2BGR);

    std::vector<unsigned char> jpg;
    std::vector<int> params;
    params.push_back(cv::IMWRITE_JPEG_QUALITY);
    params.push_back(75);

    if (!cv::imencode(".jpg", bgr_mat, jpg, params)) {
        printf("imencode 失败\n");
        return -1;
    }

    FILE *fp = fopen(path, "wb");
    if (!fp) {
        printf("保存失败: %s\n", path);
        return -1;
    }

    fwrite(jpg.data(), 1, jpg.size(), fp);
    fclose(fp);

    return 0;
}

void print_detection_info(const Detection &d)
{
    int bw = d.right - d.left;
    int bh = d.bottom - d.top;
    int cx = d.left + bw / 2;
    int cy = d.top + bh / 2;

    printf("%s: conf=%.3f center=(%d,%d) size=%dx%d box=(%d,%d,%d,%d)\n",
           d.label.c_str(),
           d.score,
           cx,
           cy,
           bw,
           bh,
           d.left,
           d.top,
           d.right,
           d.bottom);
}
