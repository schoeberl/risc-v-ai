package riscvai

/** A three-stage processor with fetch, combined decode/execute, and
  * memory/writeback stages.
  *
  * The instruction supplied for the current fetch PC is captured before decode,
  * giving synchronous instruction memory a full cycle to produce it. Decode,
  * register read, and execution share the following stage. A load or atomic
  * followed immediately by a dependent instruction incurs one stall cycle.
  */
class RvaiThreeStages(resetVector: BigInt = 0)
    extends RvaiPipeline(resetVector, separateDecodeExecute = false)

/** A three-stage comparison variant that predecodes register-source usage and
  * multiply/divide classification in fetch, before the fetch/decode-execute
  * register.
  *
  * Register-file reads, forwarding, detailed function decode, and execution
  * remain together in the second stage.
  */
class RvaiThreeStagesPredecode(resetVector: BigInt = 0)
    extends RvaiPipeline(
      resetVector,
      separateDecodeExecute = false,
      predecodeInFetch = true
    )
