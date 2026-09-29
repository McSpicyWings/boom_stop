### 使用方法

1. 方法1：利用example/ptrace目录中提供工具，直接执行需要采样的程序，不需要修改代码
   - ptrace目录中是采用了ptrace的设计，将要采样的程序作为子进程，采样的设置和处理均有父进程完成，因此不需要修改源代码
   - 使用方法：提供采样控制的文件名和可执行程序的信息即可
  
2. 方法2：example/self_handle/binary
   - 该方法是将采样所有需要的内容编译成.o文件，由要采样的程序在链接时添加进去即可
   - 通过利用c语言中__attribute((constructor))声明，使得采样设置函数initStart能够在main开始之前被执行。该函数将完成对采样参数的设置
   - 使用方法：将.o文件链接到源程序，执行时在执行目录提供采样控制参数的文件samplectrl.txt

3. 方法3：example/self_handle/include_files
   - 该方法直接提供头文件，其中包含了所有设置采样控制的接口，同时提供了基本框架用于完成采样函数的设置
   - 使用方法：将头文件包含到源文件中，在需要的位置调用initStart函数，同时自行设置采样参数和采样函数中的处理

## 64 项事件映射（compact64-v2）

使用本版 Scala 重新生成 bitstream 后，打印 E0–E63；E33 为 `commit_jump_src_decode_ras`，E35 为 `commit_jump_src_core`。不再输出这两个槽原先的 LOOP 诊断事件。完整映射见 `log_process/read_counter/eventlist`，统计口径见 `docs/commit-prediction-sources.md`。

旧 bitstream/旧日志的 E33/E35 含义不同，分析时必须指定对应历史映射。例如：

```bash
cd /root/project/boom_stop/log_process/read_counter
python3 readlog.py /path/to/new-result.log eventlist
# 分析旧日志时第二个参数改为相应 analysis 目录的 eventlist.snapshot
```
