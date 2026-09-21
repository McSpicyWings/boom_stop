#!/usr/bin/env bash
set -euo pipefail
export PATH=/root/chipyard/install/rv64g/bin:/root/chipyard/install/all/bin:$PATH
export RISCV=/root/chipyard/install/rv64g
cd /root/project/boom_stop/example/ptrace
riscv64-unknown-linux-gnu-g++ perf.cpp trace.cpp util.cpp -static -o perf.riscv
cd /root/project/boom_stop/ckpt_support/restoreCkpt/readckpt
riscv64-unknown-linux-gnu-g++ takeover_syscall.cpp elf_load.cpp ckpt_load.cpp readckpt.cpp -static -O2 -o readckpt.riscv
cd /root/project/chipyard-boom/chipyard-1.13.0/sims/verilator
make CONFIG=MediumBoomV3Config -j8 > /tmp/commit-pred-build.log 2>&1
cd /root/project/riscv_new_event_counter/riscv_test
riscv64-unknown-linux-gnu-gcc -I./env -I./common -DPREALLOCATE=1 -mcmodel=medany -std=gnu99 -O2 -fno-if-conversion -fno-if-conversion2 -fno-common -fno-builtin-printf -fno-tree-loop-distribute-patterns -o /tmp/commit-pred-test.riscv /root/project/boom_stop/test/riscv_test/commit_prediction_sources_v3.c ./common/syscalls.c ./common/crt.S -static -nostdlib -nostartfiles -lm -lgcc -T ./common/test.ld
cd /root/project/chipyard-boom/chipyard-1.13.0/sims/verilator
./simulator-chipyard.harness-MediumBoomV3Config +permissive +loadmem=/tmp/commit-pred-test.riscv +max-cycles=2000000 +permissive-off /tmp/commit-pred-test.riscv > /tmp/commit-pred-test.log 2>&1
./simulator-chipyard.harness-MediumBoomV3Config +permissive +loadmem="$RISCV/riscv64-unknown-elf/share/riscv-tests/isa/rv64ui-p-jalr" +max-cycles=100000 +permissive-off "$RISCV/riscv64-unknown-elf/share/riscv-tests/isa/rv64ui-p-jalr" > /tmp/commit-pred-isa.log 2>&1
cat /tmp/commit-pred-test.log
