#include "system.h"
#include <chrono>
#include <cstring>
#include <cstdio>     // for snprintf, printf
#include <cstdlib>    // for atoi
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>

double now_ms()
{
    using namespace std::chrono;
    return duration<double, std::milli>(
        steady_clock::now().time_since_epoch()
    ).count();
}

int clamp_int(int v, int lo, int hi)
{
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
}

void ensure_dir(const char *dir)
{
    struct stat st;
    if (stat(dir, &st) != 0) {
        mkdir(dir, 0755);
    }
}

unsigned char clip_u8(int v)
{
    if (v < 0) return 0;
    if (v > 255) return 255;
    return (unsigned char)v;
}

int safe_write_fd(int fd, const char *data, size_t len)
{
    ssize_t ret = write(fd, data, len);
    if (ret < 0) return -1;
    return 0;
}

float get_temp(void)
{
    int fd = open("/sys/class/thermal/thermal_zone0/temp", O_RDONLY);
    if (fd < 0) return 55.0f;

    char buf[32] = {0};
    int r = read(fd, buf, sizeof(buf) - 1);
    close(fd);

    if (r <= 0) return 55.0f;
    return atoi(buf) / 1000.0f;
}

int get_interval(float t)
{
    if (t >= TEMP_HIGH) return 100;
    if (t >= TEMP_MID)  return 66;
    if (t >= TEMP_LOW)  return 50;

    return 0;
}

int gpio_init(int gpio_num)
{
    char path[128];
    int fd;

    snprintf(path, sizeof(path), "/sys/class/gpio/gpio%d", gpio_num);
    if (access(path, F_OK) != 0) {
        fd = open("/sys/class/gpio/export", O_WRONLY);
        if (fd >= 0) {
            char b[16];
            snprintf(b, sizeof(b), "%d", gpio_num);
            safe_write_fd(fd, b, strlen(b));
            close(fd);
            usleep(100000);
        }
    }

    snprintf(path, sizeof(path), "/sys/class/gpio/gpio%d/direction", gpio_num);
    fd = open(path, O_WRONLY);
    if (fd >= 0) {
        safe_write_fd(fd, "out", 3);
        close(fd);
        return 0;
    }

    return -1;
}

int gpio_set(int gpio_num, int value)
{
    char p[128];
    snprintf(p, sizeof(p), "/sys/class/gpio/gpio%d/value", gpio_num);

    int fd = open(p, O_WRONLY);
    if (fd >= 0) {
        safe_write_fd(fd, value ? "1" : "0", 1);
        close(fd);
        return 0;
    }

    return -1;
}

