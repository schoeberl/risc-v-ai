# risc-v-ai

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

`riscvai.RiscVCore` is a 32-bit core with separate instruction and data-memory
interfaces. Memories are kept outside the core so the same processor can be
connected to simulation models, FPGA block RAM, or a system bus.

The first milestone implements:

- RV32I register-register ALU instructions
- RV32I immediate ALU instructions
- `LUI` and `AUIPC`
- all six conditional branches
- `JAL` and `JALR`
- word loads and stores (`LW` and `SW`)
- detection of unsupported instructions
- a read-only register debug port

Tests execute small machine-code programs and verify arithmetic, the hardwired
zero register, control flow, return addresses, and memory traffic.

## Next milestones

- byte and halfword loads/stores with byte write enables
- instruction and data-memory modules around the core
- a compliance-test runner using a RISC-V cross compiler
- exceptions, CSRs, and the privileged execution environment
- optional pipelining after the single-cycle reference core is complete

The original `riscvai.Adder` remains as a minimal Chisel example.
