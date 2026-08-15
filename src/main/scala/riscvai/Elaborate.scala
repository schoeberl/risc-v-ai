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
