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

/** Emits the three-stage processor with fetch-stage predecode. */
object ElaborateThreeStagesPredecode extends App {
  ChiselStage.emitSystemVerilogFile(
    new RvaiThreeStagesPredecode,
    args = args,
    firtoolOpts = Array(
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    )
  )
}

/** Emits the three-stage processor with merged execute and memory. */
object ElaborateThreeStagesExecuteMemory extends App {
  ChiselStage.emitSystemVerilogFile(
    new RvaiThreeStagesExecuteMemory,
    args = args,
    firtoolOpts = Array(
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    )
  )
}

/** Emits the two-stage processor. */
object ElaborateTwoStages extends App {
  ChiselStage.emitSystemVerilogFile(
    new RvaiTwoStages,
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

/** Emits the cached three-stage predecode comparison with Sky130 SRAM macros. */
object ElaborateSky130CachedThreeStagesPredecode extends App {
  ChiselStage.emitSystemVerilogFile(
    new Sky130CachedRvaiThreeStagesPredecode,
    args = args,
    firtoolOpts = Array(
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    )
  )
}

/** Emits the merged execute/memory comparison with Sky130 SRAM macros. */
object ElaborateSky130CachedThreeStagesExecuteMemory extends App {
  ChiselStage.emitSystemVerilogFile(
    new Sky130CachedRvaiThreeStagesExecuteMemory,
    args = args,
    firtoolOpts = Array(
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    )
  )
}

/** Emits the two-stage cached comparison with Sky130 SRAM macros. */
object ElaborateSky130CachedTwoStages extends App {
  ChiselStage.emitSystemVerilogFile(
    new Sky130CachedRvaiTwoStages,
    args = args,
    firtoolOpts = Array(
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    )
  )
}
