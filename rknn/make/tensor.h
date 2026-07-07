#pragma once
#include "rknn_api.h"
#include "detection.h" 
#include <vector>  

struct TensorView {
    void *buf = NULL;
    rknn_tensor_attr attr;
    int c = 0;
    int h = 0;
    int w = 0;
    bool nchw = true;
};

bool make_tensor_view(
    const rknn_tensor_attr &attr,
    void *buf,
    int expected_c,
    TensorView *view
);

size_t tensor_offset(const TensorView &v, int c, int y, int x);
float tensor_get(const TensorView &v, int c, int y, int x);
float dfl_decode(const TensorView &box, int base_ch, int y, int x);
float box_iou(const Detection &a, const Detection &b);
float box_inter_over_a(const Detection &a, const Detection &b);
bool center_inside_box(const Detection &small, const Detection &big);
bool person_should_be_removed_by_drowning(const Detection &person, const Detection &drowning);
void nms(std::vector<Detection> &dets, float iou_thresh);