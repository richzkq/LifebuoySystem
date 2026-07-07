#pragma once

int help_call_init();
int help_call_update(); // 返回 1 表示听到呼救，返回 0 表示没有
void help_call_release();