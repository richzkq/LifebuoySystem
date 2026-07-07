// detection.h
#pragma once
#include <string>

struct Detection {
    int left, top, right, bottom;
    float score;
    int cls_id;
    std::string label;
    unsigned char r, g, b;
};