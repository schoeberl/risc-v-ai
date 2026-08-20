#include "support.h"

#include <stdint.h>

#define RESULT_BASE 0xffff0000u
#define RESULT_STATUS (*(volatile uint32_t *)(RESULT_BASE + 0u))
#define RESULT_CYCLES (*(volatile uint32_t *)(RESULT_BASE + 4u))
#define RESULT_INSTRET (*(volatile uint32_t *)(RESULT_BASE + 8u))
#define RESULT_DONE (*(volatile uint32_t *)(RESULT_BASE + 12u))
#define RESULT_VALUE (*(volatile uint32_t *)(RESULT_BASE + 16u))
#define RESULT_ACTIVE (*(volatile uint32_t *)(RESULT_BASE + 20u))
#define RESULT_DONE_MAGIC 0x454d4f4bu

static uint32_t read_cycle(void) {
  uint32_t value;
  __asm__ volatile("csrr %0, cycle" : "=r"(value));
  return value;
}

static uint32_t read_instret(void) {
  uint32_t value;
  __asm__ volatile("csrr %0, instret" : "=r"(value));
  return value;
}

void initialise_board(void) {}

void start_trigger(void) {}

void stop_trigger(void) {}

int main(void) {
  volatile int result;
  uint32_t start_cycles;
  uint32_t start_instructions;
  uint32_t stop_cycles;
  uint32_t stop_instructions;

  initialise_board();
  initialise_benchmark();
  warm_caches(WARMUP_HEAT);

  RESULT_ACTIVE = 1u;
  start_cycles = read_cycle();
  start_instructions = read_instret();
  result = benchmark();
  stop_cycles = read_cycle();
  stop_instructions = read_instret();
  RESULT_ACTIVE = 0u;

  RESULT_STATUS = verify_benchmark(result) == 1 ? 1u : 0u;
  RESULT_CYCLES = stop_cycles - start_cycles;
  RESULT_INSTRET = stop_instructions - start_instructions;
  RESULT_VALUE = (uint32_t)result;
  RESULT_DONE = RESULT_DONE_MAGIC;

  for (;;) {}
}
