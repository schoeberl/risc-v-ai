package riscvai

import chisel3._

/** Core-only comparison top for an ideal, zero-wait-state memory system.
  *
  * Instruction and data reads are combinational. Instruction fetch is always
  * valid, data memory never stalls, and the atomic read value is supplied by
  * the same ideal data-memory port. Cache-precharge and simulation-only debug
  * signals are deliberately kept out of the physical top-level interface.
  */
class IdealMemoryRvaiPipeline(
    resetVector: BigInt = 0,
    threeStages: Boolean = false,
    predecodeInFetch: Boolean = false,
    executeMemoryInThirdStage: Boolean = false,
    twoStages: Boolean = false,
    multicycle: Boolean = false,
    fiveStages: Boolean = false,
    sixStages: Boolean = false,
    sixStagesMemorySplit: Boolean = false
) extends Module {
  require(!predecodeInFetch || threeStages)
  require(!executeMemoryInThirdStage || threeStages)
  require(!twoStages || (!threeStages && !predecodeInFetch && !executeMemoryInThirdStage))
  require(!multicycle || (!twoStages && !threeStages && !predecodeInFetch &&
    !executeMemoryInThirdStage && !fiveStages && !sixStages && !sixStagesMemorySplit))
  require(!fiveStages || (!twoStages && !threeStages && !predecodeInFetch &&
    !executeMemoryInThirdStage && !multicycle && !sixStages && !sixStagesMemorySplit))
  require(!sixStages || (!twoStages && !threeStages && !predecodeInFetch &&
    !executeMemoryInThirdStage && !multicycle && !fiveStages && !sixStagesMemorySplit))
  require(!sixStagesMemorySplit || (!twoStages && !threeStages && !predecodeInFetch &&
    !executeMemoryInThirdStage && !multicycle && !fiveStages && !sixStages))

  val io = IO(new Bundle {
    val instructionAddress = Output(UInt(32.W))
    val instruction = Input(UInt(32.W))

    val dataAddress = Output(UInt(32.W))
    val dataReadData = Input(UInt(32.W))
    val dataReadEnable = Output(Bool())
    val dataWriteData = Output(UInt(32.W))
    val dataWriteEnable = Output(Bool())
    val dataWriteMask = Output(UInt(4.W))
  })

  private val core = if (sixStagesMemorySplit) {
    Module(new RvaiSixStagesMemorySplit(resetVector))
  } else if (sixStages) {
    Module(new RvaiSixStages(resetVector))
  } else if (fiveStages) {
    Module(new RvaiFiveStages(resetVector))
  } else if (multicycle) {
    Module(new RvaiMulticycle(resetVector))
  } else if (twoStages) {
    Module(new RvaiTwoStages(resetVector))
  } else if (executeMemoryInThirdStage) {
    Module(new RvaiThreeStagesExecuteMemory(resetVector))
  } else if (predecodeInFetch) {
    Module(new RvaiThreeStagesPredecode(resetVector))
  } else if (threeStages) {
    Module(new RvaiThreeStages(resetVector))
  } else {
    Module(new RvaiFourStages(resetVector))
  }

  core.io.instruction := io.instruction
  core.io.instructionValid := true.B
  core.io.dataReadData := io.dataReadData
  core.io.dataAtomicReadData := io.dataReadData
  core.io.memoryStall := false.B
  core.io.debugRegisterAddress := 0.U

  io.instructionAddress := core.io.instructionAddress
  io.dataAddress := core.io.dataAddress
  io.dataReadEnable := core.io.dataReadEnable
  io.dataWriteData := core.io.dataWriteData
  io.dataWriteEnable := core.io.dataWriteEnable
  io.dataWriteMask := core.io.dataWriteMask
}

class IdealMemoryRvaiMulticycle(resetVector: BigInt = 0)
    extends IdealMemoryRvaiPipeline(resetVector, multicycle = true)

class IdealMemoryRvaiTwoStages(resetVector: BigInt = 0)
    extends IdealMemoryRvaiPipeline(resetVector, twoStages = true)

class IdealMemoryRvaiThreeStages(resetVector: BigInt = 0)
    extends IdealMemoryRvaiPipeline(resetVector, threeStages = true)

class IdealMemoryRvaiThreeStagesPredecode(resetVector: BigInt = 0)
    extends IdealMemoryRvaiPipeline(
      resetVector,
      threeStages = true,
      predecodeInFetch = true
    )

class IdealMemoryRvaiThreeStagesExecuteMemory(resetVector: BigInt = 0)
    extends IdealMemoryRvaiPipeline(
      resetVector,
      threeStages = true,
      executeMemoryInThirdStage = true
    )

class IdealMemoryRvaiFourStages(resetVector: BigInt = 0)
    extends IdealMemoryRvaiPipeline(resetVector)

class IdealMemoryRvaiFiveStages(resetVector: BigInt = 0)
    extends IdealMemoryRvaiPipeline(resetVector, fiveStages = true)

class IdealMemoryRvaiSixStages(resetVector: BigInt = 0)
    extends IdealMemoryRvaiPipeline(resetVector, sixStages = true)

class IdealMemoryRvaiSixStagesMemorySplit(resetVector: BigInt = 0)
    extends IdealMemoryRvaiPipeline(resetVector, sixStagesMemorySplit = true)
