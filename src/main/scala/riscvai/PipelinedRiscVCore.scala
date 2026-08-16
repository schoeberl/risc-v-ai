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
  val memoryFunction = UInt(3.W)
  val atomicValid = Bool()
  val atomicOperation = UInt(5.W)
  val illegal = Bool()
}

/** A three-stage, single-issue RV32IMA_Zicsr_Zifencei processor.
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
    val dataWriteMask = Output(UInt(4.W))

    val illegalInstruction = Output(Bool())
    val pipelineStall = Output(Bool())

    val retiredValid = Output(Bool())
    val retiredPc = Output(UInt(32.W))
    val retiredInstruction = Output(UInt(32.W))

    val debugRegisterAddress = Input(UInt(5.W))
    val debugRegisterData = Output(UInt(32.W))
  })

  private val OpcodeLoad = "b0000011".U(7.W)
  private val OpcodeMiscMem = "b0001111".U(7.W)
  private val OpcodeOpImm = "b0010011".U(7.W)
  private val OpcodeAuipc = "b0010111".U(7.W)
  private val OpcodeStore = "b0100011".U(7.W)
  private val OpcodeAtomic = "b0101111".U(7.W)
  private val OpcodeOp = "b0110011".U(7.W)
  private val OpcodeLui = "b0110111".U(7.W)
  private val OpcodeBranch = "b1100011".U(7.W)
  private val OpcodeJalr = "b1100111".U(7.W)
  private val OpcodeJal = "b1101111".U(7.W)
  private val OpcodeSystem = "b1110011".U(7.W)

  private val AtomicAdd = "b00000".U(5.W)
  private val AtomicSwap = "b00001".U(5.W)
  private val AtomicLr = "b00010".U(5.W)
  private val AtomicSc = "b00011".U(5.W)
  private val AtomicXor = "b00100".U(5.W)
  private val AtomicOr = "b01000".U(5.W)
  private val AtomicMin = "b10000".U(5.W)
  private val AtomicMax = "b10100".U(5.W)
  private val AtomicMinU = "b11000".U(5.W)
  private val AtomicMaxU = "b11100".U(5.W)
  private val AtomicAnd = "b01100".U(5.W)

  private def signExtend(value: UInt, width: Int): UInt =
    Cat(Fill(32 - width, value(width - 1)), value)

  val fetchPc = RegInit(resetVector.U(32.W))
  private val fetchDecodeExecute = Reg(new FetchDecodeExecute)
  private val executeMemoryWriteback = Reg(new ExecuteMemoryWriteback)
  val registers = Reg(Vec(32, UInt(32.W)))
  val reservationValid = RegInit(false.B)
  val reservationAddress = Reg(UInt(32.W))
  private val csrs = Module(new MachineCsrs)
  private val divider = Module(new IterativeDivider)
  private val multiplier = Module(new PipelinedMultiplier)
  val illegalTrap = executeMemoryWriteback.valid && executeMemoryWriteback.illegal

  val memoryByteShift = Cat(executeMemoryWriteback.address(1, 0), 0.U(3.W))
  val shiftedReadData = io.dataReadData >> memoryByteShift
  val loadedByte = shiftedReadData(7, 0)
  val loadedHalfword = shiftedReadData(15, 0)
  val loadData = MuxLookup(executeMemoryWriteback.memoryFunction, io.dataReadData)(Seq(
    "b000".U -> Cat(Fill(24, loadedByte(7)), loadedByte),
    "b001".U -> Cat(Fill(16, loadedHalfword(15)), loadedHalfword),
    "b010".U -> io.dataReadData,
    "b100".U -> Cat(0.U(24.W), loadedByte),
    "b101".U -> Cat(0.U(16.W), loadedHalfword)
  ))
  val scSuccess = reservationValid &&
    reservationAddress === (executeMemoryWriteback.address & "hfffffffc".U)
  val writebackData = Mux(
    executeMemoryWriteback.atomicValid && executeMemoryWriteback.atomicOperation === AtomicSc,
    Mux(scSuccess, 0.U, 1.U),
    Mux(executeMemoryWriteback.memoryRead, loadData, executeMemoryWriteback.result)
  )
  val writebackActive = executeMemoryWriteback.valid &&
    executeMemoryWriteback.registerWrite && !executeMemoryWriteback.illegal &&
    executeMemoryWriteback.rd =/= 0.U

  when(writebackActive) {
    registers(executeMemoryWriteback.rd) := writebackData
  }
  registers(0) := 0.U

  io.instructionAddress := fetchPc
  val storeLaneMask = MuxLookup(executeMemoryWriteback.memoryFunction, 0.U(4.W))(Seq(
    "b000".U -> 1.U(4.W),
    "b001".U -> 3.U(4.W),
    "b010".U -> 15.U(4.W)
  ))
  val shiftedStoreMask = (storeLaneMask << executeMemoryWriteback.address(1, 0))(3, 0)
  val atomicWriteData = MuxLookup(
    executeMemoryWriteback.atomicOperation,
    executeMemoryWriteback.storeData
  )(Seq(
    AtomicAdd -> (io.dataReadData + executeMemoryWriteback.storeData),
    AtomicSwap -> executeMemoryWriteback.storeData,
    AtomicXor -> (io.dataReadData ^ executeMemoryWriteback.storeData),
    AtomicAnd -> (io.dataReadData & executeMemoryWriteback.storeData),
    AtomicOr -> (io.dataReadData | executeMemoryWriteback.storeData),
    AtomicMin -> Mux(
      io.dataReadData.asSInt < executeMemoryWriteback.storeData.asSInt,
      io.dataReadData,
      executeMemoryWriteback.storeData
    ),
    AtomicMax -> Mux(
      io.dataReadData.asSInt > executeMemoryWriteback.storeData.asSInt,
      io.dataReadData,
      executeMemoryWriteback.storeData
    ),
    AtomicMinU -> Mux(
      io.dataReadData < executeMemoryWriteback.storeData,
      io.dataReadData,
      executeMemoryWriteback.storeData
    ),
    AtomicMaxU -> Mux(
      io.dataReadData > executeMemoryWriteback.storeData,
      io.dataReadData,
      executeMemoryWriteback.storeData
    )
  ))
  val atomicStoreAllowed = !executeMemoryWriteback.atomicValid ||
    executeMemoryWriteback.atomicOperation =/= AtomicSc || scSuccess
  val dataWriteActive = executeMemoryWriteback.valid &&
    executeMemoryWriteback.memoryWrite && !executeMemoryWriteback.illegal && atomicStoreAllowed

  io.dataAddress := executeMemoryWriteback.address & "hfffffffc".U
  io.dataWriteData := Mux(
    executeMemoryWriteback.atomicValid,
    Mux(
      executeMemoryWriteback.atomicOperation === AtomicSc,
      executeMemoryWriteback.storeData,
      atomicWriteData
    ),
    executeMemoryWriteback.storeData << memoryByteShift
  )
  io.dataWriteMask := Mux(
    dataWriteActive,
    shiftedStoreMask,
    0.U
  )
  io.dataWriteEnable := dataWriteActive
  io.illegalInstruction := illegalTrap
  io.retiredValid := executeMemoryWriteback.valid && !executeMemoryWriteback.illegal
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
  val isMret = instruction === "h30200073".U

  val usesRs1 = opcode === OpcodeJalr || opcode === OpcodeBranch ||
    opcode === OpcodeLoad || opcode === OpcodeStore ||
    opcode === OpcodeOpImm || opcode === OpcodeOp || opcode === OpcodeAtomic ||
    (opcode === OpcodeSystem && !funct3(2) && funct3 =/= 0.U)
  val usesRs2 = opcode === OpcodeBranch || opcode === OpcodeStore ||
    opcode === OpcodeOp || opcode === OpcodeAtomic

  val loadUseHazard = fetchDecodeExecute.valid &&
    executeMemoryWriteback.valid && executeMemoryWriteback.memoryRead &&
    executeMemoryWriteback.rd =/= 0.U &&
    ((usesRs1 && rs1 === executeMemoryWriteback.rd) ||
      (usesRs2 && rs2 === executeMemoryWriteback.rd))
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
  val divideInstruction = fetchDecodeExecute.valid && opcode === OpcodeOp &&
    funct7 === "b0000001".U && funct3(2)
  val multiplyInstruction = fetchDecodeExecute.valid && opcode === OpcodeOp &&
    funct7 === "b0000001".U && !funct3(2)
  val dividerStart = divideInstruction && !divider.io.busy && !divider.io.done &&
    !loadUseHazard && !illegalTrap
  val multiplierStart = multiplyInstruction && !multiplier.io.busy && !multiplier.io.done &&
    !loadUseHazard && !illegalTrap
  val divideStall = divideInstruction && !divider.io.done
  val multiplyStall = multiplyInstruction && !multiplier.io.done
  val pipelineStall = loadUseHazard || divideStall || multiplyStall
  io.pipelineStall := pipelineStall
  divider.io.start := dividerStart
  divider.io.signed := !funct3(0)
  divider.io.dividend := rs1Value
  divider.io.divisor := rs2Value
  multiplier.io.start := multiplierStart
  multiplier.io.lhsSigned := funct3 === "b001".U || funct3 === "b010".U
  multiplier.io.rhsSigned := funct3 === "b001".U
  multiplier.io.highResult := funct3 =/= "b000".U
  multiplier.io.lhs := rs1Value
  multiplier.io.rhs := rs2Value

  val csrSource = Mux(funct3(2), Cat(0.U(27.W), rs1), rs1Value)
  val csrCommand = funct3(1, 0)
  val validCsrCommand = funct3 === "b001".U || funct3 === "b010".U ||
    funct3 === "b011".U || funct3 === "b101".U ||
    funct3 === "b110".U || funct3 === "b111".U
  val csrWriteRequested = validCsrCommand &&
    (csrCommand === 1.U || ((csrCommand === 2.U || csrCommand === 3.U) && csrSource =/= 0.U))
  val csrWriteData = MuxLookup(csrCommand, csrSource)(Seq(
    1.U -> csrSource,
    2.U -> (csrs.io.readData | csrSource),
    3.U -> (csrs.io.readData & ~csrSource)
  ))

  csrs.io.address := instruction(31, 20)
  csrs.io.writeData := csrWriteData
  csrs.io.retired := executeMemoryWriteback.valid && !executeMemoryWriteback.illegal
  csrs.io.trapEnter := illegalTrap
  csrs.io.trapPc := executeMemoryWriteback.pc
  csrs.io.trapCause := 2.U // Illegal instruction
  csrs.io.trapValue := executeMemoryWriteback.instruction

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
  val memoryFunction = WireDefault(0.U(3.W))
  val atomicValid = WireDefault(false.B)
  val atomicOperation = WireDefault(0.U(5.W))
  val illegal = WireDefault(true.B)
  val redirect = WireDefault(false.B)
  val redirectTarget = WireDefault(0.U(32.W))

  switch(opcode) {
    is(OpcodeLui) {
      illegal := false.B
      registerWrite := true.B
      result := immediateU
    }

    is(OpcodeMiscMem) {
      when(funct3 === "b000".U || funct3 === "b001".U) {
        illegal := false.B // FENCE and FENCE.I are no-ops in this uncached single-hart core.
      }
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
      val validWidth = funct3 === "b000".U || funct3 === "b001".U ||
        funct3 === "b010".U || funct3 === "b100".U || funct3 === "b101".U
      val aligned = MuxLookup(funct3, true.B)(Seq(
        "b001".U -> !address(0),
        "b010".U -> (address(1, 0) === 0.U),
        "b101".U -> !address(0)
      ))
      when(validWidth && aligned) {
        illegal := false.B
        registerWrite := true.B
        memoryRead := true.B
        memoryFunction := funct3
      }
    }

    is(OpcodeStore) {
      address := rs1Value + immediateS
      val validWidth = funct3 === "b000".U || funct3 === "b001".U || funct3 === "b010".U
      val aligned = MuxLookup(funct3, true.B)(Seq(
        "b001".U -> !address(0),
        "b010".U -> (address(1, 0) === 0.U)
      ))
      when(validWidth && aligned) {
        illegal := false.B
        memoryWrite := true.B
        memoryFunction := funct3
      }
    }

    is(OpcodeAtomic) {
      val operation = instruction(31, 27)
      val validOperation = operation === AtomicAdd || operation === AtomicSwap ||
        operation === AtomicLr || operation === AtomicSc || operation === AtomicXor ||
        operation === AtomicAnd || operation === AtomicOr || operation === AtomicMin ||
        operation === AtomicMax || operation === AtomicMinU || operation === AtomicMaxU
      val validLr = operation =/= AtomicLr || rs2 === 0.U
      address := rs1Value
      when(funct3 === "b010".U && address(1, 0) === 0.U && validOperation && validLr) {
        illegal := false.B
        registerWrite := true.B
        memoryRead := operation =/= AtomicSc
        memoryWrite := operation =/= AtomicLr
        memoryFunction := "b010".U
        atomicValid := true.B
        atomicOperation := operation
      }
    }

    is(OpcodeSystem) {
      val legalCsrAccess = validCsrCommand && csrs.io.readValid &&
        (!csrWriteRequested || csrs.io.writeAllowed)
      when(isMret) {
        illegal := false.B
        redirect := true.B
        redirectTarget := csrs.io.mretPc
      }.elsewhen(legalCsrAccess) {
        illegal := false.B
        registerWrite := true.B
        result := csrs.io.readData
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
      when(funct7 === "b0000001".U) {
        when(funct3(2)) {
          result := Mux(funct3(1), divider.io.remainder, divider.io.quotient)
        }.otherwise {
          result := multiplier.io.result
        }
      }.otherwise {
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
  }

  when(illegalTrap) {
    executeMemoryWriteback := 0.U.asTypeOf(new ExecuteMemoryWriteback)
    fetchDecodeExecute.valid := false.B
    fetchPc := csrs.io.trapVector
  }.elsewhen(pipelineStall) {
    // Let the older instruction retire, hold decode/fetch, and inject a bubble.
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
    executeMemoryWriteback.memoryFunction := memoryFunction
    executeMemoryWriteback.atomicValid := atomicValid
    executeMemoryWriteback.atomicOperation := atomicOperation
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

  val legalCsrWrite = fetchDecodeExecute.valid && opcode === OpcodeSystem &&
    validCsrCommand && csrs.io.readValid && csrs.io.writeAllowed && csrWriteRequested
  val mretActive = fetchDecodeExecute.valid && isMret && !pipelineStall && !illegalTrap
  csrs.io.writeEnable := legalCsrWrite && !pipelineStall && !illegalTrap
  csrs.io.mret := mretActive

  val retiringAtomic = executeMemoryWriteback.valid && executeMemoryWriteback.atomicValid &&
    !executeMemoryWriteback.illegal
  when(illegalTrap || mretActive || io.dataWriteEnable ||
    (retiringAtomic && executeMemoryWriteback.atomicOperation === AtomicSc)) {
    reservationValid := false.B
  }.elsewhen(retiringAtomic && executeMemoryWriteback.atomicOperation === AtomicLr) {
    reservationValid := true.B
    reservationAddress := executeMemoryWriteback.address & "hfffffffc".U
  }

  // Payload registers are ignored until their corresponding valid bit is set.
  // Reset only the validity state so the datapath can use reset-free flops.
  when(reset.asBool) {
    fetchDecodeExecute.valid := false.B
    executeMemoryWriteback.valid := false.B
  }
}
