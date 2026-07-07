#include "uart_sender.h"
#include <cstring>
#include <stdexcept>
#include <fcntl.h>
#include <unistd.h>
#include <termios.h>
#include <iostream>
#include <vector>
#include <sys/select.h>
#include <sys/time.h>
#include <sys/ioctl.h>   // 新增：提供 ioctl 和 FIONREAD
#include <chrono>        // 新增：提供 std::chrono
#include "config.h"  // UART_DEVICE, UART_BAUD

// 构造函数
UartSender::UartSender(const std::string& device, int baud) : fd(-1), ready(false)
 {
    fd = open(device.c_str(), O_RDWR | O_NOCTTY | O_NDELAY);
    if (fd == -1)
    {
        std::cerr << "错误: 无法打开串口 " << device << ": " << strerror(errno) << std::endl;
        return; // 不抛异常，让 ready 保持 false
    }

    fcntl(fd, F_SETFL, 0);  // 阻塞模式

    struct termios options;
    tcgetattr(fd, &options);

    cfsetispeed(&options, B115200);
    cfsetospeed(&options, B115200);

    options.c_cflag |= (CLOCAL | CREAD);
    options.c_cflag &= ~PARENB;
    options.c_cflag &= ~CSTOPB;
    options.c_cflag &= ~CSIZE;
    options.c_cflag |= CS8;
    options.c_cflag &= ~CRTSCTS;

    options.c_lflag &= ~(ICANON | ECHO | ECHOE | ISIG);
    options.c_oflag &= ~OPOST;

    tcsetattr(fd, TCSANOW, &options);

    ready = true;
    std::cout << "串口 " << device << " 初始化成功。" << std::endl;
}

// 析构函数：关闭串口
UartSender::~UartSender()
{
    if (fd != -1) {
        close(fd);
        fd = -1;
    }
}

bool UartSender::isReady() const
{
    return ready;
}


// 静态校验和函数
static uint8_t calculate_checksum(const std::vector<uint8_t>& data) {
    uint8_t sum = 0;
    for (auto byte : data) sum += byte;
    return sum;
}

bool UartSender::send_target(int target_id, int x, int y, int w, int h) {
    if (!ready) {
        std::cerr << "串口未就绪，无法发送。" << std::endl;
        return false;
    }
    std::vector<uint8_t> payload;
    payload.reserve(10);
    payload.push_back(0x09); // 长度
    payload.push_back(static_cast<uint8_t>(target_id));
    // 小端模式
    payload.push_back(x & 0xFF);
    payload.push_back((x >> 8) & 0xFF);
    payload.push_back(y & 0xFF);
    payload.push_back((y >> 8) & 0xFF);
    payload.push_back(w & 0xFF);
    payload.push_back((w >> 8) & 0xFF);
    payload.push_back(h & 0xFF);
    payload.push_back((h >> 8) & 0xFF);
    payload.push_back(calculate_checksum(payload));

    std::vector<uint8_t> frame;
    frame.reserve(payload.size() + 2);
    frame.push_back(0xAA);
    frame.push_back(0x55);
    frame.insert(frame.end(), payload.begin(), payload.end());

    ssize_t written = write(fd, frame.data(), frame.size());
    if (written != static_cast<ssize_t>(frame.size())) {
        std::cerr << "UART发送错误: 期望写入 " << frame.size() 
                  << " 字节, 实际写入 " << written << std::endl;
        return false;
    }

    // 等待数据真正从串口发送出去，避免程序高频循环时下位机漏收。
    tcdrain(fd);
    return true;
}

// 检查可读字节数
int UartSender::available()
{
    if (!ready) return 0;
    int bytes = 0;
    if (ioctl(fd, FIONREAD, &bytes) == -1) return 0;
    return bytes;
}

// 带超时的读取指定长度数据
bool UartSender::receive_bytes(uint8_t* buf, size_t len, int timeout_ms)
{
    if (!ready) return false;
    fd_set fds;
    struct timeval tv;
    int remaining = len;
    uint8_t* ptr = buf;

    while (remaining > 0) {
        FD_ZERO(&fds);
        FD_SET(fd, &fds);
        tv.tv_sec = timeout_ms / 1000;
        tv.tv_usec = (timeout_ms % 1000) * 1000;
        
        int ret = select(fd + 1, &fds, NULL, NULL, &tv);
        if (ret <= 0) return false; // 超时或错误
        
        ssize_t n = read(fd, ptr, remaining);
        if (n <= 0) return false;
        remaining -= n;
        ptr += n;
    }
    return true;
}

// 接收一行字符串（以 '\n' 结尾）
bool UartSender::receive_line(std::string& line, int timeout_ms)
{
    if (!ready) return false;
    line.clear();
    char ch;
    auto start = std::chrono::steady_clock::now();
    while (true) {
        if (available() > 0) {
            if (read(fd, &ch, 1) != 1) return false;
            if (ch == '\n') break;
            line.push_back(ch);
        }
        auto now = std::chrono::steady_clock::now();
        if (std::chrono::duration_cast<std::chrono::milliseconds>(now - start).count() > timeout_ms)
            return false;
        usleep(1000);
    }
    return true;
}