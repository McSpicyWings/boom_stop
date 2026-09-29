# TAGE allocation counters

Count one successful allocation at commit update, classified by the selected
`allocate.bits` table index. A prediction-time candidate, failed allocation,
repair update, or ordinary provider update does not increment these counters.
The gate is identical to `tage_alloc_success` in `tage.scala`.

| Table | Default history length | boom_stop event | Chipyard BOOM v3 event |
| --- | --- | --- | --- |
| 0 | 2 | 36 | 50 |
| 1 | 4 | 37 | 51 |
| 2 | 8 | 38 | 52 |
| 3 | 16 | 39 | 53 |
| 4 | 32 | 40 | 54 |
| 5 | 64 | 41 | 55 |

Names are `tage_alloc_t0` through `tage_alloc_t5`. History lengths are configured
by `tableInfo`; the counters refer to table indices, even if lengths change.
Within one common measurement interval, their sum equals `tage_alloc_success`.
Counter reads are sequential, not an atomic snapshot: allocations during the
read sequence can make software totals differ.

`perf.riscv` and `readckpt.riscv` now print compact events 0–63, including
the later commit-source extension. Their 64-entry buffers and four
`ReadCounter16` calls cover this range (see `commit-prediction-sources.md`). `readlog.py`
uses event names for derived metrics and exports the six counts and their
fractions of successful allocations; a zero denominator produces `nan`.

The Chipyard 1.13.0 checkout uses `boom.v3` packages, while `boom_scala` uses
older BOOM APIs. The same counter logic is implemented in both trees; the
existing v3 event map is preserved. Do not decode v3 simulator logs with the
compact `eventlist` without translating the event IDs. The generator symlink
continues to target `/root/project/riscv-boom-code-new`.

## Allocation failures classified by provider

Count only a valid commit update with a valid CFI index, final misprediction,
and no valid allocation candidate (`tage_alloc_fail`). Classify using the
provider saved in that branch's prediction metadata. The six events are
mutually exclusive; a successful allocation never increments them.

| Provider | Event name | boom_stop | BOOM v3 |
| --- | --- | --- | --- |
| None | tage_alloc_fail_no_provider | 42 | 56 |
| t0 | tage_alloc_fail_provider_t0 | 43 | 57 |
| t1 | tage_alloc_fail_provider_t1 | 44 | 58 |
| t2 | tage_alloc_fail_provider_t2 | 45 | 59 |
| t3 | tage_alloc_fail_provider_t3 | 46 | 60 |
| t4 (second longest) | tage_alloc_fail_provider_t4 | 47 | 61 |

Provider t5 (longest history) is excluded because no longer table exists.
The global `tage_alloc_fail` counter retains its existing definition including
t5 failures. Thus the six-counter sum equals global failures minus failures
with provider t5, for the same measurement interval. It is not necessarily
equal to the global failure count. Missing providers ignore the invalid
provider index bits.

The previous per-table blocked mask is removed; default and TAGE-L metadata
capacities return from 160 to 120 bits. No extra prediction metadata is needed.
The existing allocation/update policy is preserved: on failure with a valid
provider, the implementation clears longer-history entries' `u` to zero;
with no provider its current `decr_mask` is zero (no `u` write). The no-provider
counter records the failed allocation regardless of this existing behavior.

The parser exports each category's `_per_fail` fraction over global failures,
including the unreported t5 category in the denominator. Zero denominators
yield `nan`. Old per-table-failure logs have different semantics and must not
be decoded using this revised event list.

## Rebuild and verify

```bash
export PATH=/root/chipyard/install/rv64g/bin:/root/chipyard/install/all/bin:$PATH
export RISCV=/root/chipyard/install/rv64g
cd /root/project/boom_stop/example/ptrace
riscv64-unknown-linux-gnu-g++ perf.cpp trace.cpp util.cpp -static -o perf.riscv
cd /root/project/boom_stop/ckpt_support/restoreCkpt/readckpt
riscv64-unknown-linux-gnu-g++ takeover_syscall.cpp elf_load.cpp ckpt_load.cpp readckpt.cpp -static -O2 -o readckpt.riscv
cd /root/project/chipyard-boom/chipyard-1.13.0/sims/verilator
make CONFIG=MediumBoomV3Config -j8 > /tmp/tage-provider-fail-build.log 2>&1
cd /root/project/riscv_new_event_counter/riscv_test
riscv64-unknown-linux-gnu-gcc -I./env -I./common -DPREALLOCATE=1 -mcmodel=medany -std=gnu99 -O2 -ffast-math -fno-common -fno-builtin-printf -fno-tree-loop-distribute-patterns -o /tmp/tage-provider-fail-test.riscv ./test.c ./common/syscalls.c ./common/crt.S -static -nostdlib -nostartfiles -lm -lgcc -T ./common/test.ld
cd /root/project/chipyard-boom/chipyard-1.13.0/sims/verilator
./simulator-chipyard.harness-MediumBoomV3Config +permissive +loadmem=/tmp/tage-provider-fail-test.riscv +max-cycles=2000000 +permissive-off /tmp/tage-provider-fail-test.riscv > /tmp/tage-provider-fail-baseline.log 2>&1
./simulator-chipyard.harness-MediumBoomV3Config +permissive +max-cycles=1000000 +permissive-off "$RISCV/riscv64-unknown-elf/share/riscv-tests/isa/rv64ui-p-simple"
cd /root/project/riscv_new_event_counter/riscv_test
riscv64-unknown-linux-gnu-gcc -I. -I./env -I./common -DPREALLOCATE=1 -mcmodel=medany -std=gnu99 -O2 -fno-if-conversion -fno-if-conversion2 -ffast-math -fno-common -fno-builtin-printf -fno-tree-loop-distribute-patterns -o /tmp/tage-provider-fail-stress.riscv /root/project/boom_stop/test/riscv_test/tage_alloc_fail_v3.c ./common/syscalls.c ./common/crt.S -static -nostdlib -nostartfiles -lm -lgcc -T ./common/test.ld
cd /root/project/chipyard-boom/chipyard-1.13.0/sims/verilator
./simulator-chipyard.harness-MediumBoomV3Config +permissive +loadmem=/tmp/tage-provider-fail-stress.riscv +max-cycles=2000000 +permissive-off /tmp/tage-provider-fail-stress.riscv > /tmp/tage-provider-fail-stress.log 2>&1
```

## Validation

- Scala compilation, hardware elaboration, Verilator generation and C++ link:
  `make CONFIG=MediumBoomV3Config -j8` exited 0.
- `rv64ui-p-simple`: exited 0.
- Baseline test: exited 0; attempts 10 = successes 6 + global failures 4;
  per-table successes `[1,1,1,1,1,1]`, provider failure bins all zero.
- Stress test: exited 0 and printed `PASS: allocation counter invariants`;
  attempts 15774 = successes 15735 + global failures 39;
  successes `[23,231,993,3362,8035,3091]` sum to 15735;
  provider failure bins `[0,0,0,0,0,0]`. These workloads do not exercise
  nonzero failures in the six reported categories; runtime coverage is not
  exhaustive. Global failures still include the excluded longest provider.
- Parser checks: all six new names, `_per_fail` ratios, and zero denominators
  pass. Both Scala trees have matching classification and aggregation code;
  obsolete blocked metadata is absent.

Build log: `/tmp/tage-provider-fail-build.log`; run logs:
`/tmp/tage-provider-fail-baseline.log`, `/tmp/tage-provider-fail-stress.log`,
`/tmp/tage-provider-fail-isa.log`.
