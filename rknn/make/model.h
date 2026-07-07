#pragma once
#include <vector>
#include <string>
#include "rknn_api.h"
#include "config.h"
#include "detection.h"

struct LetterboxInfo {
    float scale;
    int pad_x, pad_y;
};

struct ModelCtx {
    rknn_context ctx = 0;
    std::string name;
    int input_w = MODEL_WIDTH;
    int input_h = MODEL_HEIGHT;
    int output_num = 0;
    int num_classes = 0;
    std::vector<rknn_tensor_attr> input_attrs;
    std::vector<rknn_tensor_attr> output_attrs;
};

unsigned char* load_file(const char *filename, int *file_size);
void print_tensor_attr(const char *prefix, const rknn_tensor_attr &attr);
int init_model(const char* path, const std::string& name, ModelCtx* m, rknn_core_mask core);
void release_model(ModelCtx* m);
void letterbox_rgb(const uint8_t* src, int sw, int sh, uint8_t* dst, int dw, int dh, LetterboxInfo* info);

int run_yolov8_multi_output(
    ModelCtx* m,
    const uint8_t* input,
    const LetterboxInfo& lb,
    int orig_w,
    int orig_h,
    const std::string& label,
    int target_class,
    float conf_thresh,
    uint8_t r,
    uint8_t g,
    uint8_t b,
    std::vector<Detection>* out,
    bool debug_post = false
);
