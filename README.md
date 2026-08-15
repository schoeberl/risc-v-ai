# risc-v-ai

[![CI](https://github.com/schoeberl/risc-v-ai/actions/workflows/ci.yml/badge.svg)](https://github.com/schoeberl/risc-v-ai/actions/workflows/ci.yml)

An educational, single-cycle RISC-V processor written in
[Chisel](https://www.chisel-lang.org/).

## Requirements

- JDK 17 or newer
- sbt
- Verilator (for simulation tests)

## Commands

Compile the design:

```sh
sbt compile
```

Run the tests:

```sh
sbt test
```

## Processor

The project contains two 32-bit cores with the same separate instruction and
data-memory interface:

- `riscvai.RiscVCore` is the original single-cycle reference implementation.
- `riscvai.PipelinedRiscVCore` is the three-stage implementation.

Memories are kept outside the cores so they can be connected to simulation
models, FPGA block RAM, or a system bus.

### Three-stage pipeline

The pipelined core is organized as:

1. **Fetch** maintains the PC and fetches an instruction.
2. **Decode/Execute** reads registers, decodes the instruction, runs the ALU,
   resolves control flow, and calculates memory addresses.
3. **Memory/Writeback** performs data-memory access and commits register results.

The core forwards stage-three results to stage two. A load followed immediately
by a dependent instruction stalls for one cycle, avoiding a combinational path
from data memory through the next instruction's ALU. Taken branches and jumps
flush the sequentially fetched instruction. Pipeline valid bits prevent bubbles
from changing architectural state.

Retirement outputs report the PC and instruction reaching the final stage. A
stall output and read-only register debug port support simulation and future
compliance testing.

The first milestone implements:

- RV32I register-register ALU instructions
- RV32I immediate ALU instructions
- the complete RV32M multiply/divide extension
- the complete RV32A word-atomic extension for a single hart
- `FENCE` and `FENCE.I` as legal no-ops for the uncached single-hart memory system
- all six Zicsr read/modify/write instruction forms
- the machine CSR foundation: `mstatus`, `misa`, `mie`, `mtvec`, `mcounteren`,
  `mscratch`, `mepc`, `mcause`, `mtval`, `mip`, hart identification, and RV32
  cycle/time/retired counters
- precise illegal-instruction traps into direct-mode `mtvec`, including
  `mepc`, `mcause`, `mtval`, `mstatus` updates, pipeline flushing, and `MRET`
- `LUI` and `AUIPC`
- all six conditional branches
- `JAL` and `JALR`
- all RV32I loads and stores (`LB`, `LBU`, `LH`, `LHU`, `LW`, `SB`, `SH`, and `SW`)
- four byte-write strobes for partial memory writes
- detection of unsupported instructions
- a read-only register debug port

Tests execute small machine-code programs and verify arithmetic, the hardwired
zero register, control flow, return addresses, memory traffic, and trap/return
behavior.

The pipelined core uses a shared 32-cycle iterative divider for `DIV`, `DIVU`,
`REM`, and `REMU`; the pipeline stalls until its quotient and remainder are
ready. Multiplication remains combinational. The single-cycle reference core
keeps combinational RV32M operations for straightforward architectural checking.

RV32A reservations are local to the hart and are invalidated by local stores.
Atomic read-modify-write operations assume this core is the only memory-bus
master; multicore coherence and external reservation invalidation are out of
scope.

The CSR bank now supports the first precise exception path: illegal instructions
trap to direct-mode `mtvec`, and an M-mode handler can resume with `MRET`. The
core still runs only in M-mode; it does not yet implement user-mode privilege,
`ECALL`/`EBREAK`, memory-access faults, or live interrupt sources. Until a
platform timer is added, the `time` CSR aliases the cycle counter.

## RTL and Sky130 PPA

Generate synthesis-ready SystemVerilog for the pipelined core with:

```sh
make rtl
```

The generated files are written to `generated/`. Run the Sky130A LibreLane flow
at a 10 ns (100 MHz) clock target with:

```sh
make ppa-sky130
```

By default, the Make target uses LibreLane from `~/librelane` and the PDK from
`~/.ciel`. Override `LIBRELANE_ROOT` or `SKY130_PDK_ROOT` when they are installed
elsewhere. LibreLane run data is written below `ppa/librelane/runs/` and is not
tracked by Git.

The current target is the processor core without instruction/data memories or
peripherals. LibreLane's fallback SDC models 2 ns input and output delays; a SoC
wrapper should replace those assumptions when memories and peripherals are
integrated.

## Next milestones

- instruction and data-memory modules around the core
- a compliance-test runner using a RISC-V cross compiler
- the remaining synchronous exceptions, beginning with `ECALL` and `EBREAK`
- user-mode privilege and the platform interrupt/timer path
- differential testing of the pipelined core against the single-cycle reference

The original `riscvai.Adder` remains as a minimal Chisel example.
