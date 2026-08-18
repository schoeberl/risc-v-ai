package riscvai

/** A textbook five-stage processor with fetch, decode/register-read, execute,
  * memory, and writeback stages.
  *
  * ALU results are forwarded from execute, memory, and writeback to decode.
  * Loads and atomics are forwarded from memory after the synchronous cache
  * lookup, so an immediately dependent instruction incurs one bubble.
  */
class RvaiFiveStages(resetVector: BigInt = 0)
    extends RvaiPipeline(
      resetVector,
      separateDecodeExecute = true,
      separateWritebackStage = true,
      forwardInExecute = true
    )
