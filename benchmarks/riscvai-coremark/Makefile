COREMARK_DIR ?= ../coremark
BUILD_DIR ?= ../../build/coremark
CROSS ?= riscv64-unknown-elf-
CC := $(CROSS)gcc
OBJCOPY := $(CROSS)objcopy
SIZE := $(CROSS)size
ITERATIONS ?= 1
OPT ?= -O2

CFLAGS := $(OPT) -g0 -march=rv32im_zicsr -mabi=ilp32 -mstrict-align \
	-ffreestanding -fno-builtin -fno-common -msmall-data-limit=0 \
	-Wall -Wextra -Werror -I. -I$(COREMARK_DIR) \
	-DPERFORMANCE_RUN=1 -DITERATIONS=$(ITERATIONS) \
	-DTOTAL_DATA_SIZE=2000
LDFLAGS := -nostdlib -nostartfiles -static -Wl,--gc-sections \
	-Wl,-T,link.ld -march=rv32im_zicsr -mabi=ilp32
SOURCES := $(COREMARK_DIR)/core_list_join.c $(COREMARK_DIR)/core_main.c \
	$(COREMARK_DIR)/core_matrix.c $(COREMARK_DIR)/core_state.c \
	$(COREMARK_DIR)/core_util.c core_portme.c start.S
ELF := $(BUILD_DIR)/coremark.elf
BIN := $(BUILD_DIR)/coremark.bin

.PHONY: all clean check
all: $(BIN)

$(ELF): $(SOURCES) core_portme.h link.ld Makefile
	mkdir -p $(BUILD_DIR)
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(SOURCES) -lgcc
	$(SIZE) $@

$(BIN): $(ELF)
	$(OBJCOPY) -O binary $< $@

check:
	git -C $(COREMARK_DIR) diff --exit-code -- \
	  core_list_join.c core_main.c core_matrix.c core_state.c core_util.c coremark.h
	git -C $(COREMARK_DIR) diff --cached --exit-code -- \
	  core_list_join.c core_main.c core_matrix.c core_state.c core_util.c coremark.h

clean:
	rm -f $(ELF) $(BIN)
