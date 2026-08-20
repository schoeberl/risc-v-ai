package riscvai

import chisel3._
import chisel3.util._

/** Synchronous cache data storage with one read port and one byte-masked write port. */
private[riscvai] class CacheDataMemoryIO(depth: Int) extends Bundle {
  private val addressBits = log2Ceil(depth)

  val readEnable = Input(Bool())
  val readAddress = Input(UInt(addressBits.W))
  val readData = Output(UInt(32.W))
  val writeEnable = Input(Bool())
  val writeAddress = Input(UInt(addressBits.W))
  val writeData = Input(UInt(32.W))
  val writeMask = Input(UInt(4.W))
}

private[riscvai] abstract class CacheDataMemory(depth: Int) extends Module {
  val io = IO(new CacheDataMemoryIO(depth))
}

/** Portable implementation used for simulation and FPGA memory inference. */
private[riscvai] class InferredCacheDataMemory(depth: Int)
    extends CacheDataMemory(depth) {

  private val memory = SyncReadMem(depth, Vec(4, UInt(8.W)))
  private val readBytes = memory.read(io.readAddress, io.readEnable)
  io.readData := readBytes.asUInt

  private val writeBytes = VecInit.tabulate(4) { lane =>
    io.writeData(8 * lane + 7, 8 * lane)
  }
  when(io.writeEnable) {
    memory.write(io.writeAddress, writeBytes, io.writeMask.asBools)
  }
}

/** ChipFoundry 4 KiB single-port synchronous Sky130 SRAM hard macro. */
private[riscvai] class ChipFoundrySram1024x32 extends BlackBox {
  override def desiredName: String = "CF_SRAM_1024x32"

  val io = IO(new Bundle {
    val DO = Output(UInt(32.W))
    val ScanOutCC = Output(Bool())
    val AD = Input(UInt(10.W))
    val BEN = Input(UInt(32.W))
    val CLKin = Input(Clock())
    val DI = Input(UInt(32.W))
    val EN = Input(Bool())
    val R_WB = Input(Bool())
    val ScanInCC = Input(Bool())
    val ScanInDL = Input(Bool())
    val ScanInDR = Input(Bool())
    val SM = Input(Bool())
    val TM = Input(Bool())
    val WLBI = Input(Bool())
    val WLOFF = Input(Bool())
    val vpwrac = Input(Bool())
    val vpwrpc = Input(Bool())
  })
}

/** ASIC cache storage backed by a CF_SRAM_1024x32. A smaller cache may use a
  * prefix of the macro; the default 4 KiB caches use all 1024 words.
  */
private[riscvai] class Sky130CacheDataMemory(depth: Int)
    extends CacheDataMemory(depth) {
  require(depth <= 1024, "CF_SRAM_1024x32 contains at most 1024 words")

  private val sram = Module(new ChipFoundrySram1024x32)
  sram.suggestName("sram")

  // The cache never consumes read data in a write cycle. Writes therefore take
  // priority when the generic 1R/1W cache interface requests both operations.
  private val writeSelected = io.writeEnable
  private val address = Mux(writeSelected, io.writeAddress, io.readAddress)

  sram.io.AD := address.pad(10)
  sram.io.BEN := FillInterleaved(8, io.writeMask)
  sram.io.CLKin := clock
  sram.io.DI := io.writeData
  sram.io.EN := io.readEnable || io.writeEnable
  sram.io.R_WB := !writeSelected
  sram.io.ScanInCC := false.B
  sram.io.ScanInDL := false.B
  sram.io.ScanInDR := false.B
  sram.io.SM := false.B
  sram.io.TM := false.B
  sram.io.WLBI := false.B
  sram.io.WLOFF := false.B
  // Default non-power-switched mode requires both controls tied to the supply.
  sram.io.vpwrac := true.B
  sram.io.vpwrpc := true.B
  io.readData := sram.io.DO
}

private[riscvai] object CacheDataMemory {
  def apply(depth: Int, useAsicSram: Boolean): CacheDataMemoryIO = {
    val memory: CacheDataMemory = if (useAsicSram) {
      Module(new Sky130CacheDataMemory(depth))
    } else {
      Module(new InferredCacheDataMemory(depth))
    }
    memory.io
  }
}
