/* Platform code for short, CRC-checked CoreMark RTL simulations. */
#include "coremark.h"

#define RESULT_BASE 0xffff0000u
#define RESULT_STATUS (*(volatile ee_u32 *)(RESULT_BASE + 0u))
#define RESULT_CYCLES (*(volatile ee_u32 *)(RESULT_BASE + 4u))
#define RESULT_INSTRET (*(volatile ee_u32 *)(RESULT_BASE + 8u))
#define RESULT_ITERATIONS (*(volatile ee_u32 *)(RESULT_BASE + 12u))
#define RESULT_DONE (*(volatile ee_u32 *)(RESULT_BASE + 16u))
#define RESULT_DONE_MAGIC 0x434d4f4bu

#if VALIDATION_RUN
volatile ee_s32 seed1_volatile = 0x3415;
volatile ee_s32 seed2_volatile = 0x3415;
volatile ee_s32 seed3_volatile = 0x66;
#endif
#if PERFORMANCE_RUN
volatile ee_s32 seed1_volatile = 0;
volatile ee_s32 seed2_volatile = 0;
volatile ee_s32 seed3_volatile = 0x66;
#endif
#if PROFILE_RUN
volatile ee_s32 seed1_volatile = 8;
volatile ee_s32 seed2_volatile = 8;
volatile ee_s32 seed3_volatile = 8;
#endif
volatile ee_s32 seed4_volatile = ITERATIONS;
volatile ee_s32 seed5_volatile = 0;

ee_u32 default_num_contexts = 1;
static ee_u32 start_cycles;
static ee_u32 stop_cycles;
static ee_u32 start_instructions;
static ee_u32 stop_instructions;
static ee_u32 validation_ok = 1;

static ee_u32 read_cycle(void) {
  ee_u32 value;
  __asm__ volatile("csrr %0, cycle" : "=r"(value));
  return value;
}

static ee_u32 read_instret(void) {
  ee_u32 value;
  __asm__ volatile("csrr %0, instret" : "=r"(value));
  return value;
}

void start_time(void) {
  start_cycles = read_cycle();
  start_instructions = read_instret();
}

void stop_time(void) {
  stop_cycles = read_cycle();
  stop_instructions = read_instret();
}

CORE_TICKS get_time(void) {
  return stop_cycles - start_cycles;
}

secs_ret time_in_secs(CORE_TICKS ticks) {
  return ticks / 100000000u;
}

static int starts_with(const char *text, const char *prefix) {
  while (*prefix != '\0') {
    if (*text++ != *prefix++) return 0;
  }
  return 1;
}

int ee_printf(const char *fmt, ...) {
  /* The testbench has no UART. Record correctness errors while deliberately
   * ignoring the expected "shorter than 10 seconds" reporting error. */
  if (starts_with(fmt, "[%u]ERROR!") ||
      (starts_with(fmt, "ERROR!") &&
       !starts_with(fmt, "ERROR! Must execute for at least 10 secs"))) {
    validation_ok = 0;
  }
  return 0;
}

void portable_init(core_portable *p, int *argc, char *argv[]) {
  (void)argc;
  (void)argv;
  validation_ok = 1;
  p->portable_id = 1;
}

void portable_fini(core_portable *p) {
  p->portable_id = 0;
  RESULT_STATUS = validation_ok;
  RESULT_CYCLES = stop_cycles - start_cycles;
  RESULT_INSTRET = stop_instructions - start_instructions;
  RESULT_ITERATIONS = (ee_u32)seed4_volatile;
  RESULT_DONE = RESULT_DONE_MAGIC;
}
