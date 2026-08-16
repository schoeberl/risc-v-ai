package riscvai

import chisel3._
import chisel3.util.Cat

/** Five-stage 32-bit multiplier built from four 16-bit partial products. */
private class PipelinedMultiplier extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val lhsSigned = Input(Bool())
    val rhsSigned = Input(Bool())
    val highResult = Input(Bool())
    val lhs = Input(UInt(32.W))
    val rhs = Input(UInt(32.W))

    val busy = Output(Bool())
    val done = Output(Bool())
    val result = Output(UInt(32.W))
  })

  private val valid1 = RegInit(false.B)
  private val valid2 = RegInit(false.B)
  private val valid3 = RegInit(false.B)
  private val valid4 = RegInit(false.B)
  private val valid5 = RegInit(false.B)

  private val lhsMagnitude1 = RegInit(0.U(32.W))
  private val rhsMagnitude1 = RegInit(0.U(32.W))
  private val negative1 = RegInit(false.B)
  private val highResultReg = RegInit(false.B)

  private val partial00 = RegInit(0.U(32.W))
  private val partial01 = RegInit(0.U(32.W))
  private val partial10 = RegInit(0.U(32.W))
  private val partial11 = RegInit(0.U(32.W))
  private val negative2 = RegInit(false.B)

  private val lowerSum = RegInit(0.U(64.W))
  private val upperSum = RegInit(0.U(64.W))
  private val negative3 = RegInit(false.B)
  private val product = RegInit(0.U(64.W))
  private val result = RegInit(0.U(32.W))

  private def twosComplement(value: UInt): UInt = (~value).asUInt + 1.U

  private val lhsNegative = io.lhsSigned && io.lhs(31)
  private val rhsNegative = io.rhsSigned && io.rhs(31)
  private val lhsMagnitude = Mux(lhsNegative, twosComplement(io.lhs), io.lhs)
  private val rhsMagnitude = Mux(rhsNegative, twosComplement(io.rhs), io.rhs)

  valid5 := valid4
  valid4 := valid3
  valid3 := valid2
  valid2 := valid1
  valid1 := io.start && !io.busy

  when(io.start && !io.busy) {
    lhsMagnitude1 := lhsMagnitude
    rhsMagnitude1 := rhsMagnitude
    negative1 := lhsNegative ^ rhsNegative
    highResultReg := io.highResult
  }

  when(valid1) {
    partial00 := lhsMagnitude1(15, 0) * rhsMagnitude1(15, 0)
    partial01 := lhsMagnitude1(15, 0) * rhsMagnitude1(31, 16)
    partial10 := lhsMagnitude1(31, 16) * rhsMagnitude1(15, 0)
    partial11 := lhsMagnitude1(31, 16) * rhsMagnitude1(31, 16)
    negative2 := negative1
  }

  when(valid2) {
    lowerSum := Cat(0.U(32.W), partial00) + Cat(0.U(16.W), partial01, 0.U(16.W))
    upperSum := Cat(0.U(16.W), partial10, 0.U(16.W)) + Cat(partial11, 0.U(32.W))
    negative3 := negative2
  }

  when(valid3) {
    val magnitude = lowerSum + upperSum
    product := Mux(negative3, twosComplement(magnitude), magnitude)
  }

  when(valid4) {
    result := Mux(highResultReg, product(63, 32), product(31, 0))
  }

  io.busy := valid1 || valid2 || valid3 || valid4
  io.done := valid5
  io.result := result
}
