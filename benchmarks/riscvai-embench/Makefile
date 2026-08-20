EMBENCH_DIR ?= ../embench-iot
BUILD_DIR ?= ../../build/embench
CROSS ?= riscv64-unknown-elf-
CC := $(CROSS)gcc
OBJCOPY := $(CROSS)objcopy
SIZE := $(CROSS)size

BENCHMARKS := aha-mont64 crc32 cubic edn huffbench matmult-int minver nbody \
	nettle-aes nettle-sha256 nsichneu picojpeg qrduino sglib-combined slre st \
	statemate ud wikisort
ELFS := $(BENCHMARKS:%=$(BUILD_DIR)/%.elf)
BINS := $(BENCHMARKS:%=$(BUILD_DIR)/%.bin)
COMMON_SOURCES := main.c runtime.c start.S $(EMBENCH_DIR)/support/beebsc.c
EMBENCH_SOURCES := $(wildcard $(EMBENCH_DIR)/src/*/*.c)

CFLAGS := -O2 -g0 -march=rv32im_zicsr -mabi=ilp32 -mstrict-align \
	-ffreestanding -fno-builtin -fno-common -ffunction-sections \
	-fdata-sections -Wall -Wextra -I. -I$(EMBENCH_DIR)/support \
	-DEMBENCH_REPETITIONS=1 \
	-DCPU_MHZ=EMBENCH_REPETITIONS/LOCAL_SCALE_FACTOR -DWARMUP_HEAT=1
LDFLAGS := -nostdlib -nostartfiles -static -Wl,--gc-sections \
	-Wl,-T,link.ld -march=rv32im_zicsr -mabi=ilp32

.PHONY: all clean list
all: $(BINS)

list:
	@printf '%s\n' $(BENCHMARKS)

$(BUILD_DIR)/%.elf: $(COMMON_SOURCES) $(EMBENCH_SOURCES) link.ld Makefile
	@mkdir -p $(BUILD_DIR)
	$(CC) $(CFLAGS) $(LDFLAGS) -Wl,-Map,$(@:.elf=.map) -o $@ \
		$(COMMON_SOURCES) $(wildcard $(EMBENCH_DIR)/src/$*/*.c) -lm -lc -lgcc
	$(SIZE) $@

$(BUILD_DIR)/%.bin: $(BUILD_DIR)/%.elf
	$(OBJCOPY) -O binary $< $@

clean:
	rm -f $(ELFS) $(BINS) $(BINS:.bin=.map)
