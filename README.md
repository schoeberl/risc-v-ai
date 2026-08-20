# risc-v-ai

[![CI](https://github.com/schoeberl/risc-v-ai/actions/workflows/ci.yml/badge.svg)](https://github.com/schoeberl/risc-v-ai/actions/workflows/ci.yml)
[![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/schoeberl/risc-v-ai)

An experimental AI generated RISC-V processor written in
[Chisel](https://www.chisel-lang.org/).

## Requirements

- JDK 17 or newer
- sbt
- Verilator (for simulation tests)
- Nix (for the pinned LibreLane environment used by the Sky130 PPA flows)

### GitHub Codespaces

Use the **Open in GitHub Codespaces** badge above, or select **Code**,
**Codespaces**, **Create codespace on main** on GitHub. The checked-in dev
container provides JDK 17, sbt, Verilator, the RISC-V bare-metal compiler, and
Nix. Its first-run setup initializes the pinned CoreMark, Embench-IoT, and LibreLane
submodules and downloads the Scala dependencies.

After the terminal opens, verify the development environment with:

```sh
sbt test
make coremark
```

The RTL and simulation workflows work without additional configuration.
Sky130 PPA also needs the Sky130 PDK below `~/.ciel` and the licensed CF SRAM
views; provide those separately as Codespaces secrets or secure artifacts.

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

The project contains ten 32-bit cores with the same separate instruction and
data-memory interface, plus cached top-level wrappers:

- `riscvai.RiscVCore` is the original single-cycle reference implementation.
- `riscvai.RvaiFourStages` is the four-stage implementation. Pipeline
  organizations live in separate, explicitly named Chisel modules so they can
  evolve and be compared independently.
- `riscvai.RvaiFiveStages` is the textbook IF/ID/EX/MEM/WB implementation,
  with forwarding into execute and a distinct writeback stage.
- `riscvai.RvaiSixStages` separates decode from register read and hazard
  checking in an IF/ID/RR/EX/MEM/WB organization.
- `riscvai.RvaiSixStagesMemorySplit` instead separates synchronous data-cache
  request and response in an IF/ID/EX/MEM1/MEM2/WB organization.
- `riscvai.RvaiTwoStages` uses the synchronous instruction-cache output for
  decode/register-read/execute in stage one and performs synchronous data-cache
  access plus writeback in stage two.
- `riscvai.RvaiThreeStages` keeps the register after instruction fetch and
  combines decode/register-read with execute, followed by memory/writeback.
- `riscvai.RvaiThreeStagesPredecode` is a separate three-stage experiment that
  moves source-usage and multiply/divide classification into fetch while keeping
  register-file reads in the combined decode/execute stage.
- `riscvai.RvaiThreeStagesExecuteMemory` follows Wildcat's `ThreeCats`
  organization: decode/register-read and effective-address calculation precede
  a combined execute/memory/writeback stage.
- `riscvai.RvaiMulticycle` serializes fetch, decode/register-read, execute, and
  memory/writeback so only one instruction is active at a time.
- `riscvai.CachedRvaiFourStages` adds private instruction and data caches
  with one arbitrated memory interface.
- `riscvai.CachedRvaiThreeStages` applies the same cache hierarchy to the
  three-stage core for direct comparison.
- `riscvai.CachedRvaiThreeStagesPredecode` wraps the predecode experiment with
  the same caches and arbitration.
- `riscvai.CachedRvaiThreeStagesExecuteMemory` wraps the merged execute/memory
  experiment with the same caches and arbitration.
- `riscvai.CachedRvaiTwoStages` applies the same cache hierarchy to the
  two-stage core.
- `riscvai.CachedRvaiMulticycle` applies the same cache hierarchy to the
  serialized core.
- `riscvai.CachedRvaiFiveStages` applies the same cache hierarchy to the
  textbook five-stage core.
- `riscvai.CachedRvaiSixStages` applies the same cache hierarchy to the
  six-stage core.
- `riscvai.CachedRvaiSixStagesMemorySplit` wraps the alternative memory-split
  six-stage core.

Memories are kept outside the cores so they can be connected to simulation
models, FPGA block RAM, or a system bus.

### Pipeline organization

The pipeline names below are the same as those used in the PPA comparison.
A slash means that the operations are combined within one pipeline stage.

| Pipeline | Stage 1 | Stage 2 | Stage 3 | Stage 4 | Stage 5 | Stage 6 |
|---|---|---|---|---|---|---|
| Two stages | IF/ID/RR/EX | MEM/WB | — | — | — | — |
| Three stages | IF | ID/RR/EX | MEM/WB | — | — | — |
| Three stages + fetch predecode | IF/predecode | ID/RR/EX | MEM/WB | — | — | — |
| Three stages + execute/memory | IF | ID/RR/AG | EX/MEM/WB | — | — | — |
| Four stages | IF | ID/RR | EX | MEM/WB | — | — |
| Five stages | IF | ID/RR | EX | MEM | WB | — |
| Six stages + ID/RR split | IF | ID | RR | EX | MEM | WB |
| Six stages + memory split | IF | ID/RR | EX | MEM1 | MEM2 | WB |

Abbreviations:

- **IF:** instruction fetch
- **ID:** instruction decode
- **RR:** register read
- **AG:** effective-address generation
- **EX:** ALU, multiply/divide, and control-flow execution
- **MEM1:** synchronous data-cache request
- **MEM2:** data-cache response
- **MEM:** data-memory access
- **WB:** register writeback

### Six-stage memory-split pipeline

`RvaiSixStagesMemorySplit` retains the five-stage frontend and branch behavior,
then separates data-cache request from response. MEM1 registers the effective
address and launches the synchronous SRAM lookup; MEM2 presents that request to
the cache and consumes its hit data or miss response before WB. ALU results
forward through both memory stages. Loads and atomics deliberately wait for WB
instead of forwarding live SRAM data into EX, so an immediately dependent
instruction incurs two bubbles.

### Six-stage pipeline

`RvaiSixStages` inserts a decode-to-register-read boundary into the five-stage
organization. Decode registers the instruction and source-use metadata; the
following stage reads the register file, checks the load-use hazard, and
captures operands for EX. Forwarding still occurs in EX, and loads and atomics
retain the same one-cycle dependent-instruction bubble. Branches still resolve
in EX, but the deeper front end adds one wrong-path fetch per redirect.

### Five-stage pipeline

`RvaiFiveStages` implements the conventional fetch, decode/register-read,
execute, memory, and writeback organization. ALU results forward from MEM and
WB into EX. Loads and atomics use the registered WB result, so an immediately
dependent instruction incurs the textbook one-cycle load-use bubble. WB can
commit independently while a younger MEM access stalls; a consumed bit prevents
repeated retirement while retaining the WB payload for a held EX instruction's
forwarding path. Branches and jumps resolve in EX and flush the two younger
stages, matching the four-stage core's control-hazard penalty.

### Four-stage pipeline

The pipelined core is organized as:

1. **Fetch** maintains the PC and fetches an instruction.
2. **Decode/Register Read** decodes the instruction and reads or forwards its
   source operands.
3. **Execute** runs the ALU, resolves control flow, and calculates memory addresses.
4. **Memory/Writeback** performs data-memory access and commits register results.

The core forwards execute- and writeback-stage results to decode. A load or
atomic followed immediately by a dependent instruction stalls for one cycle,
avoiding a combinational path from data memory through the next instruction's
ALU. Taken branches and jumps flush younger instructions. Pipeline valid bits
prevent bubbles from changing architectural state.

Retirement outputs report the PC and instruction reaching the final stage. A
stall output and read-only register debug port support simulation and future
compliance testing.

### Two-stage pipeline

`RvaiTwoStages` does not add a core register after instruction fetch. The
instruction cache's synchronous address register is the stage boundary: its
output feeds instruction decode, register read, ALU/control-flow execution, and
effective-address generation in stage one. The execute-to-memory register then
aligns the request metadata with the synchronous data-cache output in stage two,
where loads are forwarded and architectural results are committed. A data-cache
hit can therefore feed a dependent stage-one instruction without a bubble, at
the cost of a long cache-ready-to-next-PC combinational path.

### Three-stage pipeline

`RvaiThreeStages` keeps the fetch register, then decodes the registered
instruction, reads or forwards its operands, and executes it in one combined
stage. The unchanged memory/writeback stage follows. It shares the ISA, hazard,
trap, and external-memory behavior of the four-stage core while providing a
smaller pipeline organization for PPA comparison.

`RvaiThreeStagesPredecode` preserves those three architectural stages but moves
the `rs1`/`rs2` usage flags and multiply/divide classification before the fetch
register. Register indices, register-file reads, forwarding, immediate formation,
the remaining opcode and function checks, and execution stay in stage two. The
original `RvaiThreeStages` module remains unchanged as the baseline.

`RvaiThreeStagesExecuteMemory` instead uses fetch, decode/register-read/address,
and execute/memory/writeback stages. Stage two calculates the effective address
and drives the next-address cache interface. On the following cycle, stage three
consumes the synchronous cache output and forwards load data directly into stage
two, so an immediately dependent instruction needs no load-use bubble. Control
transfers resolve in stage three, trading an additional wrong-path fetch for a
shorter stage-two timing path.

### Multicycle core

`RvaiMulticycle` admits one instruction at a time and advances it through
explicit fetch, decode/register-read, execute, and memory/writeback states.
Dependencies cannot overlap, so this organization needs neither operand
forwarding nor load-use hazard stalls. Multiply and divide remain iterative
multicycle operations inside the execute state, and cache misses hold the state
that issued them. This follows the frequency-first serialized philosophy of
[PicoRV32](https://github.com/YosysHQ/picorv32), while retaining this project's
RV32IMA_Zicsr_Zifencei datapath, trap support, and common cache interface. It
deliberately exchanges CPI and throughput for the shortest critical path, which
is useful when a processor must not set a larger SoC's clock period.

The first milestone implements:

- RV32I register-register ALU instructions
- RV32I immediate ALU instructions
- the complete RV32M multiply/divide extension
- the complete RV32A word-atomic extension for a single hart
- `FENCE` as a legal no-op; `FENCE.I` also invalidates the cached wrapper's
  instruction cache
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
`REM`, and `REMU`, plus a shared six-stage multiplier for `MUL`, `MULH`,
`MULHSU`, and `MULHU`. Its final stage registers the selected low or high 32-bit
result. The pipeline stalls until each multicycle result is ready.
The single-cycle reference core keeps combinational RV32M operations for
straightforward architectural checking.

RV32A reservations are local to the hart and are invalidated by local stores.
Atomic read-modify-write operations assume this core is the only memory-bus
master; multicore coherence and external reservation invalidation are out of
scope.

The CSR bank now supports the first precise exception path: illegal instructions
trap to direct-mode `mtvec`, and an M-mode handler can resume with `MRET`. The
core still runs only in M-mode; it does not yet implement user-mode privilege,
`ECALL`/`EBREAK`, memory-access faults, or live interrupt sources. Until a
platform timer is added, the `time` CSR aliases the cycle counter.

### Cache hierarchy and shared memory port

The cached top level currently uses separate 1 KiB direct-mapped instruction and
data caches with 16-byte lines. These sizes and the line size are constructor
parameters. Cache data uses synchronous storage. The instruction-cache SRAM
address is driven by the core's next-PC signal, so its address register and the
fetch-PC register capture the same value. After a line is resident, consecutive
instruction hits therefore sustain one instruction per clock without stalling
the core. On an instruction miss, fetch holds its PC and inserts invalid bubbles
while older instructions continue through the pipeline; fetch resumes after the
four-word refill. All pipeline organizations use the same `DataCache` module and
the same request interface. Execute drives the next effective address into its
synchronous tag and data memories; memory/writeback receives the aligned tag and
data result in the following cycle. A resident load therefore completes without
a cache-induced stall, and consecutive loads sustain one hit per cycle in every
pipeline organization. Data-cache misses and all write-through stores still hold
the whole pipeline until they complete. Both caches' resettable valid bits remain
register arrays.

The instruction cache is read-only and is invalidated by `FENCE.I`. The data
cache is write-through: loads allocate a line, while every store is forwarded
to memory and invalidates its indexed cache line. Conservative store
invalidation avoids retaining stale data when a pipelined store-tag prediction
is unavailable; a later load refills the updated line. An AMO miss refills
before performing its read-modify-write so the architectural old value remains
correct.

Both caches share a single 32-bit memory port. A request consists of `request`,
`write`, `address`, `writeData`, and four byte write strobes. The cached top also
exports `memoryInstruction` to identify instruction-refill reads for bus traffic
measurement. The selected cache holds its signals until memory raises `ready`;
load/refill data is returned on `readData` in that cycle. Arbitration gives an
older data access priority over speculative instruction fetch and locks the grant
across memory wait states.
There are not yet bus-error responses, uncached/MMIO regions, prefetching,
or coherence.

The default cached tops infer synchronous memories, which FPGA tools can map to
block RAM. The `Sky130CachedRvai*` comparison tops instantiate four
single-port, rising-edge synchronous `CF_SRAM_1024x32` macros: independent data
and tag memories for the instruction and data caches. Reads and writes do not
overlap architecturally: a
refill or cache-maintenance write takes port priority while its speculative read
result is ignored. The caches remain 1 KiB and tie the macro's upper two address
bits low; increasing both caches to the full 4 KiB capacity is a separate
experiment. There are no OpenRAM macro instances in the current RTL or physical
flow configurations. Independent tag macros are necessary because one
single-port macro cannot perform the instruction and data lookups concurrently.
The portable inferred memories remain only in the non-Sky130 simulation and FPGA
tops; no Sky130 PPA top uses register-inferred tag storage.

Install the licensed macro into ignored project-local directories with a Python
virtual environment:

```sh
python3 -m venv .venv-cf-ipm
.venv-cf-ipm/bin/python -m pip install cf-ipm
.venv-cf-ipm/bin/ipm install CF_SRAM_1024x32 --ip-root "$PWD/ip"
```

The downloaded `ip/` tree is intentionally excluded from Git. Consult the
license shipped with the package before using or redistributing its views.

## RTL and Sky130 PPA

Generate synthesis-ready SystemVerilog for the pipelined core with:

```sh
make rtl
```

Generate the textbook five-stage core with:

```sh
make rtl-five-stages
```

Generate the six-stage core with:

```sh
make rtl-six-stages
```

Generate the alternative memory-split six-stage core with:

```sh
make rtl-six-stages-memory-split
```

Generate the two-stage core with:

```sh
make rtl-two-stages
```

Generate the three-stage core with:

```sh
make rtl-three-stages
```

Generate the fetch-predecode three-stage variant with:

```sh
make rtl-three-stages-predecode
```

Generate the merged execute/memory three-stage variant with:

```sh
make rtl-three-stages-execute-memory
```

Generate the multicycle core with:

```sh
make rtl-multicycle
```

Generate the cached top level with:

```sh
make rtl-cached
```

Generate the cached Sky130 macro top level with:

```sh
make rtl-sky130-cached
```

Generate the five-stage cached Sky130 macro top level with:

```sh
make rtl-sky130-cached-five-stages
```

Generate the six-stage cached Sky130 macro top level with:

```sh
make rtl-sky130-cached-six-stages
```

Generate the memory-split six-stage cached Sky130 macro top level with:

```sh
make rtl-sky130-cached-six-stages-memory-split
```

Generate the two-stage cached Sky130 macro top level with:

```sh
make rtl-sky130-cached-two-stages
```

Generate the three-stage cached Sky130 macro top level with:

```sh
make rtl-sky130-cached-three-stages
```

Generate the cached Sky130 fetch-predecode variant with:

```sh
make rtl-sky130-cached-three-stages-predecode
```

Generate the cached Sky130 merged execute/memory variant with:

```sh
make rtl-sky130-cached-three-stages-execute-memory
```

Generate the cached Sky130 multicycle variant with:

```sh
make rtl-sky130-cached-multicycle
```

The generated files are written to `generated/`. Initialize the pinned CoreMark,
Embench-IoT, and LibreLane submodules after cloning with:

```sh
git submodule update --init
```

Run the 100 MHz Sky130A post-CTS comparison including all four cache SRAMs with:

```sh
make ppa-sky130-sram-cached-two-stages-post-cts \
  PPA_RUN_TAG=cf-tags-two-stages-100mhz
python3 ppa/librelane/report_metrics.py \
  cf-tags-two-stages-100mhz

make ppa-sky130-sram-cached-post-cts \
  PPA_RUN_TAG=cf-tags-four-100mhz
python3 ppa/librelane/report_metrics.py \
  cf-tags-four-100mhz

make ppa-sky130-sram-cached-three-stages-post-cts \
  PPA_RUN_TAG=cf-tags-three-100mhz
python3 ppa/librelane/report_metrics.py \
  cf-tags-three-100mhz

make ppa-sky130-sram-cached-three-stages-predecode-post-cts \
  PPA_RUN_TAG=cf-tags-three-predecode-100mhz
python3 ppa/librelane/report_metrics.py \
  cf-tags-three-predecode-100mhz

make ppa-sky130-sram-cached-three-stages-execute-memory-post-cts \
  PPA_RUN_TAG=cf-tags-three-execute-memory-100mhz
python3 ppa/librelane/report_metrics.py \
  cf-tags-three-execute-memory-100mhz

make ppa-sky130-sram-cached-multicycle-post-cts \
  PPA_RUN_TAG=cf-tags-multicycle-100mhz
python3 ppa/librelane/report_metrics.py \
  cf-tags-multicycle-100mhz

make ppa-sky130-sram-cached-five-stages-post-cts \
  PPA_RUN_TAG=cf-tags-five-textbook-final-100mhz
python3 ppa/librelane/report_metrics.py \
  cf-tags-five-textbook-final-100mhz

make ppa-sky130-sram-cached-six-stages-post-cts \
  PPA_RUN_TAG=cf-tags-six-stage-100mhz
python3 ppa/librelane/report_metrics.py \
  cf-tags-six-stage-100mhz

make ppa-sky130-sram-cached-six-stages-memory-split-post-cts \
  PPA_RUN_TAG=cf-tags-six-stage-memory-split-100mhz
python3 ppa/librelane/report_metrics.py \
  cf-tags-six-stage-memory-split-100mhz
```

The post-CTS targets stop at `OpenROAD.STAMidPNR-1`, exactly the checkpoint read
by `report_metrics.py`. They skip `Checker.PowerGridViolations` because the
licensed CF SRAM abstract views do not provide the complete power connectivity
needed by that checker. The original targets without the `-post-cts` suffix
remain available for complete physical-design runs.

By default, the Make targets use the pinned LibreLane submodule at
`external/librelane` and the PDK from `~/.ciel`. Override `LIBRELANE_ROOT` or
`SKY130_PDK_ROOT` when needed. LibreLane run data is written below
`ppa/librelane/runs/` and is not tracked by Git.

### SRAM backend change

The following records the one-time memory-backend comparison on the three-stage
pipeline. It is retained for provenance; current source and PPA configurations
contain only the CF SRAM backend.

| Metric | Previous OpenRAM | Current CF SRAM | Change |
|---|---:|---:|---:|
| Estimated Fmax | 31.98 MHz | 45.49 MHz | +42.2% |
| Setup TNS | -12,509.700 ns | -14,145.200 ns | 13.1% greater magnitude |
| Standard-cell area | 387,875 µm² | 391,085 µm² | +0.8% |
| Two SRAM macro area | 381,425 µm² | 237,978 µm² | -37.6% |
| Combined area | 769,300 µm² | 629,063 µm² | -18.2% |
| Standard-cell instances | 49,235 | 51,402 | +4.4% |
| Total placed instances | 50,703 | 52,738 | +4.0% |
| Power | 44.234 mW | 52.231 mW | +18.1% |

The old Fmax was limited by an internal falling-edge timing check in the OpenRAM
model. The CF macro replaces that half-cycle constraint with normal rising-edge
setup and clock-to-data arcs. Its higher modeled power is the principal cost;
the two macros are substantially smaller even though each physical macro has
4 KiB capacity and the current cache uses only 1 KiB.

### Cache-inclusive pipeline comparison

This table compares only pipeline organization: every entry includes the same
two 1 KiB caches, four CF SRAM data/tag macros, cache control, and shared
arbiter. All entries use LibreLane Classic, Sky130A `sky130_fd_sc_hd`, identical
1600 µm by 1200 µm floorplans, a 10 ns clock target, and the nominal-corner
post-CTS state. The flows skip post-global-placement design repair and post-CTS
resizer optimization so an unattainable 100 MHz target does not distort area
with repair buffers.

| Metric | Multicycle | Two stages | Three stages | Three stages + fetch predecode | Three stages + execute/memory | Four stages | Five stages | Six stages + ID/RR split | Six stages + memory split |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Estimated Fmax | 72.67 MHz | 49.97 MHz | 46.58 MHz | 46.13 MHz | 52.63 MHz | 59.08 MHz | 63.70 MHz | 57.48 MHz | 54.03 MHz |
| Standard-cell area | 264,869 µm² | 257,082 µm² | 259,275 µm² | 260,127 µm² | 260,786 µm² | 263,976 µm² | 270,920 µm² | 273,191 µm² | 278,490 µm² |
| Four SRAM macro area | 475,955 µm² | 475,955 µm² | 475,955 µm² | 475,955 µm² | 475,955 µm² | 475,955 µm² | 475,955 µm² | 475,955 µm² | 475,955 µm² |
| Combined area | 740,824 µm² | 733,037 µm² | 735,230 µm² | 736,082 µm² | 736,741 µm² | 739,931 µm² | 746,875 µm² | 749,146 µm² | 754,445 µm² |
| Standard-cell instances | 39,292 | 38,742 | 38,967 | 39,011 | 38,962 | 39,144 | 39,575 | 39,716 | 40,178 |
| Total placed instances | 41,114 | 40,564 | 40,789 | 40,833 | 40,784 | 40,966 | 41,397 | 41,538 | 42,000 |
| Sequential cells | 2,714 | 2,485 | 2,550 | 2,554 | 2,537 | 2,679 | 2,779 | 2,846 | 2,953 |
| Power | 50.633 mW | 48.800 mW | 49.201 mW | 49.174 mW | 49.204 mW | 50.272 mW | 51.562 mW | 52.050 mW | 52.613 mW |
| CoreMark CPI | 4.523 | 1.523 | 1.695 | 1.695 | 1.774 | 1.835 | 1.835 | 1.975 | 1.923 |
| Projected iterations/s | 51.28 | 104.73 | 87.71 | 86.86 | 94.69 | 102.75 | 110.79 | 92.87 | 89.66 |

The CF SRAM model uses rising-edge address and data timing, so all nine Fmax
values use the ordinary `1 / (10 ns - WNS)` full-cycle estimate. These runs use
the unified execute-to-memory data-cache lookup, with synchronous CF SRAM tag
reads in all nine organizations. The multicycle organization is fastest: its
72.67 MHz estimate is 23.0% above the four-stage result. Its worst path is the
asynchronous register-file read during the dedicated decode state, from a source
register-index bit to the captured operand. Serialized control removes
forwarding and cache-ready feedback from that path, but this implementation
reuses the common pipeline payloads and execution units; consequently it is a
frequency comparison point rather than an area-minimized PicoRV32 clone.
The textbook five-stage core reaches 63.70 MHz, 7.8% above four stages. Its
worst path begins in ID source-register/hazard decoding and crosses pipeline
stall control; the live SRAM load result is deliberately excluded from EX
forwarding. The extra MEM/WB payload and forwarding control increase combined
area by 0.9% and power by 2.6% relative to four stages.
The six-stage split does not improve timing in this implementation. It reaches
57.48 MHz, 9.8% below five stages, because the worst path moves away from decode:
it starts at the MEM-stage store address/control, crosses data-cache ready and
global pipeline backpressure, and ends at a core pipeline register. The added
front-end payload increases this high-fanout hold network as well as raising
combined area by 0.3% and power by 0.9% relative to five stages.
The alternative memory split also leaves cache ready combinationally connected
to global pipeline hold. Its added MEM2 payload increases that network's load,
so Fmax falls to 54.03 MHz rather than improving. The critical path starts at a
MEM2 address bit, crosses store-enable generation, cache ready, and global
backpressure, and ends at a pipeline register. Relative to five stages, combined
area rises 1.0%, power rises 2.0%, and Fmax falls 15.2%.
Moving the tags into hard macros removes the register-memory decoder and tag
selection from the critical hit path. The four-stage worst path is now wholly
inside core decode/control. The plain three-stage worst path ends in the
multiplier, while the fetch-predecode worst path ends at the instruction-data
SRAM input. The merged execute/memory path runs from execute control through the
data-cache ready/stall response and retirement control into stage-two decode.
The two-stage worst path begins at the data-cache tag SRAM, passes through hit
detection and pipeline backpressure, then crosses stage-one decode/control and
ends at the instruction-cache data SRAM address. None of the nine worst setup
paths ends in a tag memory. Resolving branches in stage three makes the merged
variant 13.0% faster than the plain three-stage
core, though it remains 10.9% slower than four stages at this checkpoint. The
two-stage core is 7.3% faster than the plain three-stage core but 5.1% slower
than the merged three-stage core.

The timing improvement costs macro area. Each tag array needs only 64 by 22 bits
but occupies a 1024-by-32 macro, using about 4.3% of its bit capacity. A
right-sized tag SRAM would preserve most of the timing benefit with much less
area. Power is estimated at the requested 100 MHz activity point even though
none of the designs closes timing at 100 MHz.

### CoreMark simulation comparison

The repository pins the official, unmodified
[EEMBC CoreMark](https://github.com/eembc/coremark) sources as a Git submodule at
commit `1f483d5b8316753a742cbf5590caf5bd0a4e4777`. Clone it and run the complete
comparison with:

```sh
git submodule update --init
make coremark
```

This requires `riscv64-unknown-elf-gcc`, `objcopy`, and `size`. The checked-in
bare-metal port builds one performance-seed iteration using GCC 13.2.0 with
`-O2 -march=rv32im_zicsr -mabi=ilp32`, a static 2,000-byte CoreMark data block,
and no libc. The ELF contains 10,608 bytes of text, 16 bytes of initialized data,
and 2,028 bytes of BSS. The same binary runs on all nine cached organizations.

Every row below passed CoreMark's algorithm and data-type CRC checks. The shared
external memory responds combinationally so the comparison focuses on the
pipeline organizations with identical caches, rather than on memory latency.
Cycles and retired instructions come from the core's `cycle` and `instret` CSRs
around the timed region. The transfer columns count accesses on the shared
external port and split reads by the cache selected by the arbiter.
Projected iterations/s combines cycles per iteration with each pipeline's
cache-inclusive Sky130 Fmax from the unified-cache PPA table above.

| Pipeline | Cycles/iteration | Retired instructions | CPI | Projected iterations/s | I-cache reads | D-cache reads | External writes |
|---|---:|---:|---:|---:|---:|---:|---:|
| Multicycle | 1,417,204 | 313,331 | 4.523 | 51.28 | 10,932 | 53,768 | 16,478 |
| Two stages | 477,143 | 313,332 | 1.523 | 104.73 | 10,932 | 53,768 | 16,478 |
| Three stages | 531,089 | 313,332 | 1.695 | 87.71 | 11,516 | 53,768 | 16,478 |
| Three stages + fetch predecode | 531,089 | 313,332 | 1.695 | 86.86 | 11,516 | 53,768 | 16,478 |
| Three stages + execute/memory | 555,806 | 313,331 | 1.774 | 94.69 | 11,824 | 53,768 | 16,478 |
| Four stages | 574,974 | 313,332 | 1.835 | 102.75 | 11,824 | 53,768 | 16,478 |
| Five stages | 574,974 | 313,332 | 1.835 | 110.79 | 11,824 | 53,768 | 16,478 |
| Six stages + ID/RR split | 618,906 | 313,332 | 1.975 | 92.87 | 16,716 | 53,768 | 16,478 |
| Six stages + memory split | 602,597 | 313,332 | 1.923 | 89.66 | 11,824 | 53,768 | 16,478 |

The identical 53,768 data reads and 16,478 writes demonstrate that all nine
pipeline organizations use equivalent data-cache behavior. The four-stage,
five-stage, and merged execute/memory cores perform 308 additional instruction
reads because they resolve control flow in stage three rather than stage two. The extra
wrong-path fetches raise CPI to 1.774 versus 1.695 for the plain
three-stage core, despite eliminating the load-use bubble. The higher 52.63 MHz
Fmax more than offsets that CPI cost: projected throughput is 94.69
iterations/s, 8.0% above the plain three-stage core and 7.8% below the four-stage
core. The one-instruction `instret` difference comes from the timing of the
benchmark's counter read, while all CRCs and external data traffic match.
The two-stage core resolves control flow in stage one and issues 10,932
instruction reads, 584 fewer than the plain three-stage core. Its CPI of 1.523
is 10.1% lower than the plain three-stage result. Combining that CPI with the
49.97 MHz post-CTS estimate gives 104.73 projected iterations/s: 19.4% above the
plain three-stage core and 1.9% above the four-stage core.
The five-stage and four-stage cores have identical cycle counts, CPI, and
external traffic because both resolve control flow in EX and insert one
load-use bubble. The distinct WB boundary raises Fmax from 59.08 to 63.70 MHz,
so projected throughput increases by 7.8% to 110.79 iterations/s.
The six-stage front end issues 4,892 more instruction reads than five stages
because EX redirects discard one additional in-flight instruction. Together
with the timing regression, its 1.975 CPI reduces projected throughput to 92.87
iterations/s, 16.2% below five stages.
The memory-split alternative retains the five-stage instruction traffic and
branch penalty, but the second load-use bubble raises CPI to 1.923. Combined
with its 54.03 MHz timing result, projected throughput is 89.66 iterations/s,
19.1% below five stages.
The multicycle core has the same external traffic as the two-stage core, but its
one-instruction-at-a-time schedule raises CPI to 4.523. Its 72.67 MHz Fmax does
not compensate for those extra cycles on CoreMark, yielding 51.28 projected
iterations/s. This is the intended integration-oriented point: it maximizes
clock-frequency headroom rather than standalone processor throughput.

These are short RTL-simulation comparison results, not reportable CoreMark
scores. CoreMark's official rules require at least ten seconds of execution and
both performance- and validation-seed runs. The projected figures above are
therefore deliberately labeled iterations/s rather than `CoreMark 1.0` scores.
They are useful for comparing these pipelines because the program, compiler,
cache geometry, and memory model are otherwise identical.

### Embench-IoT simulation comparison

The repository also pins
[Embench-IoT 1.0](https://github.com/embench/embench-iot) at commit
`0466a18e4f6b47e19598d7c6ba72916d54b68f65`. Build all 19 bare-metal RV32IM
programs and run the default RTL comparison with:

```sh
git submodule update --init
make embench
```

The port uses GCC with `-O2 -march=rv32im_zicsr -mabi=ilp32`. Each measurement
executes one fundamental benchmark repetition after one warm-up repetition and
checks Embench's built-in result before reporting CPI. This deliberately short
run makes cycle-accurate RTL simulation practical; it is a pipeline comparison,
not an official Embench speed score. The default set contains eight integer
workloads that complete in a practical simulation time. Any built program can
instead be selected with, for example:

```sh
EMBENCH_BENCHMARK=aha-mont64 make embench
EMBENCH_PIPELINE='Five stages' make embench
```

| Benchmark | Two stages | Three stages | Three stages + fetch predecode | Three stages + execute/memory | Four stages | Five stages | Six stages + ID/RR split | Six stages + memory split |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| crc32 | 1.595 | 1.732 | 1.732 | 1.868 | 1.868 | 1.868 | 2.005 | 1.868 |
| edn | 2.115 | 2.212 | 2.212 | 2.312 | 2.311 | 2.311 | 2.408 | 2.307 |
| huffbench | 1.389 | 1.562 | 1.562 | 1.700 | 1.717 | 1.717 | 1.872 | 1.757 |
| matmult-int | 1.953 | 2.088 | 2.088 | 2.223 | 2.223 | 2.223 | 2.358 | 2.223 |
| nettle-aes | 1.855 | 1.868 | 1.868 | 1.882 | 1.880 | 1.880 | 1.889 | 1.879 |
| nettle-sha256 | 2.353 | 2.382 | 2.382 | 2.411 | 2.404 | 2.404 | 2.433 | 2.400 |
| slre | 2.030 | 2.205 | 2.205 | 2.388 | 2.407 | 2.407 | 2.534 | 2.450 |
| statemate | 2.768 | 2.892 | 2.892 | 3.057 | 3.038 | 3.038 | 3.153 | 3.030 |
| **Instruction-weighted aggregate** | **1.663** | **1.800** | **1.800** | **1.919** | **1.929** | **1.929** | **2.054** | **1.951** |

The aggregate is total measured cycles divided by total retired instructions,
so longer workloads carry proportionally more weight. The results reinforce the
CoreMark ordering while showing that it is not peculiar to a single program:
the two-stage core has the lowest CPI on every selected workload, and fetch
predecode remains cycle-equivalent to the plain three-stage core. Splitting
ID/RR produces the highest aggregate CPI; splitting memory is less costly but
adds CPI relative to five stages.

`sglib-combined` is not part of the default table because its built-in check
exposed a repeatable processor correctness failure on the two-stage and plain
three-stage cores (result `0x00003aca`), while the same binary passes on the
four-stage and merged execute/memory cores. The integration keeps that program
selectable so the discrepancy can serve as a focused regression while its root
cause is investigated. Floating-point-heavy or especially long programs such
as `cubic` and `picojpeg` are likewise omitted from the default matrix because
this RV32IM target has no hardware floating point and per-cycle RTL simulation
would dominate the comparison time.

### Historical PPA experiments

The experiment log below records earlier core and cache timing work. Run tags are
directory names below `ppa/librelane/runs/`; Fmax values are post-CTS estimates
unless an entry explicitly identifies a half-cycle path.

- `100mhz` — original combinational RV32M baseline: WNS -196.162 ns, estimated
  Fmax 4.85 MHz.
- `iterative-100mhz` — replaced the combinational divider with the 32-cycle
  iterative divider (`4419025`): WNS -8.712 ns, estimated Fmax 53.44 MHz.
- `four-stage-mul-100mhz` — initial four-stage partial-product multiplier: WNS
  -8.079 ns, estimated Fmax 55.31 MHz.
- `corrected-four-stage-mul-100mhz` — corrected the multiplier pipeline and its
  control (`e7f8ef6`): WNS -4.392 ns, estimated Fmax 69.49 MHz.
- `registered-mul-result-100mhz` — registered the selected multiplier result
  (`be606a3`): WNS -4.688 ns, estimated Fmax 68.08 MHz.
- `writeback-stage-100mhz` — experimental extra core writeback stage, later
  reverted: WNS -5.996 ns, estimated Fmax 62.52 MHz.
- `reset-free-100mhz` — removed resets from datapath registers while retaining
  reset on valid/control state (`a2c8a8f`): WNS -4.278 ns, estimated Fmax
  70.04 MHz.
- `valid-only-bubbles-100mhz` — experimental retention of invalid pipeline
  payloads instead of writing zero bubbles, later reverted: WNS -6.318 ns,
  estimated Fmax 61.28 MHz.
- `decode-execute-split-100mhz` — split decode/register-read and execute into
  separate core stages: WNS -3.858 ns, estimated Fmax 72.16 MHz.
- `decode-split-six-stage-mul-100mhz` — registered the multiplier magnitude
  before sign correction: WNS -2.568 ns, estimated Fmax 79.56 MHz.
- `decode-split-split-sign-mul-100mhz` — split the 64-bit sign correction into
  registered 32-bit halves: WNS -2.751 ns, estimated Fmax 78.42 MHz. The critical
  path moved out of the multiplier to the atomic read-modify-write path from
  `io_dataReadData` to `io_dataWriteData`.
- `registered-atomic-rmw-100mhz` — experimental capture of the atomic read value
  and registered replacement, later reverted: WNS -4.348 ns, estimated Fmax
  69.70 MHz. This removed the input-to-output AMO path but exposed the
  execute-to-decode bypass.
- `registered-atomic-execute-bypass-100mhz` — experimental move of ALU forwarding
  from decode to execute, later reverted: WNS -5.560 ns, estimated Fmax 64.27 MHz.
- `registered-atomic-fsm-100mhz` — experimental registered AMO read-state control,
  later reverted: WNS -6.734 ns, estimated Fmax 59.76 MHz.
- `registered-atomic-state-control-100mhz` — experimental removal of the AMO
  operation decode from retirement control, later reverted: WNS -5.746 ns,
  estimated Fmax 63.51 MHz.
- `no-a-extension-100mhz` — experimental removal of RV32A, later reverted: WNS
  -3.146 ns, estimated Fmax 76.07 MHz, 248,441 µm² of standard cells, and
  21.693 mW. Area and power improved, but timing regressed from the 78.42 MHz
  baseline; the critical path moved to the execute-result/decode-forwarding
  network.
- `cache-control-deferred-100mhz` — uncached control for the register-cache
  experiment, synthesized with `SYNTH_HIERARCHY_MODE: deferred_flatten`: WNS
  -1.408 ns, estimated Fmax 87.66 MHz, 265,297 µm² of standard cells, 30,912
  standard-cell instances, 2,354 flip-flops, and 26.273 mW.
- `register-caches-deferred-100mhz` — 1 KiB instruction and 1 KiB data caches
  implemented as flip-flop arrays, also using deferred flattening: WNS -9.714 ns,
  estimated Fmax 50.72 MHz, 1,248,130 µm² of standard cells, 128,649
  standard-cell instances, 21,847 flip-flops, and 181.270 mW. Relative to the
  matched uncached control, area is 4.70x larger, the flip-flop count is 9.28x
  larger, and estimated Fmax is 42.1% lower. The caches account for 19,328 of
  the 19,493 added state bits (16,384 data bits plus 2,944 tag and valid bits).
  The worst path runs from the core memory-stage address through data-cache
  selection/control to a data-cache state register. Flat synthesis of these
  asynchronous register arrays was impractically slow, so both sides of this
  comparison use `deferred_flatten`. The cached step-36 setup and power reports
  completed, but the run was stopped while OpenROAD generated the nonessential
  clock-skew report for 21,847 clock sinks.
- `sram-caches-100mhz` — first replacement of the register-array data stores
  with two 1 KiB Sky130 SRAMs: WNS -4.369 ns and TNS -506.461 ns. The worst path
  was a half-cycle path from the falling-edge SRAM output through the AMO logic
  and back to the same SRAM's write input, so the usual full-cycle Fmax formula
  does not meaningfully describe this result.
- `sram-caches-split-amo-100mhz` — added separate AMO preparation and cache-write
  states: WNS -3.426 ns and TNS -505.603 ns. The remaining worst path was still
  a half-cycle SRAM-output path, this time ending at the pending write-data
  register.
- `sram-caches-registered-response-100mhz` — registered both cache lookup
  responses before exposing them to the core: WNS -1.072 ns, estimated Fmax
  90.32 MHz, TNS -75.255 ns, 438,188 µm² of standard cells, 381,425 µm² of SRAM
  macros, 819,613 µm² combined cell-and-macro area, 5,610 flip-flops, and
  49.902 mW. The critical path moved completely out of the caches and now runs
  from the core memory/writeback address into CSR/trap logic. Relative to the
  register-cache run, combined area is 34.3% lower, the flip-flop count is 74.3%
  lower, power is 72.5% lower, and estimated Fmax is 78.1% higher. The 90.32 MHz
  estimate is close to the matched uncached control's 87.66 MHz, indicating that
  the SRAM-backed caches no longer impose the timing limit; their different top
  levels and floorplans make the small difference unsuitable as a claimed
  speedup.
- `sram-caches-single-cycle-icache-100mhz` — experimental removal of the
  instruction-cache response register, with the SRAM address driven from the
  core's next-PC signal: WNS -4.869 ns, estimated Fmax 67.26 MHz, TNS -622.251
  ns, 439,105 µm² of standard cells, 381,425 µm² of SRAM macros, and 820,530
  µm² combined area. Compared with the registered-response version, combined
  area increases by only 0.11%, but estimated Fmax falls by 25.5%. The critical
  path starts at `decodeExecute_instruction[14]`, passes through redirect and
  next-PC selection, then through the asynchronous 64-entry instruction-tag
  register mux, and ends at the selected-tag register. The next optimization is
  to make the tag lookup synchronous and align it with the SRAM data read.
- `sram-caches-sync-icache-tags-100mhz` — changed the instruction tags to a
  synchronous memory addressed alongside the instruction-data SRAM: WNS -5.655
  ns, estimated Fmax 63.88 MHz, TNS -5,914.590 ns, 440,436 µm² of standard
  cells, 381,425 µm² of SRAM macros, and 821,861 µm² combined area. This removed
  the next-PC-to-selected-tag-register critical path, but timing regressed a
  further 5.0% from the asynchronous-tag single-cycle experiment. The new worst
  path starts at the tag memory's registered read address, passes through its
  synthesized 64-entry tag mux and hit comparison, then continues through the
  cache-ready/core-stall network into CSR retirement state. Relative to the
  original registered cache-response version, combined area is 0.27% higher and
  estimated Fmax is 29.3% lower. Achieving both one-hit-per-cycle throughput and
  high Fmax will require decoupling speculative fetch hit/miss handling from the
  core-wide retirement stall rather than adding another cache lookup register.
- `sram-caches-local-icache-miss-100mhz` — replaced the instruction cache's
  core-wide miss stall with local fetch backpressure: a miss holds the fetch PC
  and injects invalid bubbles while older pipeline stages drain. WNS is -1.350
  ns, estimated Fmax is 88.11 MHz, TNS is -73.004 ns, standard-cell area is
  440,076 µm², SRAM macro area is 381,425 µm², combined area is 821,501
  µm², and power is 45.570 mW. This improves estimated Fmax by 37.9% and
  reduces TNS by 98.8% relative to the synchronous-tag/global-stall experiment,
  with effectively unchanged area. It is 2.4% slower and 0.23% larger than the
  registered-response baseline, but retains one-hit-per-cycle instruction
  throughput. The new worst path starts at the synchronous tag memory's read
  address, passes through the synthesized tag mux and hit comparison to
  `instructionValid`, and ends at instruction bit 23 of the fetch/decode
  register. This confirms that removing instruction readiness from the global
  retirement-stall network eliminated the much longer tag-to-CSR path.
- `sram-caches-single-cycle-dcache-100mhz` — experimental execute-stage
  addressing of synchronous data and tag memories, allowing a resident data
  read to complete in memory/writeback without a cache-induced stall: WNS
  -5.912 ns, estimated Fmax 62.85 MHz, TNS -12,896.300 ns, 442,999 µm² of
  standard cells, 381,425 µm² of SRAM macros, 824,424 µm² combined area, and
  45.427 mW. Against `sram-caches-local-icache-miss-100mhz`, Fmax regresses by
  28.7%, TNS magnitude grows by 177x, and combined area increases by 0.36%.
  The worst path starts at the data-tag memory's registered read address,
  crosses its synthesized 64-entry tag mux and hit comparison, then propagates
  through `cpuReady`, the global data-cache stall, core retirement/FENCE.I
  control, and into an instruction-tag memory bit. Thus the experiment achieves
  the intended one-hit-per-cycle data throughput but is not suitable for the
  current 100 MHz target. The next implementation should determine and register
  the data-tag hit before memory/writeback, or handle data misses with replay,
  so tag lookup is removed from the global-stall path.
- `sram-caches-pipelined-dcache-tag-100mhz` — moved the synchronous data-tag
  lookup into decode and registered its comparison result in execute: WNS
  -3.626 ns, estimated Fmax 73.39 MHz, TNS -620.795 ns, 444,123 µm² of standard
  cells, 381,425 µm² of SRAM macros, 825,548 µm² combined area, and 45.386 mW.
  This recovers 16.8% Fmax relative to the direct single-cycle experiment and
  removes tag lookup from `cpuReady`. The new worst path is a half-cycle path
  from the data SRAM's falling-edge output through AMO result logic to the
  registered write data. The state machine logically uses a registered old
  value for that calculation, but the shared load/AMO data signal leaves the
  false structural path visible to STA.
- `sram-caches-pipelined-dcache-tag-amo-100mhz` — added a dedicated registered
  AMO operand path, structurally cutting the false SRAM-to-AMO path: WNS -3.230
  ns, estimated Fmax 75.59 MHz, TNS -2,852.700 ns, 444,274 µm² of standard
  cells, 381,425 µm² of SRAM macros, 825,699 µm² combined area, and 45.225 mW.
  This is 20.3% faster than the direct single-cycle data-cache experiment, with
  0.15% more combined area, but remains 14.2% slower and 0.51% larger than
  `sram-caches-local-icache-miss-100mhz`. The worst path now starts at the
  registered atomic/store control, crosses data-request and `cpuReady` logic and
  the global memory-stall network, and ends at the constrained top-level
  `illegalInstruction` output. The SRAM data and tag lookup are no longer on the
  worst path; further improvement should separate store/miss sequencing from
  global retirement control or register nonarchitectural status outputs.

The selected SRAM distribution supplies only a TT, 1.8 V, 25 °C Liberty model.
The SRAM runs therefore use that nominal model at every standard-cell corner;
the nominal post-CTS comparison is useful for development, but is not a
multi-corner signoff result. The first two SRAM experiments also violate a
half-cycle path and are retained only to document why the final response
register was added.

The commit IDs in parentheses identify published source checkpoints. After
checking one out, generate its RTL and run the same checked-in LibreLane config;
older Makefiles without `PPA_RUN_TAG` require passing `--run-tag` directly to
LibreLane. The entries explicitly marked experimental were measured before being
reverted; their saved run directories preserve the reports, but their source
variants are intentionally not Git checkpoints. Run directories are untracked,
so archive a run separately when it must remain available after cleaning the
workspace.

## Next milestones

- an external memory controller and uncached peripheral/MMIO region
- SRAM or FPGA block-RAM implementation for cache tags
- a compliance-test runner using a RISC-V cross compiler
- the remaining synchronous exceptions, beginning with `ECALL` and `EBREAK`
- user-mode privilege and the platform interrupt/timer path
- differential testing of the pipelined core against the single-cycle reference

The original `riscvai.Adder` remains as a minimal Chisel example.
