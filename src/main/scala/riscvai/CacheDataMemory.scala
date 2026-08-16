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

/** Installed OpenRAM 1 KiB Sky130 SRAM hard macro. */
private[riscvai] class Sky130Sram1Kbyte extends BlackBox {
  override def desiredName: String = "sky130_sram_1kbyte_1rw1r_32x256_8"

  val io = IO(new Bundle {
    val clk0 = Input(Clock())
    val csb0 = Input(Bool())
    val web0 = Input(Bool())
    val wmask0 = Input(UInt(4.W))
    val addr0 = Input(UInt(8.W))
    val din0 = Input(UInt(32.W))
    val dout0 = Output(UInt(32.W))
    val clk1 = Input(Clock())
    val csb1 = Input(Bool())
    val addr1 = Input(UInt(8.W))
    val dout1 = Output(UInt(32.W))
  })
}

/** ASIC implementation backed by the installed 256x32 Sky130 SRAM macro. */
private[riscvai] class Sky130CacheDataMemory(depth: Int)
    extends CacheDataMemory(depth) {
  require(depth == 256, "the installed Sky130 cache SRAM contains exactly 256 words")
  private val sram = Module(new Sky130Sram1Kbyte)
  sram.suggestName("sram")

  sram.io.clk0 := clock
  sram.io.csb0 := !io.writeEnable
  sram.io.web0 := false.B
  sram.io.wmask0 := io.writeMask
  sram.io.addr0 := io.writeAddress
  sram.io.din0 := io.writeData

  sram.io.clk1 := clock
  sram.io.csb1 := !io.readEnable
  sram.io.addr1 := io.readAddress
  io.readData := sram.io.dout1
}

private[riscvai] object CacheDataMemory {
  def apply(depth: Int, useSky130Sram: Boolean): CacheDataMemoryIO = {
    val memory: CacheDataMemory = if (useSky130Sram) {
      Module(new Sky130CacheDataMemory(depth))
    } else {
      Module(new InferredCacheDataMemory(depth))
    }
    memory.io
  }
}
