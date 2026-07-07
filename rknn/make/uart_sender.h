#ifndef _UART_SENDER_H
#define UART_SENDER_H

#include <string>
#include <cstdint>
#include <vector>

class UartSender {
public:
    UartSender(const std::string& device, int baud);
    ~UartSender();

    bool isReady() const;
    bool send_target(int target_id, int x, int y, int w, int h);

    bool receive_line(std::string& line, int timeout_ms = 100);
    bool receive_bytes(uint8_t* buf, size_t len, int timeout_ms = 100);
    int available();

private:
    int fd;   // 串口文件描述符
    bool ready; //初始化状态标志
};

#endif