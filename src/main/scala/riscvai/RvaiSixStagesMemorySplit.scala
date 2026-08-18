package riscvai

/** A six-stage processor with fetch, decode/register-read, execute,
  * memory-request, memory-response, and writeback stages.
  *
  * The synchronous data-cache address is launched from the registered request
  * stage. Loads and atomics use the following response stage and therefore
  * impose two bubbles on an immediately dependent instruction.
  */
class RvaiSixStagesMemorySplit(resetVector: BigInt = 0)
    extends RvaiPipeline(
      resetVector,
      separateDecodeExecute = true,
      separateWritebackStage = true,
      forwardInExecute = true,
      separateMemoryResponseStage = true
    )
