#include "model.h"
#include "tensor.h"      // 因为使用了 TensorView, dfl_decode, nms 等
#include "system.h"      // for clamp_int
#include <cstdio>
#include <cstring>
#include <cmath>
#include <cstdlib>
#include <algorithm>

unsigned char* load_file(const char *filename, int *file_size)
{
    FILE *fp = fopen(filename, "rb");
    if (!fp) {
        printf("打开模型失败: %s\n", filename);
        return NULL;
    }

    fseek(fp, 0, SEEK_END);
    int size = ftell(fp);
    fseek(fp, 0, SEEK_SET);

    unsigned char *data = (unsigned char*)malloc(size);
    if (!data) {
        fclose(fp);
        return NULL;
    }

    if ((int)fread(data, 1, size, fp) != size) {
        fclose(fp);
        free(data);
        return NULL;
    }

    fclose(fp);
    *file_size = size;
    return data;
}

void print_tensor_attr(const char *prefix, const rknn_tensor_attr &attr)
{
    printf("%s index=%d name=%s n_dims=%d dims=[", prefix, attr.index, attr.name, attr.n_dims);
    for (int i = 0; i < attr.n_dims; i++) {
        printf("%d", attr.dims[i]);
        if (i != attr.n_dims - 1) printf(",");
    }

    printf("] type=%d fmt=%d qnt_type=%d zp=%d scale=%.10f size=%d\n",
           attr.type,
           attr.fmt,
           attr.qnt_type,
           attr.zp,
           attr.scale,
           attr.size);
}

static void print_one_key_output(const std::string &model_name,
                                 const char *desc,
                                 int index,
                                 const std::vector<rknn_tensor_attr> &attrs)
{
    if (index < 0 || index >= (int)attrs.size()) {
        printf("[%s] %s output%d 不存在\n", model_name.c_str(), desc, index);
        return;
    }

    const rknn_tensor_attr &a = attrs[index];

    printf("[%s] 重点检查 %s output%d: dims=[", model_name.c_str(), desc, index);
    for (int i = 0; i < a.n_dims; i++) {
        printf("%d", a.dims[i]);
        if (i != a.n_dims - 1) printf(",");
    }
    printf("] type=%d qnt_type=%d zp=%d scale=%.10f size=%d\n",
           a.type,
           a.qnt_type,
           a.zp,
           a.scale,
           a.size);
}

static void print_yolov8_multi_output_debug(const std::string &model_name,
                                            const std::vector<rknn_tensor_attr> &attrs)
{
    printf("\n========== %s 9输出重点排查 ==========\n", model_name.c_str());
    printf("说明：output1/output4/output7 是类别分支，重点对比新旧模型的 type/qnt_type/zp/scale。\n");
    printf("如果阈值提高后红框不变，优先看这三个输出是否量化饱和或反量化异常。\n");

    print_one_key_output(model_name, "80x80 class", 1, attrs);
    print_one_key_output(model_name, "40x40 class", 4, attrs);
    print_one_key_output(model_name, "20x20 class", 7, attrs);

    printf("同时也记录 box 分支 output0/output3/output6，方便排除框解码分支异常。\n");
    print_one_key_output(model_name, "80x80 box", 0, attrs);
    print_one_key_output(model_name, "40x40 box", 3, attrs);
    print_one_key_output(model_name, "20x20 box", 6, attrs);

    printf("======================================\n\n");
}

int init_model(const char *model_path, const std::string &name, ModelCtx *m, rknn_core_mask core_mask)
{
    int model_size = 0;
    unsigned char *model_data = load_file(model_path, &model_size);
    if (!model_data) {
        printf("[%s] 读取模型失败\n", name.c_str());
        return -1;
    }

    int ret = rknn_init(&m->ctx, model_data, model_size, 0, NULL);
    free(model_data);

    if (ret != RKNN_SUCC) {
        printf("[%s] rknn_init 失败 ret=%d\n", name.c_str(), ret);
        return -1;
    }

    int core_ret = rknn_set_core_mask(m->ctx, core_mask);
    if (core_ret != RKNN_SUCC) {
        printf("[%s] 警告：rknn_set_core_mask 失败 ret=%d，继续使用默认核心\n",
               name.c_str(), core_ret);
    } else {
        printf("[%s] 已设置 NPU core mask=%d\n", name.c_str(), core_mask);
    }

    m->name = name;
    m->output_num = 0;
    m->num_classes = 0;

    rknn_input_output_num io_num;
    memset(&io_num, 0, sizeof(io_num));

    ret = rknn_query(m->ctx, RKNN_QUERY_IN_OUT_NUM, &io_num, sizeof(io_num));
    if (ret != RKNN_SUCC) {
        printf("[%s] 查询 input/output num 失败 ret=%d\n", name.c_str(), ret);
        return -1;
    }

    printf("\n========== 模型 %s ==========\n", name.c_str());
    printf("model_path=%s\n", model_path);
    printf("input_num=%d output_num=%d\n", io_num.n_input, io_num.n_output);

    m->output_num = io_num.n_output;

    m->input_attrs.resize(io_num.n_input);
    for (int i = 0; i < io_num.n_input; i++) {
        memset(&m->input_attrs[i], 0, sizeof(rknn_tensor_attr));
        m->input_attrs[i].index = i;
        ret = rknn_query(m->ctx, RKNN_QUERY_INPUT_ATTR, &m->input_attrs[i], sizeof(rknn_tensor_attr));
        if (ret != RKNN_SUCC) {
            printf("[%s] 查询 input%d 属性失败 ret=%d\n", name.c_str(), i, ret);
            return -1;
        }
        print_tensor_attr("input", m->input_attrs[i]);
    }

    m->output_attrs.resize(io_num.n_output);
    for (int i = 0; i < io_num.n_output; i++) {
        memset(&m->output_attrs[i], 0, sizeof(rknn_tensor_attr));
        m->output_attrs[i].index = i;
        ret = rknn_query(m->ctx, RKNN_QUERY_OUTPUT_ATTR, &m->output_attrs[i], sizeof(rknn_tensor_attr));
        if (ret != RKNN_SUCC) {
            printf("[%s] 查询 output%d 属性失败 ret=%d\n", name.c_str(), i, ret);
            return -1;
        }
        print_tensor_attr("output", m->output_attrs[i]);
    }

    if (io_num.n_output != 9) {
        printf("[%s] 警告：当前输出不是 9 个，多输出后处理可能不适配。\n", name.c_str());
    }

    print_yolov8_multi_output_debug(name, m->output_attrs);

    if (io_num.n_output >= 8) {
        const rknn_tensor_attr &a = m->output_attrs[7];

        if (a.n_dims == 4) {
            int d1 = a.dims[1];
            int d2 = a.dims[2];
            int d3 = a.dims[3];

            if (d2 == d3 && d1 > 1) {
                m->num_classes = d1;
            } else if (d1 == d2 && d3 > 1) {
                m->num_classes = d3;
            } else {
                m->num_classes = d1;
            }
        }
    }

    printf("[%s] 推断类别数 num_classes=%d\n", name.c_str(), m->num_classes);
    return 0;
}

void release_model(ModelCtx *m)
{
    if (m->ctx) {
        rknn_destroy(m->ctx);
        m->ctx = 0;
    }
}

void letterbox_rgb(
    const unsigned char *src,
    int src_w,
    int src_h,
    unsigned char *dst,
    int dst_w,
    int dst_h,
    LetterboxInfo *info
)
{
    memset(dst, 114, dst_w * dst_h * 3);

    float scale_w = (float)dst_w / (float)src_w;
    float scale_h = (float)dst_h / (float)src_h;
    float scale = std::min(scale_w, scale_h);

    int new_w = (int)round(src_w * scale);
    int new_h = (int)round(src_h * scale);

    int pad_x = (dst_w - new_w) / 2;
    int pad_y = (dst_h - new_h) / 2;

    if (new_w <= 0) new_w = 1;
    if (new_h <= 0) new_h = 1;

    for (int y = 0; y < new_h; y++) {
        int sy = (int)(y / scale);
        if (sy < 0) sy = 0;
        if (sy >= src_h) sy = src_h - 1;

        for (int x = 0; x < new_w; x++) {
            int sx = (int)(x / scale);
            if (sx < 0) sx = 0;
            if (sx >= src_w) sx = src_w - 1;

            const unsigned char *p_src = src + (sy * src_w + sx) * 3;
            unsigned char *p_dst = dst + ((y + pad_y) * dst_w + (x + pad_x)) * 3;

            p_dst[0] = p_src[0];
            p_dst[1] = p_src[1];
            p_dst[2] = p_src[2];
        }
    }

    info->scale = scale;
    info->pad_x = pad_x;
    info->pad_y = pad_y;
}

int run_yolov8_multi_output(
    ModelCtx *m,
    const unsigned char *input_nhwc_rgb,
    const LetterboxInfo &lb,
    int orig_w,
    int orig_h,
    const std::string &label,
    int target_class,
    float conf_thresh,
    unsigned char r,
    unsigned char g,
    unsigned char b,
    std::vector<Detection> *out_dets,
    bool debug_enable
)
{
    (void)debug_enable;

    rknn_input input;
    memset(&input, 0, sizeof(input));

    input.index = 0;
    input.type = RKNN_TENSOR_UINT8;
    input.fmt = RKNN_TENSOR_NHWC;
    input.size = MODEL_WIDTH * MODEL_HEIGHT * 3;
    input.buf = (void*)input_nhwc_rgb;
    input.pass_through = 0;

    int ret = rknn_inputs_set(m->ctx, 1, &input);
    if (ret != RKNN_SUCC) {
        printf("[%s] rknn_inputs_set 失败 ret=%d\n", m->name.c_str(), ret);
        return -1;
    }

    ret = rknn_run(m->ctx, NULL);
    if (ret != RKNN_SUCC) {
        printf("[%s] rknn_run 失败 ret=%d\n", m->name.c_str(), ret);
        return -1;
    }

    std::vector<rknn_output> outputs(m->output_num);
    for (int i = 0; i < m->output_num; i++) {
        memset(&outputs[i], 0, sizeof(rknn_output));
        outputs[i].index = i;
        outputs[i].want_float = 0;   // 保持 INT8 输出，手动按 scale/zp 反量化
        outputs[i].is_prealloc = 0;
    }

    ret = rknn_outputs_get(m->ctx, m->output_num, outputs.data(), NULL);
    if (ret != RKNN_SUCC) {
        printf("[%s] rknn_outputs_get 失败 ret=%d\n", m->name.c_str(), ret);
        return -1;
    }

    std::vector<Detection> dets;

    const int strides[3] = {8, 16, 32};
    const int box_idx[3] = {0, 3, 6};
    const int cls_idx[3] = {1, 4, 7};

    for (int s = 0; s < 3; s++) {
        TensorView box_view;
        TensorView cls_view;

        make_tensor_view(m->output_attrs[box_idx[s]], outputs[box_idx[s]].buf, BOX_CHANNELS, &box_view);
        make_tensor_view(m->output_attrs[cls_idx[s]], outputs[cls_idx[s]].buf, m->num_classes, &cls_view);

        int h = box_view.h;
        int w = box_view.w;
        int stride = strides[s];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float best_score = -1.0f;
                int best_cls = -1;

                for (int c = 0; c < m->num_classes; c++) {
                    float score = tensor_get(cls_view, c, y, x);
                    if (score > best_score) {
                        best_score = score;
                        best_cls = c;
                    }
                }

                if (best_cls != target_class) continue;
                if (best_score < conf_thresh) continue;

                float l = dfl_decode(box_view, 0 * REG_MAX, y, x);
                float t = dfl_decode(box_view, 1 * REG_MAX, y, x);
                float rr = dfl_decode(box_view, 2 * REG_MAX, y, x);
                float bb = dfl_decode(box_view, 3 * REG_MAX, y, x);

                float cx = ((float)x + 0.5f) * stride;
                float cy = ((float)y + 0.5f) * stride;

                float x1 = cx - l * stride;
                float y1 = cy - t * stride;
                float x2 = cx + rr * stride;
                float y2 = cy + bb * stride;

                x1 = (x1 - lb.pad_x) / lb.scale;
                y1 = (y1 - lb.pad_y) / lb.scale;
                x2 = (x2 - lb.pad_x) / lb.scale;
                y2 = (y2 - lb.pad_y) / lb.scale;

                Detection det;
                det.left = clamp_int((int)roundf(x1), 0, orig_w - 1);
                det.top = clamp_int((int)roundf(y1), 0, orig_h - 1);
                det.right = clamp_int((int)roundf(x2), 0, orig_w - 1);
                det.bottom = clamp_int((int)roundf(y2), 0, orig_h - 1);
                det.score = best_score;
                det.cls_id = best_cls;
                det.label = label;
                det.r = r;
                det.g = g;
                det.b = b;

                if (det.right - det.left >= 2 && det.bottom - det.top >= 2) {
                    dets.push_back(det);
                }
            }
        }
    }

    rknn_outputs_release(m->ctx, m->output_num, outputs.data());

    nms(dets, NMS_THRESH);

    for (const auto &d : dets) {
        out_dets->push_back(d);
    }

    return 0;
}
