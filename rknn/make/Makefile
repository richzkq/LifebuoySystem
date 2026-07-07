# 编译器与标志
CXX       = g++
CXXFLAGS  = -std=c++17 -O3 -pthread
LDFLAGS   = -pthread

# OpenCV 配置（使用 pkg-config）
OPENCV_CFLAGS = $(shell pkg-config --cflags opencv4)
OPENCV_LIBS   = $(shell pkg-config --libs opencv4)

# RKNN 配置（库在标准路径，头文件可能需要额外指定）
RKNN_INCLUDE = -I/usr/include
RKNN_LIB     = -L/usr/lib
RKNN_LIBS    = -lrknnrt

# 合并编译标志
CXXFLAGS += $(OPENCV_CFLAGS) $(RKNN_INCLUDE)
LDFLAGS  += $(OPENCV_LIBS) $(RKNN_LIB) $(RKNN_LIBS)

# 源文件列表（注意 .cc 和 .cpp 都要包含）
SRCS = main.cpp camera.cpp model.cpp tensor.cpp draw.cpp system.cpp help_call.cpp uart_sender.cc

# 如果将来需要集成队友的模块，取消下面的注释并添加相应 .cpp
# SRCS += call_detector.cpp motor_control.cpp audio_utils.cpp

# 生成的目标文件名
TARGET = rknn_vision

# 自动生成 .o 文件列表（支持 .cpp 和 .cc）
OBJS = $(SRCS:.cpp=.o)
OBJS := $(OBJS:.cc=.o)

# 默认目标
all: $(TARGET)

# 链接所有目标文件
$(TARGET): $(OBJS)
	$(CXX) -o $@ $^ $(LDFLAGS)

# 编译规则（自动推导）
%.o: %.cpp
	$(CXX) $(CXXFLAGS) -c $< -o $@

%.o: %.cc
	$(CXX) $(CXXFLAGS) -c $< -o $@

# 清理
clean:
	rm -f $(OBJS) $(TARGET)

# 伪目标
.PHONY: all clean