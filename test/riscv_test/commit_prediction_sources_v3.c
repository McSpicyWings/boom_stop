#include <stdio.h>
#include <stdint.h>

/* Chipyard v3 ANDI interface; not the boom_stop FPGA ADDI interface. */
static uint64_t counters[64];
static volatile uint64_t sink;
static __attribute__((noinline)) uint64_t first(uint64_t n) { sink += n; return sink; }
static __attribute__((noinline)) uint64_t second(uint64_t n) { sink ^= n; return sink; }
static uint64_t (* volatile target)(uint64_t);
#define READ_ONE(start, n) \
    "andi x0, t0, 512+" #start "+" #n "\n\t" \
    "sd t0, " #n "*8(t1)\n\t"
#define READ8(base, start) asm volatile( \
    "mv t1, %0\n\t" \
    READ_ONE(start,0) READ_ONE(start,1) READ_ONE(start,2) READ_ONE(start,3) \
    READ_ONE(start,4) READ_ONE(start,5) READ_ONE(start,6) READ_ONE(start,7) \
    : : "r"(base) : "t0", "t1", "memory")

int main(void) {
    asm volatile("li t0, 0x1234567\n\taddi x0, t0, 1\n\t"
                 "li t0, 3\n\taddi x0, t0, 6\n\t"
                 "andi x0, t0, 1024\n\t" ::: "t0", "memory");
    uint32_t rng = 0x12345678;
    for (unsigned i = 0; i < 3000; ++i) {
        rng ^= rng << 13; rng ^= rng >> 17; rng ^= rng << 5;
        if (rng & 1) sink += i; else sink ^= i;
        sink ^= first(i);             /* direct JAL and return */
        target = (rng & 2) ? first : second;
        sink += target(i);            /* indirect JALR and return */
    }
    /* Unique instruction freezes the common measurement window. */
    asm volatile("addi x0, x0, 1\n\t" ::: "memory");
    READ8(counters,0); READ8(counters+8,8);
    READ8(counters+16,16); READ8(counters+24,24);
    READ8(counters+32,32); READ8(counters+40,40);
    READ8(counters+48,48); READ8(counters+56,56);
    uint64_t totals[3] = {0,0,0};
    for (int kind = 0; kind < 3; ++kind)
        for (int src = 0; src < 6; ++src)
            totals[kind] += counters[9 + kind*6 + src];
    int failed = totals[0] != counters[1] || totals[1] != counters[28] ||
                 totals[2] <= counters[29] || totals[1] < 3000 || totals[2] < 6000 ||
                 counters[14] != counters[20] + counters[26] || counters[20] == 0 ||
                 counters[25] == 0 || counters[26] == 0 ||
                 counters[13] != counters[19] + counters[25];
    for (int src = 0; src < 6; ++src)
        failed |= counters[15+src] + counters[21+src] > counters[9+src];
    printf("commit inst=%llu br=%llu jalr=%llu jump=%llu\n",
           counters[1], counters[28], counters[29], totals[2]);
    for (int i = 9; i < 27; ++i)
        printf("event %d: %llu\n", i, counters[i]);
    printf("%s: commit source histogram invariants\n", failed ? "FAIL" : "PASS");
    return failed;
}
