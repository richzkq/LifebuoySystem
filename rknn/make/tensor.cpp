#include "tensor.h"
#include "system.h"      // 如果需要 clamp_int 等（但本文件未用）
#include <cmath>
#include <algorithm>

bool make_tensor_view(
    const rknn_tensor_attr &attr,
    void *buf,
    int expected_c,
    TensorView *view
)
{
    if (attr.n_dims != 4) return false;

    int d1 = attr.dims[1];
    int d2 = attr.dims[2];
    int d3 = attr.dims[3];

    view->buf = buf;
    view->attr = attr;

    // NCHW: [1,C,H,W]
    if ((d1 == expected_c || expected_c <= 0) && d2 == d3) {
        view->nchw = true;
        view->c = d1;
        view->h = d2;
        view->w = d3;
        return true;
    }

    // NHWC: [1,H,W,C]
    if (d3 == expected_c && d1 == d2) {
        view->nchw = false;
        view->c = d3;
        view->h = d1;
        view->w = d2;
        return true;
    }

    // 兜底：优先按 NCHW
    view->nchw = true;
    view->c = d1;
    view->h = d2;
    view->w = d3;
    return true;
}

size_t tensor_offset(const TensorView &v, int c, int y, int x)
{
    if (v.nchw) {
        return ((size_t)c * v.h + y) * v.w + x;
    } else {
        return ((size_t)y * v.w + x) * v.c + c;
    }
}

float tensor_get(const TensorView &v, int c, int y, int x)
{
    size_t off = tensor_offset(v, c, y, x);

    if (v.attr.type == RKNN_TENSOR_INT8) {
        int q = ((int8_t*)v.buf)[off];
        return ((float)q - (float)v.attr.zp) * v.attr.scale;
    }

    if (v.attr.type == RKNN_TENSOR_UINT8) {
        int q = ((uint8_t*)v.buf)[off];
        return ((float)q - (float)v.attr.zp) * v.attr.scale;
    }

    if (v.attr.type == RKNN_TENSOR_FLOAT32) {
        return ((float*)v.buf)[off];
    }

    return 0.0f;
}

float dfl_decode(const TensorView &box, int base_ch, int y, int x)
{
    float logits[REG_MAX];
    float max_v = -1e30f;

    for (int i = 0; i < REG_MAX; i++) {
        float v = tensor_get(box, base_ch + i, y, x);
        logits[i] = v;
        if (v > max_v) max_v = v;
    }

    float sum = 0.0f;
    float weighted = 0.0f;

    for (int i = 0; i < REG_MAX; i++) {
        float e = expf(logits[i] - max_v);
        sum += e;
        weighted += e * i;
    }

    if (sum <= 0.0f) return 0.0f;
    return weighted / sum;
}

float box_iou(const Detection &a, const Detection &b)
{
    int x1 = std::max(a.left, b.left);
    int y1 = std::max(a.top, b.top);
    int x2 = std::min(a.right, b.right);
    int y2 = std::min(a.bottom, b.bottom);

    int iw = std::max(0, x2 - x1);
    int ih = std::max(0, y2 - y1);
    int inter = iw * ih;

    int area_a = std::max(0, a.right - a.left) * std::max(0, a.bottom - a.top);
    int area_b = std::max(0, b.right - b.left) * std::max(0, b.bottom - b.top);

    int uni = area_a + area_b - inter;
    if (uni <= 0) return 0.0f;

    return (float)inter / (float)uni;
}

// person 自身有多少比例被 drowning 覆盖
float box_inter_over_a(const Detection &a, const Detection &b)
{
    int x1 = std::max(a.left, b.left);
    int y1 = std::max(a.top, b.top);
    int x2 = std::min(a.right, b.right);
    int y2 = std::min(a.bottom, b.bottom);

    int iw = std::max(0, x2 - x1);
    int ih = std::max(0, y2 - y1);
    int inter = iw * ih;

    int area_a = std::max(1, a.right - a.left) * std::max(1, a.bottom - a.top);
    return (float)inter / (float)area_a;
}

bool center_inside_box(const Detection &small, const Detection &big)
{
    int cx = (small.left + small.right) / 2;
    int cy = (small.top + small.bottom) / 2;

    return cx >= big.left && cx <= big.right && cy >= big.top && cy <= big.bottom;
}

// 判断 person 是否应该被 drowning 压掉
bool person_should_be_removed_by_drowning(const Detection &person, const Detection &drowning)
{
    float iou = box_iou(person, drowning);
    float person_cover = box_inter_over_a(person, drowning);

    // 原来的 IoU 0.45 太严格，红框大、绿框小时 IoU 可能不高
    if (iou >= 0.20f) return true;

    // person 大部分区域在 drowning 框里面
    if (person_cover >= 0.35f) return true;

    // person 中心点在 drowning 框内，也认为是同一目标
    if (center_inside_box(person, drowning)) return true;

    return false;
}

void nms(std::vector<Detection> &dets, float iou_thresh)
{
    std::sort(dets.begin(), dets.end(), [](const Detection &a, const Detection &b) {
        return a.score > b.score;
    });

    std::vector<Detection> keep;
    std::vector<int> removed(dets.size(), 0);

    for (size_t i = 0; i < dets.size(); i++) {
        if (removed[i]) continue;

        keep.push_back(dets[i]);

        for (size_t j = i + 1; j < dets.size(); j++) {
            if (removed[j]) continue;
            if (box_iou(dets[i], dets[j]) >= iou_thresh) {
                removed[j] = 1;
            }
        }
    }

    dets.swap(keep);
}
