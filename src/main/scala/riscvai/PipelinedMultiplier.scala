package riscvai

import chisel3._
import chisel3.util.Cat

/** Six-stage 32-bit multiplier built from four 16-bit partial products. */
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
  private val valid6 = RegInit(false.B)

  private val lhsMagnitude1 = Reg(UInt(32.W))
  private val rhsMagnitude1 = Reg(UInt(32.W))
  private val negative1 = Reg(Bool())
  private val highResultReg = Reg(Bool())

  private val partial00 = Reg(UInt(32.W))
  private val partial01 = Reg(UInt(32.W))
  private val partial10 = Reg(UInt(32.W))
  private val partial11 = Reg(UInt(32.W))
  private val negative2 = Reg(Bool())

  private val lowerSum = Reg(UInt(64.W))
  private val upperSum = Reg(UInt(64.W))
  private val negative3 = Reg(Bool())
  private val magnitude = Reg(UInt(64.W))
  private val negative4 = Reg(Bool())
  private val correctedLow = Reg(UInt(32.W))
  private val magnitudeHigh = Reg(UInt(32.W))
  private val carryToHigh = Reg(Bool())
  private val negative5 = Reg(Bool())
  private val result = Reg(UInt(32.W))

  private def twosComplement(value: UInt): UInt = (~value).asUInt + 1.U

  private val lhsNegative = io.lhsSigned && io.lhs(31)
  private val rhsNegative = io.rhsSigned && io.rhs(31)
  private val lhsMagnitude = Mux(lhsNegative, twosComplement(io.lhs), io.lhs)
  private val rhsMagnitude = Mux(rhsNegative, twosComplement(io.rhs), io.rhs)

  valid6 := valid5
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
    magnitude := lowerSum + upperSum
    negative4 := negative3
  }

  when(valid4) {
    correctedLow := Mux(
      negative4,
      twosComplement(magnitude(31, 0)),
      magnitude(31, 0)
    )
    magnitudeHigh := magnitude(63, 32)
    carryToHigh := magnitude(31, 0) === 0.U
    negative5 := negative4
  }

  when(valid5) {
    val correctedHigh = Mux(
      negative5,
      (~magnitudeHigh).asUInt + carryToHigh,
      magnitudeHigh
    )
    result := Mux(highResultReg, correctedHigh, correctedLow)
  }

  io.busy := valid1 || valid2 || valid3 || valid4 || valid5
  io.done := valid6
  io.result := result
}
