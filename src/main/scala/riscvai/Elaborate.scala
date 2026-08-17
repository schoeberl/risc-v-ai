package riscvai

import circt.stage.ChiselStage

/** Emits synthesis-ready SystemVerilog for the four-stage processor. */
object Elaborate extends App {
  ChiselStage.emitSystemVerilogFile(
    new RvaiFourStages,
    args = args,
    firtoolOpts = Array(
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    )
  )
}

/** Emits synthesis-ready SystemVerilog for the three-stage processor. */
object ElaborateThreeStages extends App {
  ChiselStage.emitSystemVerilogFile(
    new RvaiThreeStages,
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
    new CachedRvaiFourStages,
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
    new Sky130CachedRvaiFourStages,
    args = args,
    firtoolOpts = Array(
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    )
  )
}

/** Emits the three-stage cached processor using installed Sky130 SRAM macros. */
object ElaborateSky130CachedThreeStages extends App {
  ChiselStage.emitSystemVerilogFile(
    new Sky130CachedRvaiThreeStages,
    args = args,
    firtoolOpts = Array(
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    )
  )
}
