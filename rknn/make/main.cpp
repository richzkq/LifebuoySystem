#include <iostream>
#include <thread>
#include <vector>
#include <cstring>   // for memcpy
#include <cstdlib>   // for malloc, free
#include <unistd.h>  // for usleep
#include <algorithm> // for remove_if

#include "camera.h"
#include "draw.h"
#include "model.h"
#include "tensor.h"
#include "system.h"
#include "uart_sender.h"
#include "help_call.h"
#include "config.h"

// 全局标志（可以从串口命令修改）
static bool Call_idle_enable = 0;
static bool Pres_idle_enable = 0;
static bool vision_servo_enable = true;
static uint32_t frame_counter = 0;

// 最近 N 次 Drowning 推理确认：5 次里至少 2 次识别到，才允许输出/报警/串口发送
static int g_drowning_history[DROWNING_CONFIRM_WINDOW] = {0};
static int g_drowning_history_pos = 0;
static int g_drowning_history_count = 0;
static int g_drowning_history_sum = 0;

static void update_drowning_confirm_history(bool positive)
{
    int v = positive ? 1 : 0;

    if (g_drowning_history_count < DROWNING_CONFIRM_WINDOW) {
        g_drowning_history[g_drowning_history_pos] = v;
        g_drowning_history_sum += v;
        g_drowning_history_pos = (g_drowning_history_pos + 1) % DROWNING_CONFIRM_WINDOW;
        g_drowning_history_count++;
        return;
    }

    g_drowning_history_sum -= g_drowning_history[g_drowning_history_pos];
    g_drowning_history[g_drowning_history_pos] = v;
    g_drowning_history_sum += v;
    g_drowning_history_pos = (g_drowning_history_pos + 1) % DROWNING_CONFIRM_WINDOW;
}

static int get_drowning_confirm_count()
{
    return g_drowning_history_sum;
}

static int get_thermal_infer_interval(float temp, const char **state, int *sleep_ms)
{
    *sleep_ms = 0;

    if (temp >= TEMP_CRITICAL) {
        *state = "CRITICAL";
        *sleep_ms = TEMP_CRITICAL_SLEEP_MS;
        return -1;
    }

    if (temp >= TEMP_HIGH) {
        *state = "HIGH";
        *sleep_ms = 120;
        return 5;
    }

    if (temp >= TEMP_MID) {
        *state = "MID";
        *sleep_ms = 80;
        return 3;
    }

    if (temp >= TEMP_LOW) {
        *state = "LOW";
        *sleep_ms = 40;
        return 2;
    }

    *state = "NORMAL";
    *sleep_ms = 0;
    return 1;
}

int main(int argc, char **argv)
{
    printf("========== dual_model_camera_int8_multi VERSION DROWNING-8OF4-UART-DEBUG-FIX ==========%s", "\n");

    if (argc < 3) {
        printf("用法:\n");
        printf("  %s ./best_int8_fixed.rknn ./yolov8m_int8_fixed.rknn\n", argv[0]);
        return -1;
    }

    const char *drowning_model_path = argv[1];
    const char *person_model_path = argv[2];

    printf("[MODEL PATH] Drowning: %s\n", drowning_model_path);
    printf("[MODEL PATH] Person:   %s\n", person_model_path);
    printf("[THRESH] Drowning=%.2f Person=%.2f NMS=%.2f\n",
           DROWNING_CONF_THRESH, PERSON_CONF_THRESH, NMS_THRESH);
    printf("[CONFIRM] Drowning recent %d model-runs >= %d positive runs will output/alarm\n",
           DROWNING_CONFIRM_WINDOW, DROWNING_CONFIRM_MIN);
    printf("[THERMAL] LOW=%d MID=%d HIGH=%d CRITICAL=%d, temp log every %d ms\n",
           TEMP_LOW, TEMP_MID, TEMP_HIGH, TEMP_CRITICAL, TEMP_LOG_INTERVAL_MS);
    printf("[UART STOP] no confirmed Drowning for %d ms -> send target_id=0 x=0 y=0 w=0 h=0, repeat every %d ms\n",
           DROWNING_STOP_TIMEOUT_MS, DROWNING_STOP_REPEAT_MS);

    // 初始化串口
    UartSender uart(UART_DEVICE, UART_BAUD);
    if (!uart.isReady()) {
        printf("警告：串口 %s 初始化失败，将继续运行但无法通信\n", UART_DEVICE);
    } else {
        printf("串口 %s 初始化成功，波特率 %d\n", UART_DEVICE, UART_BAUD);
    }

    gpio_init(TARGET_GPIO);
    help_call_init();

    if (camera_init() < 0) {
        return -1;
    }

    ModelCtx drowning_model;
    ModelCtx person_model;

    // Drowning 固定 NPU core 0
    if (init_model(drowning_model_path, "Drowning", &drowning_model, RKNN_NPU_CORE_0) < 0) {
        printf("初始化 Drowning 模型失败\n");
        return -1;
    }

    // Person 固定 NPU core 1
    if (init_model(person_model_path, "Person", &person_model, RKNN_NPU_CORE_1) < 0) {
        printf("初始化 Person 模型失败\n");
        return -1;
    }

    printf("[CHECK] Drowning num_classes=%d target_class=%d\n",
           drowning_model.num_classes, DROWNING_CLASS_ID);
    printf("[CHECK] Person num_classes=%d target_class=%d\n",
           person_model.num_classes, PERSON_CLASS_ID);

    unsigned char *frame_rgb = (unsigned char*)malloc(CAPTURE_WIDTH * CAPTURE_HEIGHT * 3);
    unsigned char *draw_rgb = (unsigned char*)malloc(CAPTURE_WIDTH * CAPTURE_HEIGHT * 3);
    unsigned char *input_rgb = (unsigned char*)malloc(MODEL_WIDTH * MODEL_HEIGHT * 3);

    if (!frame_rgb || !draw_rgb || !input_rgb) {
        printf("内存分配失败\n");
        return -1;
    }

    int frame_idx = 0;
    double fps_t0 = now_ms();
    int fps_count = 0;

    float cached_temp = get_temp();
    double last_temp_read_ms = now_ms();
    double last_temp_log_ms = 0.0;

    // 如果连续 DROWNING_STOP_TIMEOUT_MS 没有 confirmed Drowning，向 ttyS9 发送一次停止信号：target_id/x/y/w/h 全部为 0。
    double last_confirmed_drowning_ms = now_ms();
    bool uart_stop_sent_after_lost = false;
    double last_uart_stop_send_ms = 0.0;

    std::vector<Detection> cached_person_dets;

    while (1) {
        double t_loop0 = now_ms();

        // 按时间读温度，不再完全依赖帧号。室外降速后帧率变化也能及时保护。
        double now_for_temp = now_ms();
        if ((now_for_temp - last_temp_read_ms) >= TEMP_READ_INTERVAL_MS) {
            cached_temp = get_temp();
            last_temp_read_ms = now_for_temp;
        }

        float temp = cached_temp;
        const char *thermal_state = "NORMAL";
        int sleep_ms = 0;
        int infer_interval = get_thermal_infer_interval(temp, &thermal_state, &sleep_ms);

        // 超过临界温度：不取帧、不推理、不保存，优先降温，避免 80°C 重启。
        if (infer_interval < 0) {
            double now_t = now_ms();
            if ((now_t - last_temp_log_ms) >= TEMP_LOG_INTERVAL_MS) {
                printf("[TEMP] %.1f°C state=%s skip_model=yes sleep=%dms confirm=%d/%d\n",
                       temp, thermal_state, sleep_ms,
                       get_drowning_confirm_count(), DROWNING_CONFIRM_WINDOW);
                last_temp_log_ms = now_t;
            }

            // 过热保护期间模型暂停，如果超过 5 秒没有 confirmed Drowning，也要通知下位机停止视觉定位。
            if (uart.isReady() &&
                ((now_t - last_confirmed_drowning_ms) >= DROWNING_STOP_TIMEOUT_MS) &&
                ((last_uart_stop_send_ms <= 0.0) || ((now_t - last_uart_stop_send_ms) >= DROWNING_STOP_REPEAT_MS))) {
                bool ok_stop = uart.send_target(0, 0, 0, 0, 0);
                if (ok_stop) {
                    printf("[UART STOP SEND] target_id=0 x=0 y=0 w=0 h=0\n");
                } else {
                    printf("串口停止信号发送失败\n");
                }
                uart_stop_sent_after_lost = true;
                last_uart_stop_send_ms = now_t;
            }

            gpio_set(TARGET_GPIO, 0);
            usleep(sleep_ms * 1000);
            continue;
        }

        bool run_model_this_frame = ((frame_idx % infer_interval) == 0);
        int person_interval = PERSON_INTERVAL * infer_interval;
        if (person_interval < 1) person_interval = 1;
        bool run_person_this_frame = run_model_this_frame && ((frame_idx % person_interval) == 0);

        if (camera_get_rgb(frame_rgb) < 0) {
            usleep(30000);
            continue;
        }

        memcpy(draw_rgb, frame_rgb, CAPTURE_WIDTH * CAPTURE_HEIGHT * 3);

        LetterboxInfo lb;
        if (run_model_this_frame) {
            letterbox_rgb(frame_rgb, CAPTURE_WIDTH, CAPTURE_HEIGHT, input_rgb, MODEL_WIDTH, MODEL_HEIGHT, &lb);
        }

        std::vector<Detection> drowning_dets;
        std::vector<Detection> person_dets;

        double t_drown0 = now_ms();
        double t_drown1 = t_drown0;
        double t_person0 = now_ms();
        double t_person1 = t_person0;

        int ret_drowning = 0;
        int ret_person = 0;

        if (run_model_this_frame) {
            bool debug_drowning_post = false;

            if (run_person_this_frame) {
                cached_person_dets.clear();

                // person_run=yes 时，Drowning 与 Person 双线程并行推理。
                std::thread th_drowning([&]() {
                    double t0 = now_ms();
                    ret_drowning = run_yolov8_multi_output(
                        &drowning_model,
                        input_rgb,
                        lb,
                        CAPTURE_WIDTH,
                        CAPTURE_HEIGHT,
                        "Drowning",
                        DROWNING_CLASS_ID,
                        DROWNING_CONF_THRESH,
                        255, 0, 0,
                        &drowning_dets,
                        debug_drowning_post
                    );
                    double t1 = now_ms();
                    t_drown0 = t0;
                    t_drown1 = t1;
                });

                std::thread th_person([&]() {
                    double t0 = now_ms();
                    ret_person = run_yolov8_multi_output(
                        &person_model,
                        input_rgb,
                        lb,
                        CAPTURE_WIDTH,
                        CAPTURE_HEIGHT,
                        "Person out of water",
                        PERSON_CLASS_ID,
                        PERSON_CONF_THRESH,
                        0, 255, 0,
                        &cached_person_dets,
                        false
                    );
                    double t1 = now_ms();
                    t_person0 = t0;
                    t_person1 = t1;
                });

                th_drowning.join();
                th_person.join();
            } else {
                // person_run=cached 时，只跑 Drowning，Person 沿用上一帧缓存
                t_drown0 = now_ms();
                ret_drowning = run_yolov8_multi_output(
                    &drowning_model,
                    input_rgb,
                    lb,
                    CAPTURE_WIDTH,
                    CAPTURE_HEIGHT,
                    "Drowning",
                    DROWNING_CLASS_ID,
                    DROWNING_CONF_THRESH,
                    255, 0, 0,
                    &drowning_dets,
                    debug_drowning_post
                );
                t_drown1 = now_ms();

                t_person0 = now_ms();
                t_person1 = t_person0;
            }

            if (ret_drowning != 0) {
                printf("[Drowning] 推理失败 ret=%d\n", ret_drowning);
            }
            if (ret_person != 0) {
                printf("[Person] 推理失败 ret=%d\n", ret_person);
            }

            // 双保险：Drowning 只保留 class 0。
            drowning_dets.erase(
                std::remove_if(
                    drowning_dets.begin(),
                    drowning_dets.end(),
                    [](const Detection& d) {
                        return d.cls_id != DROWNING_CLASS_ID;
                    }
                ),
                drowning_dets.end()
            );

            update_drowning_confirm_history(!drowning_dets.empty());
        }

        person_dets = cached_person_dets;

        bool raw_has_drowning = !drowning_dets.empty();
        bool confirmed_drowning = raw_has_drowning && (get_drowning_confirm_count() >= DROWNING_CONFIRM_MIN);

        std::vector<Detection> confirmed_drowning_dets;
        if (confirmed_drowning) {
            confirmed_drowning_dets = drowning_dets;
        }

        // 串口停止机制：连续 5 秒没有 confirmed Drowning，就发送一次全 0 视觉定位数据。
        // 只发送一次；后面如果重新检测到 confirmed Drowning，会自动恢复并允许下次再次发送停止。
        double now_for_stop_check = now_ms();
        if (!confirmed_drowning_dets.empty()) {
            last_confirmed_drowning_ms = now_for_stop_check;
            uart_stop_sent_after_lost = false;
            last_uart_stop_send_ms = 0.0;
        } else if (uart.isReady() &&
                   ((now_for_stop_check - last_confirmed_drowning_ms) >= DROWNING_STOP_TIMEOUT_MS) &&
                   ((last_uart_stop_send_ms <= 0.0) || ((now_for_stop_check - last_uart_stop_send_ms) >= DROWNING_STOP_REPEAT_MS))) {
            bool ok_stop = uart.send_target(0, 0, 0, 0, 0);
            if (ok_stop) {
                printf("[UART STOP SEND] target_id=0 x=0 y=0 w=0 h=0\n");
            } else {
                printf("串口停止信号发送失败\n");
            }
            uart_stop_sent_after_lost = true;
            last_uart_stop_send_ms = now_for_stop_check;
        }

        // 如果 person 与 confirmed drowning 是同一个目标，去掉 person，保留 Drowning
        std::vector<Detection> filtered_person;
        for (const auto &p : person_dets) {
            bool remove_person = false;

            for (const auto &d : confirmed_drowning_dets) {
                if (person_should_be_removed_by_drowning(p, d)) {
                    remove_person = true;
                    break;
                }
            }

            if (!remove_person) {
                filtered_person.push_back(p);
            }
        }

        int heard_call = help_call_update();

        bool has_drowning = !confirmed_drowning_dets.empty();
        bool has_person = !filtered_person.empty();
        bool danger = has_drowning || has_person || (heard_call != 0);

        // GPIO 不再每帧写，减少 sysfs 文件访问；Drowning 必须通过 5帧2次确认才参与报警。
        if (frame_idx % GPIO_UPDATE_INTERVAL == 0) {
            gpio_set(TARGET_GPIO, danger ? 1 : 0);
        }

        // ==================== 串口发送目标坐标（仅 confirmed Drowning） ====================
        if (uart.isReady() && !confirmed_drowning_dets.empty()) {
            const Detection& target = confirmed_drowning_dets[0];
            int target_id = 1;
            int width  = target.right - target.left;
            int height = target.bottom - target.top;
            bool ok = uart.send_target(target_id,
                                       target.left,
                                       target.top,
                                       width,
                                       height);
            if (ok) {
                printf("[UART TARGET SEND] target_id=%d x=%d y=%d w=%d h=%d confirm=%d/%d\n",
                       target_id, target.left, target.top, width, height,
                       get_drowning_confirm_count(), DROWNING_CONFIRM_WINDOW);
            } else {
                printf("串口发送失败\n");
            }
        }

        // ==================== 串口接收命令 ====================
        if (uart.isReady() && uart.available() > 0) {
            std::string cmd;
            if (uart.receive_line(cmd, 10)) {
                // 去除末尾的 '\r' 和 '\n'
                while (!cmd.empty() && (cmd.back() == '\r' || cmd.back() == '\n')) {
                    cmd.pop_back();
                }
                if (cmd == "01") {
                    Call_idle_enable = 1;
                } else if (cmd == "02") {
                    Pres_idle_enable = 1;
                }
            }
        }

        int call_log_value = Call_idle_enable ? 1 : 0;
        int pres_log_value = Pres_idle_enable ? 1 : 0;
        Call_idle_enable = 0;
        Pres_idle_enable = 0;

        bool log_this_frame = confirmed_drowning && ((frame_idx % LOG_EVERY_N) == 0);

        // 日志：确认溺水前，只每 5 秒输出一次温度；确认后保持原来的详细输出格式。
        if (log_this_frame) {
            printf("\n================ 帧 %d ================\n", frame_idx);
            printf("温度: %.1f°C  sleep=%dms  person_run=%s\n",
                   temp,
                   sleep_ms,
                   run_person_this_frame ? "yes-parallel" : (run_model_this_frame ? "cached" : "skip-model"));

            printf("Drowning=%zu  Person out of water=%zu  HeardCall=%d\n",
                   confirmed_drowning_dets.size(),
                   filtered_person.size(),
                   heard_call);
            printf("CallforHelp = %d\n", call_log_value);
            printf("Pressure = %d\n", pres_log_value);
        } else {
            double now_t = now_ms();
            if ((now_t - last_temp_log_ms) >= TEMP_LOG_INTERVAL_MS) {
                printf("[TEMP] %.1f°C state=%s infer_every=%d person_every=%d sleep=%dms confirm=%d/%d model_run=%s\n",
                       temp,
                       thermal_state,
                       infer_interval,
                       person_interval,
                       sleep_ms,
                       get_drowning_confirm_count(),
                       DROWNING_CONFIRM_WINDOW,
                       run_model_this_frame ? "yes" : "no");
                last_temp_log_ms = now_t;
            }
        }

        int debug_print_count = 0;
        for (const auto &d : confirmed_drowning_dets) {
            if (log_this_frame && debug_print_count < MAX_DEBUG_DET_PRINT) {
                printf("[Drowning check] cls_id=%d score=%.4f box=(%d,%d,%d,%d)\n",
                       d.cls_id, d.score, d.left, d.top, d.right, d.bottom);
                print_detection_info(d);
                debug_print_count++;
            }

            draw_rect_rgb(draw_rgb, CAPTURE_WIDTH, CAPTURE_HEIGHT,
                          d.left, d.top, d.right, d.bottom,
                          d.r, d.g, d.b, 3);
            draw_label_rgb(draw_rgb, CAPTURE_WIDTH, CAPTURE_HEIGHT, d);
        }

        for (const auto &p : filtered_person) {
            if (log_this_frame) {
                print_detection_info(p);
            }

            draw_rect_rgb(draw_rgb, CAPTURE_WIDTH, CAPTURE_HEIGHT,
                          p.left, p.top, p.right, p.bottom,
                          p.r, p.g, p.b, 3);
            draw_label_rgb(draw_rgb, CAPTURE_WIDTH, CAPTURE_HEIGHT, p);
        }

        // 每 SAVE_EVERY_N 帧保存 JPG。没有确认 Drowning 时，不会画红框。
        double t_save0 = now_ms();
        if (frame_idx % SAVE_EVERY_N == 0) {
            save_rgb_jpg(draw_rgb, CAPTURE_WIDTH, CAPTURE_HEIGHT, frame_idx);
        }
        double t_save1 = now_ms();

        double t_loop1 = now_ms();

        fps_count++;
        if (log_this_frame && fps_count >= LOG_EVERY_N) {
            double now = now_ms();
            double fps = fps_count * 1000.0 / (now - fps_t0);

            double total_ms = t_loop1 - t_loop0;
            double drown_ms = t_drown1 - t_drown0;
            double person_ms = t_person1 - t_person0;
            double save_ms = t_save1 - t_save0;
            double infer_effective_ms = 0.0;
            if (run_model_this_frame) {
                infer_effective_ms = run_person_this_frame ? std::max(drown_ms, person_ms) : drown_ms;
            }
            double other_ms = total_ms - infer_effective_ms - save_ms;

            printf("[FPS] %.2f | frame=%d | Drowning=%zu | Person=%zu | person_run=%s | total=%.2fms | drown=%.2fms | person=%.2fms | save=%.2fms | other=%.2fms\n",
                   fps,
                   frame_idx,
                   confirmed_drowning_dets.size(),
                   filtered_person.size(),
                   run_person_this_frame ? "yes-parallel" : (run_model_this_frame ? "cached" : "skip-model"),
                   total_ms,
                   drown_ms,
                   person_ms,
                   save_ms,
                   other_ms);

            fps_t0 = now;
            fps_count = 0;
        } else if (!log_this_frame && fps_count >= LOG_EVERY_N) {
            // 不输出 FPS，但重置统计，避免下次确认时 FPS 跨很长时间失真。
            fps_t0 = now_ms();
            fps_count = 0;
        }

        frame_idx++;
        frame_counter++;

        if (sleep_ms > 0) {
            usleep(sleep_ms * 1000);
        }
    }

    free(frame_rgb);
    free(draw_rgb);
    free(input_rgb);

    release_model(&drowning_model);
    release_model(&person_model);
    camera_release();
    help_call_release();

    return 0;
}
