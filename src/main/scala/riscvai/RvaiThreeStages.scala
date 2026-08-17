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
