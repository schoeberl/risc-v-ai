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

/** A three-stage processor with fetch, decode/register-read/address, and
  * combined execute/memory/writeback stages.
  *
  * Stage 2 calculates the effective memory address early enough to start the
  * synchronous cache lookup. Stage 3 consumes load data directly, resolves
  * control transfers, and forwards load data into stage 2, eliminating the
  * ordinary load-use bubble.
  */
class RvaiThreeStagesExecuteMemory(resetVector: BigInt = 0)
    extends RvaiPipeline(
      resetVector,
      separateDecodeExecute = true,
      mergeExecuteMemory = true
    )

/** A two-stage processor with instruction/decode/execute in stage 1 and the
  * synchronous data-cache access plus writeback in stage 2.
  *
  * The instruction cache's synchronous address register is the fetch boundary,
  * so the core does not add a fetch/decode register of its own.
  */
class RvaiTwoStages(resetVector: BigInt = 0)
    extends RvaiPipeline(
      resetVector,
      separateDecodeExecute = false,
      executeFromInstructionPort = true
    )

/** A serialized multicycle processor with one instruction in flight. */
class RvaiMulticycle(resetVector: BigInt = 0)
    extends RvaiPipeline(
      resetVector,
      separateDecodeExecute = true,
      serializedInstructions = true
    )
