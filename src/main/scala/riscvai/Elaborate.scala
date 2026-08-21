package riscvai

import chisel3.RawModule
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

/** Emits the textbook five-stage processor. */
object ElaborateFiveStages extends App {
  ChiselStage.emitSystemVerilogFile(
    new RvaiFiveStages,
    args = args,
    firtoolOpts = Array(
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    )
  )
}

/** Emits the six-stage processor. */
object ElaborateSixStages extends App {
  ChiselStage.emitSystemVerilogFile(
    new RvaiSixStages,
    args = args,
    firtoolOpts = Array(
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    )
  )
}

/** Emits the six-stage processor with separate memory request and response. */
object ElaborateSixStagesMemorySplit extends App {
  ChiselStage.emitSystemVerilogFile(
    new RvaiSixStagesMemorySplit,
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

/** Emits the serialized multicycle processor. */
object ElaborateMulticycle extends App {
  ChiselStage.emitSystemVerilogFile(
    new RvaiMulticycle,
    args = args,
    firtoolOpts = Array(
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    )
  )
}

private object IdealMemoryElaboration {
  def emit(module: => RawModule, args: Array[String]): Unit =
    ChiselStage.emitSystemVerilogFile(
      module,
      args = args,
      firtoolOpts = Array(
        "--disable-all-randomization",
        "--strip-debug-info",
        "--lowering-options=disallowLocalVariables"
      )
    )
}

object ElaborateIdealMemoryMulticycle extends App {
  IdealMemoryElaboration.emit(new IdealMemoryRvaiMulticycle, args)
}

object ElaborateIdealMemoryTwoStages extends App {
  IdealMemoryElaboration.emit(new IdealMemoryRvaiTwoStages, args)
}

object ElaborateIdealMemoryThreeStages extends App {
  IdealMemoryElaboration.emit(new IdealMemoryRvaiThreeStages, args)
}

object ElaborateIdealMemoryThreeStagesPredecode extends App {
  IdealMemoryElaboration.emit(new IdealMemoryRvaiThreeStagesPredecode, args)
}

object ElaborateIdealMemoryThreeStagesExecuteMemory extends App {
  IdealMemoryElaboration.emit(new IdealMemoryRvaiThreeStagesExecuteMemory, args)
}

object ElaborateIdealMemoryFourStages extends App {
  IdealMemoryElaboration.emit(new IdealMemoryRvaiFourStages, args)
}

object ElaborateIdealMemoryFiveStages extends App {
  IdealMemoryElaboration.emit(new IdealMemoryRvaiFiveStages, args)
}

object ElaborateIdealMemorySixStages extends App {
  IdealMemoryElaboration.emit(new IdealMemoryRvaiSixStages, args)
}

object ElaborateIdealMemorySixStagesMemorySplit extends App {
  IdealMemoryElaboration.emit(new IdealMemoryRvaiSixStagesMemorySplit, args)
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

/** Emits the textbook five-stage cached processor using Sky130 SRAM macros. */
object ElaborateSky130CachedFiveStages extends App {
  ChiselStage.emitSystemVerilogFile(
    new Sky130CachedRvaiFiveStages,
    args = args,
    firtoolOpts = Array(
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    )
  )
}

/** Emits the six-stage cached processor using Sky130 SRAM macros. */
object ElaborateSky130CachedSixStages extends App {
  ChiselStage.emitSystemVerilogFile(
    new Sky130CachedRvaiSixStages,
    args = args,
    firtoolOpts = Array(
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    )
  )
}

/** Emits the memory-split six-stage cached processor using Sky130 SRAM macros. */
object ElaborateSky130CachedSixStagesMemorySplit extends App {
  ChiselStage.emitSystemVerilogFile(
    new Sky130CachedRvaiSixStagesMemorySplit,
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

/** Emits the cached serialized multicycle core using Sky130 SRAM macros. */
object ElaborateSky130CachedMulticycle extends App {
  ChiselStage.emitSystemVerilogFile(
    new Sky130CachedRvaiMulticycle,
    args = args,
    firtoolOpts = Array(
      "--disable-all-randomization",
      "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    )
  )
}
