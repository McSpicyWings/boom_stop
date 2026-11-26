# F3 提前更新 FAuBTB

实验分支从 `wpc_dev` 的 `84b5c08` 派生。第一版实现 JAL 静态目标提前填充，以及 BR 静态目标和后级预测方向提前更新。BIM、主 BTB、TAGE、Loop、FTQ 和 E0–E63 事件映射沿用基线。[设计稿](https://app.notion.com/p/pcpcw/4235f8022ba780ca9e48c64229473653)

## 构建对照组

在现有 FPGA Config 左侧叠加表中的 BOOM mixin，板级参数沿用同一个基准。B 与 JTD 共享有效位、偏移检查和更新时选路。原始 `wpc_dev` 可用于单独量化这部分公共结构的影响。

| 组 | JAL 目标 | BR 目标 | BR 方向 | 选择方式 |
| --- | --- | --- | --- | --- |
| R | 关 | 关 | 关 | 使用原始 `wpc_dev` |
| B | 关 | 关 | 关 | 使用本分支和原有板级 Config |
| J | 开 | 关 | 关 | `new boom.common.WithFAuBTBEarlyUpdate(jal = true, brTarget = false, brDir = false)` |
| JT | 开 | 开 | 关 | `new boom.common.WithFAuBTBEarlyUpdate(jal = true, brTarget = true, brDir = false)` |
| JTD | 开 | 开 | 开 | `new boom.common.WithFAuBTBEarlyJTD` |

`WithFAuBTBEarlyJTD` 需要当前预测器组合包含 FAuBTB。板上 B 和 JTD 对同一 checkpoint 使用相同预热及提交指令窗口，沿用现有 64 计数器读取程序，以 E0 和 E1 计算 IPC。各组记录实际时钟频率。

## 更新语义

- F4 接收 F3 取指包时，抽取有效路径前缀内的直接 JAL 和 BR。静态目标来自预译码，BR 方向来自同一取指包的最终 BPD 响应。BR 预测 NT 时也能填充其 taken 目标。
- 更新按取指包 PC 映射物理 bank，寄存一级。同 bank 的真实提交优先，碰撞时丢弃这拍的提前更新。下一拍写入，随后 F1 自然可见。
- 提交和提前更新均按当前 tag 选路。offset 使用 bank 内 slot PC，适用于拼接到下一 bank slot 0 的 32 位指令。提前更新跳过越界或奇地址目标。真实 taken 提交若目标越界，则将该槽目标标为无效。
- 真实提交训练 2-bit 计数器并清理该槽方向覆盖。外部冲刷取消待写并清除方向覆盖，已写静态目标保留。JALR 和返回沿用普通提交写入。

## 本机验证

使用 Chipyard 1.13.0、双 bank `LargeBoomV3Config` 与 Verilator 5.030 编译 B 和 JTD。两组均通过 BEQ、JAL、JALR、RVC ISA 回归和定向裸机负载。另在独立 RTL 测试中检查同包多槽、bank 1 起始、cache line 尾端、两拍后可见、提交优先、冲刷、方向回退、过期提交元数据与偏移边界。整核 ELF 含跨 bank 的 32 位 JAL 和超出 13 位偏移范围的远 JAL。

| 组 | 窗口核周期 | 退休指令 | 校验值 | IPC |
| --- | ---: | ---: | ---: | ---: |
| B | 27,822 | 15,502 | 4,615 | 0.557185 |
| JTD | 27,819 | 15,502 | 4,615 | 0.557245 |

这一轮的数值来自裸机定向负载。JTD 在该窗口少用 3 个核周期。SPEC 切片和 Vivado 布线后的频率尚待板上测量。[B 日志](../analysis/1126-faubtb-early-update/B-workload.log) · [JTD 日志](../analysis/1126-faubtb-early-update/JTD-workload.log) · [定向 RTL 日志](../analysis/1126-faubtb-early-update/unit.log)

本机复现脚本保存在 `analysis/1126-faubtb-early-update/`。构建脚本把分支修改应用到本地 Chipyard v3 基线的临时副本，保留原 `generators/boom` 链接以及两套源码各自的事件接口。可用 `FAUBTB_V3_BASELINE` 指向另一份同版本、未引入 Ferret 改动的 v3 源码。

```bash
results=/root/project/boom_stop/analysis/1126-faubtb-early-update
bash "$results/build.sh"
bash "$results/unit.sh" > "$results/unit.log" 2>&1
bash "$results/run.sh"
```
