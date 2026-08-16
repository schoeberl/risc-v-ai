# risc-v-ai

[![CI](https://github.com/schoeberl/risc-v-ai/actions/workflows/ci.yml/badge.svg)](https://github.com/schoeberl/risc-v-ai/actions/workflows/ci.yml)

An experimental AI generated RISC-V processor written in
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
data-memory interface, plus a cached top-level wrapper:

- `riscvai.RiscVCore` is the original single-cycle reference implementation.
- `riscvai.PipelinedRiscVCore` is the four-stage implementation.
- `riscvai.CachedPipelinedRiscVCore` adds private instruction and data caches
  with one arbitrated memory interface.

Memories are kept outside the cores so they can be connected to simulation
models, FPGA block RAM, or a system bus.

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
parameters. Cache data uses synchronous storage, so a hit has a registered
lookup response and holds the pipeline until that response is accepted. A miss
continues to hold the pipeline while four 32-bit words are refilled. Tags remain
small register arrays.

The instruction cache is read-only and is invalidated by `FENCE.I`. The data
cache is write-through: loads allocate a line, stores update a resident line and
are always forwarded to memory, and store misses write around the cache. An AMO
miss refills before performing its read-modify-write so the architectural old
value remains correct.

Both caches share a single 32-bit memory port. A request consists of `request`,
`write`, `address`, `writeData`, and four byte write strobes. The selected cache
holds those signals until memory raises `ready`; load/refill data is returned on
`readData` in that cycle. Arbitration gives an older data access priority over
speculative instruction fetch and locks the grant across memory wait states.
There are not yet bus-error responses, uncached/MMIO regions, prefetching,
or coherence.

The default cached top infers synchronous memories, which FPGA tools can map to
block RAM. `Sky130CachedPipelinedRiscVCore` instead instantiates two
`sky130_sram_1kbyte_1rw1r_32x256_8` OpenRAM macros, one for each cache. Port 1 is
the lookup port; port 0 performs byte-masked store updates and full-word
refills. The macro's falling-edge read output is captured before it reaches the
core, keeping SRAM-to-core logic out of the critical path.

## RTL and Sky130 PPA

Generate synthesis-ready SystemVerilog for the pipelined core with:

```sh
make rtl
```

Generate the cached top level with:

```sh
make rtl-cached
```

Generate the cached Sky130 macro top level with:

```sh
make rtl-sky130-cached
```

The generated files are written to `generated/`. Run the Sky130A LibreLane flow
at a 10 ns (100 MHz) clock target with a descriptive run tag, then print the
post-CTS metrics with:

```sh
make ppa-sky130 PPA_RUN_TAG=decode-split-split-sign-mul-100mhz
python3 ppa/librelane/report_metrics.py decode-split-split-sign-mul-100mhz
```

Run the equivalent 100 MHz flow with the two cache SRAMs using:

```sh
make ppa-sky130-sram-cached \
  PPA_RUN_TAG=sram-caches-registered-response-100mhz
python3 ppa/librelane/report_metrics.py \
  sram-caches-registered-response-100mhz
```

By default, the Make target uses LibreLane from `~/librelane` and the PDK from
`~/.ciel`. Override `LIBRELANE_ROOT` or `SKY130_PDK_ROOT` when they are installed
elsewhere. LibreLane run data is written below `ppa/librelane/runs/` and is not
tracked by Git.

The current target is the processor core without instruction/data memories or
peripherals. LibreLane's fallback SDC models 2 ns input and output delays; a SoC
wrapper should replace those assumptions when memories and peripherals are
integrated.

All results below use LibreLane Classic, Sky130A `sky130_fd_sc_hd`, the checked-in
`ppa/librelane/config.yaml`, a 10 ns clock, and the nominal-corner post-CTS state
from step 36. "Fmax" is the estimate `1000 / (10 - WNS)` in MHz, not the result of
rerunning the flow at successively shorter clock periods. The run tag is also the
directory name below `ppa/librelane/runs/`.

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
