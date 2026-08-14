package riscvai

import chisel3._

/** A small example circuit that adds two unsigned values without truncating carry-out. */
class Adder(val width: Int = 8) extends Module {
  require(width > 0, "width must be positive")

  val io = IO(new Bundle {
    val a = Input(UInt(width.W))
    val b = Input(UInt(width.W))
    val sum = Output(UInt((width + 1).W))
  })

  io.sum := io.a +& io.b
}
