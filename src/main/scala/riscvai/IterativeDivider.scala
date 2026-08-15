package riscvai

import chisel3._
import chisel3.util.Cat

/** Shared 32-cycle restoring divider for RV32M quotient and remainder operations. */
private class IterativeDivider extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val signed = Input(Bool())
    val dividend = Input(UInt(32.W))
    val divisor = Input(UInt(32.W))

    val busy = Output(Bool())
    val done = Output(Bool())
    val quotient = Output(UInt(32.W))
    val remainder = Output(UInt(32.W))
  })

  private val busy = RegInit(false.B)
  private val done = RegInit(false.B)
  private val count = RegInit(0.U(5.W))
  private val divisor = RegInit(0.U(32.W))
  private val quotient = RegInit(0.U(32.W))
  private val remainder = RegInit(0.U(33.W))
  private val negateQuotient = RegInit(false.B)
  private val negateRemainder = RegInit(false.B)
  private val divisorZero = RegInit(false.B)
  private val quotientResult = RegInit(0.U(32.W))
  private val remainderResult = RegInit(0.U(32.W))

  private def twosComplement(value: UInt): UInt = (~value).asUInt + 1.U

  done := false.B

  when(io.start && !busy) {
    val dividendNegative = io.signed && io.dividend(31)
    val divisorNegative = io.signed && io.divisor(31)
    busy := true.B
    count := 0.U
    divisor := Mux(divisorNegative, twosComplement(io.divisor), io.divisor)
    quotient := Mux(dividendNegative, twosComplement(io.dividend), io.dividend)
    remainder := 0.U
    negateQuotient := dividendNegative ^ divisorNegative
    negateRemainder := dividendNegative
    divisorZero := io.divisor === 0.U
  }.elsewhen(busy) {
    val shiftedRemainder = Cat(remainder(31, 0), quotient(31))
    val subtract = shiftedRemainder >= Cat(0.U(1.W), divisor)
    val nextRemainder = Mux(
      subtract,
      shiftedRemainder - Cat(0.U(1.W), divisor),
      shiftedRemainder
    )
    val nextQuotient = Cat(quotient(30, 0), subtract)

    remainder := nextRemainder
    quotient := nextQuotient

    when(count === 31.U) {
      busy := false.B
      done := true.B
      quotientResult := Mux(
        divisorZero,
        "hffffffff".U,
        Mux(negateQuotient, twosComplement(nextQuotient), nextQuotient)
      )
      remainderResult := Mux(
        negateRemainder,
        twosComplement(nextRemainder(31, 0)),
        nextRemainder(31, 0)
      )
    }.otherwise {
      count := count + 1.U
    }
  }

  io.busy := busy
  io.done := done
  io.quotient := quotientResult
  io.remainder := remainderResult
}
