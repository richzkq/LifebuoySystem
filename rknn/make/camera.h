#pragma once
#include <cstdint>
#include "config.h"

int camera_init(void);
int camera_get_rgb(uint8_t* rgb_buf);
int camera_wait_frame(int timeout_ms);
void camera_release();