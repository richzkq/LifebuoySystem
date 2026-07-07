#pragma once
#include <cstdint>
#include "model.h"
#include "config.h"

void yuyv_to_rgb(unsigned char* yuyv, unsigned char* rgb, int width, int height);
void draw_rect_rgb(uint8_t* rgb, int w, int h, int l, int t, int r, int b,
                   uint8_t red, uint8_t green, uint8_t blue, int thickness);
void draw_label_rgb(uint8_t* rgb, int w, int h, const Detection& d);
int save_rgb_jpg(const uint8_t* rgb, int w, int h, int idx);
void print_detection_info(const Detection &d);