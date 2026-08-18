package riscvai

/** A six-stage processor with fetch, decode, register read, execute, memory,
  * and writeback stages.
  *
  * Decode registers source-use metadata before the register-read and hazard
  * stage. Results are forwarded into execute, and an immediately dependent
  * load or atomic operation incurs one bubble.
  */
class RvaiSixStages(resetVector: BigInt = 0)
    extends RvaiPipeline(
      resetVector,
      separateDecodeExecute = true,
      separateWritebackStage = true,
      forwardInExecute = true,
      separateDecodeRegisterReadStage = true
    )
