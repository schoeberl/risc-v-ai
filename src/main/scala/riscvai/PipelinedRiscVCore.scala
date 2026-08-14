package riscvai

import chisel3._
import chisel3.util._

private class FetchDecodeExecute extends Bundle {
  val valid = Bool()
  val pc = UInt(32.W)
  val instruction = UInt(32.W)
}

private class ExecuteMemoryWriteback extends Bundle {
  val valid = Bool()
  val pc = UInt(32.W)
  val instruction = UInt(32.W)
  val rd = UInt(5.W)
  val result = UInt(32.W)
  val address = UInt(32.W)
  val storeData = UInt(32.W)
  val registerWrite = Bool()
  val memoryRead = Bool()
  val memoryWrite = Bool()
  val illegal = Bool()
}

/** A three-stage, single-issue RV32I processor.
  *
  * The stages are fetch, decode/execute, and memory/writeback. Results from the
  * final stage are forwarded to decode/execute. A load followed immediately by
  * a dependent instruction incurs one stall cycle, avoiding a data-memory to
  * ALU combinational path.
  */
class PipelinedRiscVCore(resetVector: BigInt = 0) extends Module {
  val io = IO(new Bundle {
    val instructionAddress = Output(UInt(32.W))
    val instruction = Input(UInt(32.W))

    val dataAddress = Output(UInt(32.W))
    val dataReadData = Input(UInt(32.W))
    val dataWriteData = Output(UInt(32.W))
    val dataWriteEnable = Output(Bool())

    val illegalInstruction = Output(Bool())
    val pipelineStall = Output(Bool())

    val retiredValid = Output(Bool())
    val retiredPc = Output(UInt(32.W))
    val retiredInstruction = Output(UInt(32.W))

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

  val fetchPc = RegInit(resetVector.U(32.W))
  private val fetchDecodeExecute = RegInit(0.U.asTypeOf(new FetchDecodeExecute))
  private val executeMemoryWriteback = RegInit(0.U.asTypeOf(new ExecuteMemoryWriteback))
  val registers = RegInit(VecInit(Seq.fill(32)(0.U(32.W))))

  val writebackData = Mux(
    executeMemoryWriteback.memoryRead,
    io.dataReadData,
    executeMemoryWriteback.result
  )
  val writebackActive = executeMemoryWriteback.valid &&
    executeMemoryWriteback.registerWrite && !executeMemoryWriteback.illegal &&
    executeMemoryWriteback.rd =/= 0.U

  when(writebackActive) {
    registers(executeMemoryWriteback.rd) := writebackData
  }
  registers(0) := 0.U

  io.instructionAddress := fetchPc
  io.dataAddress := executeMemoryWriteback.address
  io.dataWriteData := executeMemoryWriteback.storeData
  io.dataWriteEnable := executeMemoryWriteback.valid &&
    executeMemoryWriteback.memoryWrite && !executeMemoryWriteback.illegal
  io.illegalInstruction := executeMemoryWriteback.valid && executeMemoryWriteback.illegal
  io.retiredValid := executeMemoryWriteback.valid
  io.retiredPc := executeMemoryWriteback.pc
  io.retiredInstruction := executeMemoryWriteback.instruction
  io.debugRegisterData := Mux(
    io.debugRegisterAddress === 0.U,
    0.U,
    registers(io.debugRegisterAddress)
  )

  val instruction = fetchDecodeExecute.instruction
  val opcode = instruction(6, 0)
  val rd = instruction(11, 7)
  val funct3 = instruction(14, 12)
  val rs1 = instruction(19, 15)
  val rs2 = instruction(24, 20)
  val funct7 = instruction(31, 25)

  val usesRs1 = opcode === OpcodeJalr || opcode === OpcodeBranch ||
    opcode === OpcodeLoad || opcode === OpcodeStore ||
    opcode === OpcodeOpImm || opcode === OpcodeOp
  val usesRs2 = opcode === OpcodeBranch || opcode === OpcodeStore || opcode === OpcodeOp

  val loadUseHazard = fetchDecodeExecute.valid &&
    executeMemoryWriteback.valid && executeMemoryWriteback.memoryRead &&
    executeMemoryWriteback.rd =/= 0.U &&
    ((usesRs1 && rs1 === executeMemoryWriteback.rd) ||
      (usesRs2 && rs2 === executeMemoryWriteback.rd))
  io.pipelineStall := loadUseHazard

  private def readRegister(address: UInt): UInt =
    Mux(
      address === 0.U,
      0.U,
      Mux(
        writebackActive && executeMemoryWriteback.rd === address,
        writebackData,
        registers(address)
      )
    )

  val rs1Value = readRegister(rs1)
  val rs2Value = readRegister(rs2)

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

  val result = WireDefault(0.U(32.W))
  val address = WireDefault(0.U(32.W))
  val storeData = WireDefault(rs2Value)
  val registerWrite = WireDefault(false.B)
  val memoryRead = WireDefault(false.B)
  val memoryWrite = WireDefault(false.B)
  val illegal = WireDefault(true.B)
  val redirect = WireDefault(false.B)
  val redirectTarget = WireDefault(0.U(32.W))

  switch(opcode) {
    is(OpcodeLui) {
      illegal := false.B
      registerWrite := true.B
      result := immediateU
    }

    is(OpcodeAuipc) {
      illegal := false.B
      registerWrite := true.B
      result := fetchDecodeExecute.pc + immediateU
    }

    is(OpcodeJal) {
      illegal := false.B
      registerWrite := true.B
      result := fetchDecodeExecute.pc + 4.U
      redirect := true.B
      redirectTarget := fetchDecodeExecute.pc + immediateJ
    }

    is(OpcodeJalr) {
      when(funct3 === "b000".U) {
        illegal := false.B
        registerWrite := true.B
        result := fetchDecodeExecute.pc + 4.U
        redirect := true.B
        redirectTarget := (rs1Value + immediateI) & "hfffffffe".U
      }
    }

    is(OpcodeBranch) {
      val validBranch = funct3 === "b000".U || funct3 === "b001".U ||
        funct3 === "b100".U || funct3 === "b101".U ||
        funct3 === "b110".U || funct3 === "b111".U
      val branchTaken = WireDefault(false.B)
      illegal := !validBranch
      switch(funct3) {
        is("b000".U) { branchTaken := rs1Value === rs2Value }
        is("b001".U) { branchTaken := rs1Value =/= rs2Value }
        is("b100".U) { branchTaken := rs1Value.asSInt < rs2Value.asSInt }
        is("b101".U) { branchTaken := rs1Value.asSInt >= rs2Value.asSInt }
        is("b110".U) { branchTaken := rs1Value < rs2Value }
        is("b111".U) { branchTaken := rs1Value >= rs2Value }
      }
      when(branchTaken && validBranch) {
        redirect := true.B
        redirectTarget := fetchDecodeExecute.pc + immediateB
      }
    }

    is(OpcodeLoad) {
      address := rs1Value + immediateI
      when(funct3 === "b010".U) {
        illegal := false.B
        registerWrite := true.B
        memoryRead := true.B
      }
    }

    is(OpcodeStore) {
      address := rs1Value + immediateS
      when(funct3 === "b010".U) {
        illegal := false.B
        memoryWrite := true.B
      }
    }

    is(OpcodeOpImm) {
      illegal := false.B
      registerWrite := true.B
      switch(funct3) {
        is("b000".U) { result := rs1Value + immediateI }
        is("b010".U) { result := (rs1Value.asSInt < immediateI.asSInt).asUInt }
        is("b011".U) { result := rs1Value < immediateI }
        is("b100".U) { result := rs1Value ^ immediateI }
        is("b110".U) { result := rs1Value | immediateI }
        is("b111".U) { result := rs1Value & immediateI }
        is("b001".U) {
          when(funct7 === "b0000000".U) {
            result := rs1Value << instruction(24, 20)
          }.otherwise {
            illegal := true.B
          }
        }
        is("b101".U) {
          when(funct7 === "b0000000".U) {
            result := rs1Value >> instruction(24, 20)
          }.elsewhen(funct7 === "b0100000".U) {
            result := (rs1Value.asSInt >> instruction(24, 20)).asUInt
          }.otherwise {
            illegal := true.B
          }
        }
      }
    }

    is(OpcodeOp) {
      illegal := false.B
      registerWrite := true.B
      switch(funct3) {
        is("b000".U) {
          when(funct7 === "b0000000".U) {
            result := rs1Value + rs2Value
          }.elsewhen(funct7 === "b0100000".U) {
            result := rs1Value - rs2Value
          }.otherwise {
            illegal := true.B
          }
        }
        is("b001".U) {
          result := rs1Value << rs2Value(4, 0)
          illegal := funct7 =/= "b0000000".U
        }
        is("b010".U) {
          result := (rs1Value.asSInt < rs2Value.asSInt).asUInt
          illegal := funct7 =/= "b0000000".U
        }
        is("b011".U) {
          result := rs1Value < rs2Value
          illegal := funct7 =/= "b0000000".U
        }
        is("b100".U) {
          result := rs1Value ^ rs2Value
          illegal := funct7 =/= "b0000000".U
        }
        is("b101".U) {
          when(funct7 === "b0000000".U) {
            result := rs1Value >> rs2Value(4, 0)
          }.elsewhen(funct7 === "b0100000".U) {
            result := (rs1Value.asSInt >> rs2Value(4, 0)).asUInt
          }.otherwise {
            illegal := true.B
          }
        }
        is("b110".U) {
          result := rs1Value | rs2Value
          illegal := funct7 =/= "b0000000".U
        }
        is("b111".U) {
          result := rs1Value & rs2Value
          illegal := funct7 =/= "b0000000".U
        }
      }
    }
  }

  when(loadUseHazard) {
    // Let the load retire, hold the consumer and fetch PC, and inject a bubble.
    executeMemoryWriteback := 0.U.asTypeOf(new ExecuteMemoryWriteback)
  }.otherwise {
    executeMemoryWriteback.valid := fetchDecodeExecute.valid
    executeMemoryWriteback.pc := fetchDecodeExecute.pc
    executeMemoryWriteback.instruction := fetchDecodeExecute.instruction
    executeMemoryWriteback.rd := rd
    executeMemoryWriteback.result := result
    executeMemoryWriteback.address := address
    executeMemoryWriteback.storeData := storeData
    executeMemoryWriteback.registerWrite := registerWrite
    executeMemoryWriteback.memoryRead := memoryRead
    executeMemoryWriteback.memoryWrite := memoryWrite
    executeMemoryWriteback.illegal := illegal

    when(redirect && fetchDecodeExecute.valid && !illegal) {
      fetchDecodeExecute.valid := false.B
      fetchPc := redirectTarget
    }.otherwise {
      fetchDecodeExecute.valid := true.B
      fetchDecodeExecute.pc := fetchPc
      fetchDecodeExecute.instruction := io.instruction
      fetchPc := fetchPc + 4.U
    }
  }
}
