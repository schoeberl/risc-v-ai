#include <stddef.h>
#include <stdint.h>

#define RESULT_BASE 0xffff0000u
#define RESULT_STATUS (*(volatile uint32_t *)(RESULT_BASE + 0u))
#define RESULT_DONE (*(volatile uint32_t *)(RESULT_BASE + 12u))
#define RESULT_VALUE (*(volatile uint32_t *)(RESULT_BASE + 16u))
#define RESULT_DONE_MAGIC 0x454d4f4bu

static int runtime_errno;

int *__errno(void) { return &runtime_errno; }

void *memcpy(void *destination, const void *source, size_t count) {
  unsigned char *dst = destination;
  const unsigned char *src = source;
  while (count-- != 0u) *dst++ = *src++;
  return destination;
}

void *memmove(void *destination, const void *source, size_t count) {
  unsigned char *dst = destination;
  const unsigned char *src = source;
  if (dst <= src) {
    while (count-- != 0u) *dst++ = *src++;
  } else {
    dst += count;
    src += count;
    while (count-- != 0u) *--dst = *--src;
  }
  return destination;
}

void *memset(void *destination, int value, size_t count) {
  unsigned char *dst = destination;
  while (count-- != 0u) *dst++ = (unsigned char)value;
  return destination;
}

int memcmp(const void *left, const void *right, size_t count) {
  const unsigned char *lhs = left;
  const unsigned char *rhs = right;
  while (count-- != 0u) {
    if (*lhs != *rhs) return *lhs < *rhs ? -1 : 1;
    ++lhs;
    ++rhs;
  }
  return 0;
}

size_t strlen(const char *text) {
  const char *end = text;
  while (*end != '\0') ++end;
  return (size_t)(end - text);
}

void exit(int status) {
  RESULT_STATUS = 0u;
  RESULT_VALUE = (uint32_t)status;
  RESULT_DONE = RESULT_DONE_MAGIC;
  for (;;) {}
}

void abort(void) { exit(-1); }
