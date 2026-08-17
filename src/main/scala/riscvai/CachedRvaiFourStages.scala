package riscvai

import chisel3._

/** Pipelined core with private 1 KiB instruction/data caches and one shared bus. */
class CachedRvaiFourStages(
    resetVector: BigInt = 0,
    cacheBytes: Int = 1024,
    lineBytes: Int = 16,
    useSky130Sram: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val memoryRequest = Output(Bool())
    val memoryWrite = Output(Bool())
    val memoryAddress = Output(UInt(32.W))
    val memoryWriteData = Output(UInt(32.W))
    val memoryWriteMask = Output(UInt(4.W))
    val memoryReady = Input(Bool())
    val memoryReadData = Input(UInt(32.W))

    val illegalInstruction = Output(Bool())
    val pipelineStall = Output(Bool())
    val retiredValid = Output(Bool())
    val retiredPc = Output(UInt(32.W))
    val retiredInstruction = Output(UInt(32.W))
    val debugRegisterAddress = Input(UInt(5.W))
    val debugRegisterData = Output(UInt(32.W))
  })

  private val core = Module(new RvaiFourStages(resetVector))
  private val instructionCache =
    Module(new InstructionCache(cacheBytes, lineBytes, useSky130Sram))
  private val dataCache = Module(new DataCache(cacheBytes, lineBytes, useSky130Sram))
  private val arbiter = Module(new CacheArbiter)

  instructionCache.io.cpuRequest := true.B
  instructionCache.io.cpuNextAddress := core.io.instructionNextAddress
  instructionCache.io.invalidate := core.io.instructionCacheInvalidate
  core.io.instruction := instructionCache.io.cpuData
  core.io.instructionValid := instructionCache.io.cpuReady &&
    !core.io.instructionCacheInvalidate

  val dataRequest = core.io.dataReadEnable || core.io.dataWriteEnable
  dataCache.io.cpuRequest := dataRequest
  dataCache.io.cpuRead := core.io.dataReadEnable
  dataCache.io.cpuWrite := core.io.dataWriteEnable
  dataCache.io.cpuAddress := core.io.dataAddress
  dataCache.io.cpuNextAddress := core.io.dataNextAddress
  dataCache.io.cpuTagNextAddress := core.io.dataTagNextAddress
  dataCache.io.cpuWriteData := core.io.dataWriteData
  dataCache.io.cpuWriteMask := core.io.dataWriteMask
  core.io.dataReadData := dataCache.io.cpuReadData
  core.io.dataAtomicReadData := dataCache.io.cpuAtomicReadData

  val dataAdvance = !dataRequest || dataCache.io.cpuReady
  instructionCache.io.cpuAccept := core.io.instructionValid && dataAdvance
  dataCache.io.cpuAccept := dataAdvance && dataRequest
  core.io.memoryStall := !dataAdvance

  arbiter.io.instruction <> instructionCache.io.memory
  arbiter.io.data <> dataCache.io.memory
  arbiter.io.memoryReady := io.memoryReady
  arbiter.io.memoryReadData := io.memoryReadData
  io.memoryRequest := arbiter.io.memoryRequest
  io.memoryWrite := arbiter.io.memoryWrite
  io.memoryAddress := arbiter.io.memoryAddress
  io.memoryWriteData := arbiter.io.memoryWriteData
  io.memoryWriteMask := arbiter.io.memoryWriteMask

  io.illegalInstruction := core.io.illegalInstruction
  io.pipelineStall := core.io.pipelineStall
  io.retiredValid := core.io.retiredValid
  io.retiredPc := core.io.retiredPc
  io.retiredInstruction := core.io.retiredInstruction
  core.io.debugRegisterAddress := io.debugRegisterAddress
  io.debugRegisterData := core.io.debugRegisterData
}

/** Sky130 implementation with one installed 1 KiB OpenRAM macro per cache. */
class Sky130CachedRvaiFourStages(resetVector: BigInt = 0)
    extends CachedRvaiFourStages(
      resetVector = resetVector,
      cacheBytes = 1024,
      lineBytes = 16,
      useSky130Sram = true
    )
