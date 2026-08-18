package riscvai

import chisel3._
import chisel3.util._

/** Synchronous cache-tag storage; writes take priority on the physical port. */
private[riscvai] class CacheTagMemoryIO(depth: Int, width: Int) extends Bundle {
  private val addressBits = log2Ceil(depth)

  val readEnable = Input(Bool())
  val readAddress = Input(UInt(addressBits.W))
  val readData = Output(UInt(width.W))
  val writeEnable = Input(Bool())
  val writeAddress = Input(UInt(addressBits.W))
  val writeData = Input(UInt(width.W))
}

private[riscvai] abstract class CacheTagMemory(depth: Int, width: Int)
    extends Module {
  val io = IO(new CacheTagMemoryIO(depth, width))
}

/** Portable tag memory used by simulation and FPGA builds. */
private[riscvai] class InferredCacheTagMemory(depth: Int, width: Int)
    extends CacheTagMemory(depth, width) {
  private val memory = SyncReadMem(depth, UInt(width.W))
  io.readData := memory.read(io.readAddress, io.readEnable)
  when(io.writeEnable) {
    memory.write(io.writeAddress, io.writeData)
  }
}

/** Sky130 tag memory backed by a complete CF_SRAM_1024x32 macro. */
private[riscvai] class Sky130CacheTagMemory(depth: Int, width: Int)
    extends CacheTagMemory(depth, width) {
  require(depth <= 1024, "CF_SRAM_1024x32 contains at most 1024 words")
  require(width <= 32, "CF_SRAM_1024x32 stores at most 32 tag bits")

  private val sram = Module(new ChipFoundrySram1024x32)
  sram.suggestName("sram")

  // The caches ignore a read result in the refill cycle, so tag writes may take
  // priority on the macro's single port.
  private val address = Mux(io.writeEnable, io.writeAddress, io.readAddress)
  sram.io.AD := address.pad(10)
  sram.io.BEN := Fill(32, io.writeEnable)
  sram.io.CLKin := clock
  sram.io.DI := io.writeData.pad(32)
  sram.io.EN := io.readEnable || io.writeEnable
  sram.io.R_WB := !io.writeEnable
  sram.io.ScanInCC := false.B
  sram.io.ScanInDL := false.B
  sram.io.ScanInDR := false.B
  sram.io.SM := false.B
  sram.io.TM := false.B
  sram.io.WLBI := false.B
  sram.io.WLOFF := false.B
  sram.io.vpwrac := true.B
  sram.io.vpwrpc := true.B
  io.readData := sram.io.DO(width - 1, 0)
}

private[riscvai] object CacheTagMemory {
  def apply(depth: Int, width: Int, useAsicSram: Boolean): CacheTagMemoryIO = {
    val memory: CacheTagMemory = if (useAsicSram) {
      Module(new Sky130CacheTagMemory(depth, width))
    } else {
      Module(new InferredCacheTagMemory(depth, width))
    }
    memory.io
  }
}
