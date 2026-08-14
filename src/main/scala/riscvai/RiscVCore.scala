package riscvai

import chisel3._
import chisel3.util._

/** A small single-cycle RV32IM processor core.
  *
  * Instruction and data memories are external and combinationally read. The
  * core implements the RV32I integer instructions and the RV32M extension.
  */
class RiscVCore(resetVector: BigInt = 0) extends Module {
  val io = IO(new Bundle {
    val instructionAddress = Output(UInt(32.W))
    val instruction = Input(UInt(32.W))

    val dataAddress = Output(UInt(32.W))
    val dataReadData = Input(UInt(32.W))
    val dataWriteData = Output(UInt(32.W))
    val dataWriteEnable = Output(Bool())
    val dataWriteMask = Output(UInt(4.W))

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

  val unsignedProduct = rs1Value * rs2Value
  val signedProduct = rs1Value.asSInt * rs2Value.asSInt
  val signedUnsignedProduct = rs1Value.asSInt * Cat(0.U(1.W), rs2Value).asSInt
  val signedDivideOverflow = rs1Value === "h80000000".U && rs2Value === "hffffffff".U
  val signedQuotient = (rs1Value.asSInt / rs2Value.asSInt).asUInt
  val signedRemainder = (rs1Value.asSInt % rs2Value.asSInt).asUInt

  io.instructionAddress := pc
  io.dataAddress := 0.U
  io.dataWriteData := 0.U
  io.dataWriteEnable := false.B
  io.dataWriteMask := 0.U
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
      val address = rs1Value + immediateI
      val byteShift = Cat(address(1, 0), 0.U(3.W))
      val shiftedReadData = io.dataReadData >> byteShift
      val loadedByte = shiftedReadData(7, 0)
      val loadedHalfword = shiftedReadData(15, 0)
      val validWidth = funct3 === "b000".U || funct3 === "b001".U ||
        funct3 === "b010".U || funct3 === "b100".U || funct3 === "b101".U
      val aligned = MuxLookup(funct3, true.B)(Seq(
        "b001".U -> !address(0),
        "b010".U -> (address(1, 0) === 0.U),
        "b101".U -> !address(0)
      ))

      io.dataAddress := address & "hfffffffc".U
      when(validWidth && aligned) {
        illegal := false.B
        writeEnable := true.B
        switch(funct3) {
          is("b000".U) { writeData := Cat(Fill(24, loadedByte(7)), loadedByte) } // LB
          is("b001".U) { writeData := Cat(Fill(16, loadedHalfword(15)), loadedHalfword) } // LH
          is("b010".U) { writeData := io.dataReadData } // LW
          is("b100".U) { writeData := Cat(0.U(24.W), loadedByte) } // LBU
          is("b101".U) { writeData := Cat(0.U(16.W), loadedHalfword) } // LHU
        }
      }
    }

    is(OpcodeStore) {
      val address = rs1Value + immediateS
      val byteShift = Cat(address(1, 0), 0.U(3.W))
      val laneMask = MuxLookup(funct3, 0.U(4.W))(Seq(
        "b000".U -> 1.U(4.W),
        "b001".U -> 3.U(4.W),
        "b010".U -> 15.U(4.W)
      ))
      val validWidth = funct3 === "b000".U || funct3 === "b001".U || funct3 === "b010".U
      val aligned = MuxLookup(funct3, true.B)(Seq(
        "b001".U -> !address(0),
        "b010".U -> (address(1, 0) === 0.U)
      ))

      io.dataAddress := address & "hfffffffc".U
      io.dataWriteData := rs2Value << byteShift
      when(validWidth && aligned) {
        illegal := false.B
        io.dataWriteEnable := true.B
        io.dataWriteMask := (laneMask << address(1, 0))(3, 0)
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
      when(funct7 === "b0000001".U) {
        switch(funct3) {
          is("b000".U) { writeData := unsignedProduct(31, 0) } // MUL
          is("b001".U) { writeData := signedProduct.asUInt(63, 32) } // MULH
          is("b010".U) { writeData := signedUnsignedProduct.asUInt(63, 32) } // MULHSU
          is("b011".U) { writeData := unsignedProduct(63, 32) } // MULHU
          is("b100".U) { // DIV
            writeData := Mux(
              rs2Value === 0.U,
              "hffffffff".U,
              Mux(signedDivideOverflow, "h80000000".U, signedQuotient)
            )
          }
          is("b101".U) { // DIVU
            writeData := Mux(rs2Value === 0.U, "hffffffff".U, rs1Value / rs2Value)
          }
          is("b110".U) { // REM
            writeData := Mux(
              rs2Value === 0.U,
              rs1Value,
              Mux(signedDivideOverflow, 0.U, signedRemainder)
            )
          }
          is("b111".U) { // REMU
            writeData := Mux(rs2Value === 0.U, rs1Value, rs1Value % rs2Value)
          }
        }
      }.otherwise {
        switch(funct3) {
          is("b000".U) {
            when(funct7 === "b0000000".U) { writeData := rs1Value + rs2Value } // ADD
              .elsewhen(funct7 === "b0100000".U) { writeData := rs1Value - rs2Value } // SUB
              .otherwise { illegal := true.B }
          }
          is("b001".U) {
            writeData := rs1Value << rs2Value(4, 0) // SLL
            illegal := funct7 =/= "b0000000".U
          }
          is("b010".U) {
            writeData := (rs1Value.asSInt < rs2Value.asSInt).asUInt // SLT
            illegal := funct7 =/= "b0000000".U
          }
          is("b011".U) {
            writeData := rs1Value < rs2Value // SLTU
            illegal := funct7 =/= "b0000000".U
          }
          is("b100".U) {
            writeData := rs1Value ^ rs2Value // XOR
            illegal := funct7 =/= "b0000000".U
          }
          is("b101".U) {
            when(funct7 === "b0000000".U) { writeData := rs1Value >> rs2Value(4, 0) } // SRL
              .elsewhen(funct7 === "b0100000".U) {
                writeData := (rs1Value.asSInt >> rs2Value(4, 0)).asUInt // SRA
              }.otherwise { illegal := true.B }
          }
          is("b110".U) {
            writeData := rs1Value | rs2Value // OR
            illegal := funct7 =/= "b0000000".U
          }
          is("b111".U) {
            writeData := rs1Value & rs2Value // AND
            illegal := funct7 =/= "b0000000".U
          }
        }
      }
    }
  }

  when(writeEnable && !illegal && rd =/= 0.U) {
    registers(rd) := writeData
  }
  registers(0) := 0.U
  pc := nextPc
}
