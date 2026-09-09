#include <stdio.h>
#include "pfc_define.h"
volatile unsigned long sink;
int main(void) {
    start_record();
    unsigned int state = 0x12345678;
    for (unsigned int i = 0; i < 10000; ++i) {
        state ^= state << 13;
        state ^= state >> 17;
        state ^= state << 5;
        if (state & 1) sink += i; else sink ^= i;
        if (state & 2) sink += 3; else sink ^= 7;
        if (i & 4) sink += 5; else sink ^= 11;
        if ((state & 15) < 12) sink += 13; else sink ^= 17;
    }
    // Freeze counters before reading a consistent snapshot.
    asm volatile("addi x0, x0, 1" ::: "memory");
    ReadCounter16(&exit_counters[32], 32);
    ReadCounter16(&exit_counters[48], 48);
    for (int i = 41; i <= 43; ++i)
        printf("event %2d: exit_counters: %llu\n", i, exit_counters[i]);
    // v3: 56 = no provider; 57..61 = provider t0..t4 (t5 excluded).
    for (int i = 50; i <= 61; ++i)
        printf("event %2d: exit_counters: %llu\n", i, exit_counters[i]);
    unsigned long long successes = 0, classified_failures = 0;
    for (int i = 50; i <= 55; ++i) successes += exit_counters[i];
    for (int i = 56; i <= 61; ++i) classified_failures += exit_counters[i];
    // Global failures also include provider t5; these six categories do not.
    if (successes != exit_counters[42] ||
        exit_counters[41] != exit_counters[42] + exit_counters[43] ||
        classified_failures > exit_counters[43]) {
        printf("FAIL: allocation counter invariants\n");
        return 1;
    }
    printf("PASS: allocation counter invariants\n");
    return 0;
}
