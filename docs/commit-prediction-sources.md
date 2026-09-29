# 提交时的逐指令预测来源计数

## 统计口径

每条实际提交的指令按它自身的下一 PC 预测归属分到一个来源。它不是“哪次重定向把这条指令取进来”：否则 core 重定向后的普通指令也会被误算为 core 贡献。原有 event2–5 的 F0 NPC 来源口径保持不变。

| 来源编号 | 名称 | 归属条件 |
| --- | --- | --- |
| 0 | seq | 无可归属预测器的顺序预测；普通指令也在此列，压缩指令为 PC+2，其余 PC+4 |
| 1 | f1 | F1 命中且识别为 CFI，作为初始来源，包括预测不跳转的命中 |
| 2 | f2 | F2 相对对齐后的 F1 改变有效跳转决定或已跳转的目标 |
| 3 | f3 | F3 相对对齐后的 F2 改变上述决定，默认组合为 TAGE/LOOP |
| 4 | decode_ras | F3 指令解码/直接目标计算或 RAS 再次修正预测 |
| 5 | core | 该指令执行解析发现误预测，随后实际提交 |

后一级预测与前一级一致时保留前一级归属。因此这是“初始来源 + 最后有效修正”的互斥归属，不是最终读取了哪个表、各级命中率或独立准确率。有效跳转要求预测目标有效且为 JUMP 或预测 taken 的 BR；双方都不跳转时忽略目标值。无效目标下的方向信息不能单独取得 NPC 归属。如果 TAGE 改变方向但最终由解码补齐目标，最终来源可能是 decode_ras。

非 CFI 指令统一归 seq；BR 为 `is_br`；JUMP 为 `is_jal || is_jalr`，包含直接跳转、间接跳转和返回。不要把 JUMP 总数等同于旧 JALR 总数。F3 TAGE/LOOP 合并统计，当前不再区分两者。以上阶段名称对应当前启用 BPD 的默认组合。

## 数据通路与计数

- `BranchPrediction.pred_src` 在 composer 中逐 slot、逐级对齐传播，通过预测响应队列与取指包关联。
- `FetchBundle.pred_src` 在 F3 结合解码/RAS 得到最终前端来源，fetch-buffer 将其拷入 MicroOp。
- `MicroOp.pred_src_snapshot` 保留前端来源；`pred_src` 初值相同。ROB 收到该指令的误预测解析时只将后者改为 core；同时处理解析与提交同周期的旁路。
- core 只对 ROB 的 `commit.arch_valids` 做 PopCount，每周期可增加多条，排除被冲刷、回滚和未提交指令。新事件与 event1 同为提交计数延迟一周期。
- ROB 断言来源范围有效，且最终来源只能等于快照或 core。

uop 快照并未另增“原始来源 × 是否误预测”直方图。如果以后需要各级准确率、TAGE/LOOP 各自救对/改错数，需要增加这种交叉计数；本次来源份额不能直接解释为移除该预测器后的 IPC 损失。

## 事件编号

| 来源 | boom_stop ALL | BR | JUMP | Chipyard v3 ALL | BR | JUMP |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| seq | 48 | 54 | 60 | 9 | 15 | 21 |
| f1 | 49 | 55 | 61 | 10 | 16 | 22 |
| f2 | 50 | 56 | 62 | 11 | 17 | 23 |
| f3 | 51 | 57 | 63 | 12 | 18 | 24 |
| decode_ras | 52 | 58 | **33** | 13 | 19 | **25** |
| core | 53 | 59 | **35** | 14 | 20 | **26** |

### 64 项版本（compact64-v2）

实际 FPGA 读取程序仅输出 E0–E63，故把原 E64/E65 移到 E33/E35，分别替换 `loop_override_misp` 与 `loop_conf_degraded`。原 E33 并非精确 LOOP 错误计数；原 E35 仅是更新诊断。保留 E32 覆盖候选、E34 p_cnt 写路径及所有 TAGE 事件。除上述两槽和移除 E64/E65 外，compact 编号不变。预测算法、uop 快照、ROB 提交门控均不变；新 E33/E35 直接来自对应 JUMP 提交 PopCount，不用 ALL−BR 推导。

两棵 Scala 树均将 subECounterNum 设为 4（64 项），源码对提交事件索引增加 `<64` 检查。v3 原有布局不同，提交来源改用 E9–26，复用旧解码/派发/缓存事件，原 E27–61 保留，E62–63 置零。v3 不使用 FPGA compact eventlist；其 JUMP decode/core 为 E25/E26。Chipyard generators/boom 仍链接到 `/root/project/riscv-boom-code-new`，由该树实际编译测试。

`perf.riscv`、`readckpt.riscv` 使用 64 项缓冲、四组 ReadCounter16、打印 0–63；FPGA 保留 ADDI 协议，v3 测试保留 ANDI 协议。应重新生成 bitstream 并使用本版读取程序和 eventlist。读取程序在事件前输出 `counter_map=compact64-v2 slots=64` 以标识软件版本；它不是硬件版本探测。

旧日志不能使用新 eventlist：0913/0929 分析脚本已固定读取各自 eventlist.snapshot，不改写历史快照。通用 readlog.py 可指定映射路径：`python3 readlog.py logfile /path/to/eventlist.snapshot`；省略时使用当前目录 eventlist。0929 缺失两项的既有报告仍是条件推导，重新烧录采样后才有实测值。

readlog.py 输出全部新事件，并增加每组内的六个 `_share`，分母为本组六项之和，分母为零输出 nan。必须使用新 bitstream 配合新读取程序。跨事件比较应冻结同一计数窗口；读取过程中继续计数会导致不一致。

## 当前版本复现

复用本机已有 Chipyard、Verilator、RISC-V 编译器与裸机运行库：

```bash
bash /root/project/boom_stop/test/riscv_test/run_commit_prediction_sources_v3.sh
```

该脚本重编译两个读取程序、生成 MediumBoomV3Config simulator、编译/运行 3000 轮混合 BR/JAL/JALR 的计数测试，再运行 rv64ui-p-jalr。日志为 `/tmp/commit-pred-{build,test,isa}.log`。FPGA 工程的旧 BOOM API 与 Chipyard v3 不同；本次实际编译运行的是同步修改后的 v3，未执行 Vivado 综合或板上实验。

## 初版验证结果（旧映射，历史记录）

Scala 编译、硬件展开、Verilator 生成及 C++ 链接退出码均为 0；混合测试打印 PASS，rv64ui-p-jalr 退出码为 0，ROB 断言未触发。

| 来源 | ALL | BR | JUMP |
| --- | ---: | ---: | ---: |
| seq | 111002 | 0 | 0 |
| f1 | 15295 | 5169 | 10126 |
| f2 | 1 | 0 | 1 |
| f3 | 785 | 785 | 0 |
| decode_ras | 959 | 1 | 958 |
| core | 5437 | 3045 | 2392 |
| 合计 | 133479 | 9000 | 13477 |

ALL 合计等于 event1；BR 合计等于 v3 event28；JUMP 大于仅 JALR 的 event29=9000；各来源 BR+JUMP 不超过 ALL；core_ALL=core_BR+core_JUMP。各大类来源均有运行覆盖，但部分类型与来源组合为零，测试并未穷举每种时序和预测修正。此压力程序的数据用于验证计数，不能代表 SPEC 性能。

软件检查：两个读取程序交叉编译通过；解析器用 66 项合成输入检查全部 18 个 share，等量输入为 1/6、全零输入为 nan；git diff --check 通过。

## 64 项版验证

运行上述脚本，验证 ALL 来源和=E1、BR 来源和=旧 BR 计数、JUMP 包含 JAL、core 与 decode/RAS 均满足 ALL=BR+JUMP，且 JUMP decode/RAS 和 core 两项均非零。结果见构建和运行日志。

本版实测：`make CONFIG=MediumBoomV3Config -j8` 及完整复现脚本退出 0；混合程序输出 `PASS: commit source histogram invariants`，提交指令 133479、BR 9000、JUMP 13477。v3 E25（JUMP decode/RAS）直接读出 **916**，E26（JUMP core）直接读出 **2386**；core_ALL=5401=3015+2386，decode_ALL=916=0+916。rv64ui-p-jalr 退出 0。两个 FPGA 读取程序重新编译通过；64 项映射与含版本标识的解析器端到端检查通过。实际编译运行的是 v3；未执行本版 Vivado 综合或板上测试。
