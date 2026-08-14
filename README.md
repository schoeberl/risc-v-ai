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
- `LUI` and `AUIPC`
- all six conditional branches
- `JAL` and `JALR`
- all RV32I loads and stores (`LB`, `LBU`, `LH`, `LHU`, `LW`, `SB`, `SH`, and `SW`)
- four byte-write strobes for partial memory writes
- detection of unsupported instructions
- a read-only register debug port

Tests execute small machine-code programs and verify arithmetic, the hardwired
zero register, control flow, return addresses, and memory traffic.

## Next milestones

- instruction and data-memory modules around the core
- a compliance-test runner using a RISC-V cross compiler
- exceptions, CSRs, and the privileged execution environment
- differential testing of the pipelined core against the single-cycle reference

The original `riscvai.Adder` remains as a minimal Chisel example.
