package riscvai

import chisel3._
import chisel3.util._

/** A small single-cycle RV32I processor core.
  *
  * Instruction and data memories are external and combinationally read. The
  * core currently implements the RV32I integer ALU instructions, branches,
  * jumps, LUI/AUIPC, LW, and SW.
  */
class RiscVCore(resetVector: BigInt = 0) extends Module {
  val io = IO(new Bundle {
    val instructionAddress = Output(UInt(32.W))
    val instruction = Input(UInt(32.W))

    val dataAddress = Output(UInt(32.W))
    val dataReadData = Input(UInt(32.W))
    val dataWriteData = Output(UInt(32.W))
    val dataWriteEnable = Output(Bool())

    val illegalInstruction = Output(Bool())

    // A read-only debug port keeps architectural state observable in tests.
    val debugRegisterAddress = Input(UInt(5.W))
    val debugRegisterData = Output(UInt(32.W))
  })

  private val OpcodeLoad = "b0000011".U(7.W)
  private val OpcodeOpImm = "b0010011".U(7.W)
  private val OpcodeAuipc = "b0010111".U(7.W)
  private val OpcodeStore = "b0100011".U(7.W)
  private val OpcodeOp = "b0110011".U(7.W)
  private val OpcodeLui = "b0110111".U(7.W)
  private val OpcodeBranch = "b1100011".U(7.W)
  private val OpcodeJalr = "b1100111".U(7.W)
  private val OpcodeJal = "b1101111".U(7.W)

  private def signExtend(value: UInt, width: Int): UInt =
    Cat(Fill(32 - width, value(width - 1)), value)

  val pc = RegInit(resetVector.U(32.W))
  val registers = RegInit(VecInit(Seq.fill(32)(0.U(32.W))))

  val instruction = io.instruction
  val opcode = instruction(6, 0)
  val rd = instruction(11, 7)
  val funct3 = instruction(14, 12)
  val rs1 = instruction(19, 15)
  val rs2 = instruction(24, 20)
  val funct7 = instruction(31, 25)

  val rs1Value = Mux(rs1 === 0.U, 0.U, registers(rs1))
  val rs2Value = Mux(rs2 === 0.U, 0.U, registers(rs2))

  val immediateI = signExtend(instruction(31, 20), 12)
  val immediateS = signExtend(Cat(instruction(31, 25), instruction(11, 7)), 12)
  val immediateB = signExtend(
    Cat(instruction(31), instruction(7), instruction(30, 25), instruction(11, 8), 0.U(1.W)),
    13
  )
  val immediateU = Cat(instruction(31, 12), 0.U(12.W))
  val immediateJ = signExtend(
    Cat(instruction(31), instruction(19, 12), instruction(20), instruction(30, 21), 0.U(1.W)),
    21
  )

  val nextPc = WireDefault(pc + 4.U)
  val writeEnable = WireDefault(false.B)
  val writeData = WireDefault(0.U(32.W))
  val illegal = WireDefault(true.B)

  io.instructionAddress := pc
  io.dataAddress := rs1Value + immediateS
  io.dataWriteData := rs2Value
  io.dataWriteEnable := false.B
  io.illegalInstruction := illegal
  io.debugRegisterData := Mux(
    io.debugRegisterAddress === 0.U,
    0.U,
    registers(io.debugRegisterAddress)
  )

  switch(opcode) {
    is(OpcodeLui) {
      illegal := false.B
      writeEnable := true.B
      writeData := immediateU
    }

    is(OpcodeAuipc) {
      illegal := false.B
      writeEnable := true.B
      writeData := pc + immediateU
    }

    is(OpcodeJal) {
      illegal := false.B
      writeEnable := true.B
      writeData := pc + 4.U
      nextPc := pc + immediateJ
    }

    is(OpcodeJalr) {
      when(funct3 === "b000".U) {
        illegal := false.B
        writeEnable := true.B
        writeData := pc + 4.U
        nextPc := (rs1Value + immediateI) & "hfffffffe".U
      }.otherwise {
        illegal := true.B
      }
    }

    is(OpcodeBranch) {
      val taken = WireDefault(false.B)
      val validBranch = funct3 === "b000".U || funct3 === "b001".U ||
        funct3 === "b100".U || funct3 === "b101".U ||
        funct3 === "b110".U || funct3 === "b111".U
      illegal := !validBranch
      switch(funct3) {
        is("b000".U) { taken := rs1Value === rs2Value } // BEQ
        is("b001".U) { taken := rs1Value =/= rs2Value } // BNE
        is("b100".U) { taken := rs1Value.asSInt < rs2Value.asSInt } // BLT
        is("b101".U) { taken := rs1Value.asSInt >= rs2Value.asSInt } // BGE
        is("b110".U) { taken := rs1Value < rs2Value } // BLTU
        is("b111".U) { taken := rs1Value >= rs2Value } // BGEU
      }
      when(taken) {
        nextPc := pc + immediateB
      }
    }

    is(OpcodeLoad) {
      io.dataAddress := rs1Value + immediateI
      when(funct3 === "b010".U) { // LW
        illegal := false.B
        writeEnable := true.B
        writeData := io.dataReadData
      }.otherwise {
        illegal := true.B
      }
    }

    is(OpcodeStore) {
      io.dataAddress := rs1Value + immediateS
      when(funct3 === "b010".U) { // SW
        illegal := false.B
        io.dataWriteEnable := true.B
      }.otherwise {
        illegal := true.B
      }
    }

    is(OpcodeOpImm) {
      illegal := false.B
      writeEnable := true.B
      switch(funct3) {
        is("b000".U) { writeData := rs1Value + immediateI } // ADDI
        is("b010".U) { writeData := (rs1Value.asSInt < immediateI.asSInt).asUInt } // SLTI
        is("b011".U) { writeData := rs1Value < immediateI } // SLTIU
        is("b100".U) { writeData := rs1Value ^ immediateI } // XORI
        is("b110".U) { writeData := rs1Value | immediateI } // ORI
        is("b111".U) { writeData := rs1Value & immediateI } // ANDI
        is("b001".U) { // SLLI
          when(funct7 === "b0000000".U) {
            writeData := rs1Value << instruction(24, 20)
          }.otherwise { illegal := true.B }
        }
        is("b101".U) { // SRLI/SRAI
          when(funct7 === "b0000000".U) {
            writeData := rs1Value >> instruction(24, 20)
          }.elsewhen(funct7 === "b0100000".U) {
            writeData := (rs1Value.asSInt >> instruction(24, 20)).asUInt
          }.otherwise { illegal := true.B }
        }
      }
    }

    is(OpcodeOp) {
      illegal := false.B
      writeEnable := true.B
      switch(funct3) {
        is("b000".U) {
          when(funct7 === "b0000000".U) { writeData := rs1Value + rs2Value } // ADD
            .elsewhen(funct7 === "b0100000".U) { writeData := rs1Value - rs2Value } // SUB
            .otherwise { illegal := true.B }
        }
        is("b001".U) { writeData := rs1Value << rs2Value(4, 0) } // SLL
        is("b010".U) { writeData := (rs1Value.asSInt < rs2Value.asSInt).asUInt } // SLT
        is("b011".U) { writeData := rs1Value < rs2Value } // SLTU
        is("b100".U) { writeData := rs1Value ^ rs2Value } // XOR
        is("b101".U) {
          when(funct7 === "b0000000".U) { writeData := rs1Value >> rs2Value(4, 0) } // SRL
            .elsewhen(funct7 === "b0100000".U) {
              writeData := (rs1Value.asSInt >> rs2Value(4, 0)).asUInt // SRA
            }.otherwise { illegal := true.B }
        }
        is("b110".U) { writeData := rs1Value | rs2Value } // OR
        is("b111".U) { writeData := rs1Value & rs2Value } // AND
      }
    }
  }

  when(writeEnable && !illegal && rd =/= 0.U) {
    registers(rd) := writeData
  }
  registers(0) := 0.U
  pc := nextPc
}
