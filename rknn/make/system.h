#pragma once
#include "config.h"
#include <cstddef>

double now_ms();
int clamp_int(int v, int lo, int hi);
void ensure_dir(const char *dir);
unsigned char clip_u8(int v);
int safe_write_fd(int fd, const char *data, size_t len);
float get_temp();
int get_interval(float temp);
int gpio_init(int gpio);
int gpio_set(int gpio, int value);