package riscvai

import circt.stage.ChiselStage

/** Emits synthesis-ready SystemVerilog for the pipelined processor. */
object Elaborate extends App {
  ChiselStage.emitSystemVerilogFile(
    new PipelinedRiscVCore,
    args = args,
    firtoolOpts = Array(
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    )
  )
}

/** Emits the cached processor and its shared 32-bit memory interface. */
object ElaborateCached extends App {
  ChiselStage.emitSystemVerilogFile(
    new CachedPipelinedRiscVCore,
    args = args,
    firtoolOpts = Array(
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    )
  )
}

/** Emits the cached processor using installed Sky130 SRAM hard macros. */
object ElaborateSky130Cached extends App {
  ChiselStage.emitSystemVerilogFile(
    new Sky130CachedPipelinedRiscVCore,
    args = args,
    firtoolOpts = Array(
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    )
  )
}
