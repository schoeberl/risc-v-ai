package riscvai

import chisel3._
import chisel3.util._

private class FetchDecodeExecute extends Bundle {
  val valid = Bool()
  val pc = UInt(32.W)
  val instruction = UInt(32.W)
  val usesRs1 = Bool()
  val usesRs2 = Bool()
  val divide = Bool()
  val multiply = Bool()
}

private class DecodeRegisterRead extends Bundle {
  val valid = Bool()
  val pc = UInt(32.W)
  val instruction = UInt(32.W)
  val usesRs1 = Bool()
  val usesRs2 = Bool()
}

private class DecodeExecute extends Bundle {
  val valid = Bool()
  val pc = UInt(32.W)
  val instruction = UInt(32.W)
  val rs1Value = UInt(32.W)
  val rs2Value = UInt(32.W)
  val memoryAddress = UInt(32.W)
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
  val redirect = Bool()
  val redirectTarget = UInt(32.W)
}

private class MemoryWriteback extends Bundle {
  val valid = Bool()
  val pc = UInt(32.W)
  val instruction = UInt(32.W)
  val rd = UInt(5.W)
  val data = UInt(32.W)
  val registerWrite = Bool()
  val illegal = Bool()
}

/** A configurable, single-issue RV32IMA_Zicsr_Zifencei pipeline.
  *
  * Concrete classes select whether decode and execute are separate stages and
  * whether selected control predecode is performed in fetch at elaboration time.
  */
class RvaiPipeline(
    resetVector: BigInt,
    separateDecodeExecute: Boolean,
    predecodeInFetch: Boolean = false,
    mergeExecuteMemory: Boolean = false,
    executeFromInstructionPort: Boolean = false,
    serializedInstructions: Boolean = false,
    separateWritebackStage: Boolean = false,
    forwardInExecute: Boolean = false,
    separateDecodeRegisterReadStage: Boolean = false
) extends Module {
  require(
    !predecodeInFetch || !separateDecodeExecute,
    "fetch predecode is currently supported only by a combined decode/execute stage"
  )
  require(
    !mergeExecuteMemory || separateDecodeExecute,
    "merged execute/memory requires a separate decode/address stage"
  )
  require(
    !mergeExecuteMemory || !predecodeInFetch,
    "merged execute/memory does not use fetch predecode"
  )
  require(
    !executeFromInstructionPort || (!separateDecodeExecute && !predecodeInFetch &&
      !mergeExecuteMemory),
    "direct instruction-port execution is a distinct two-stage organization"
  )
  require(
    !serializedInstructions || (separateDecodeExecute && !predecodeInFetch &&
      !mergeExecuteMemory && !executeFromInstructionPort),
    "serialized execution uses explicit fetch, decode, execute, and memory phases"
  )
  require(
    !separateWritebackStage || (separateDecodeExecute && !predecodeInFetch &&
      !mergeExecuteMemory && !executeFromInstructionPort && !serializedInstructions),
    "a separate writeback stage extends the conventional four-stage organization"
  )
  require(!forwardInExecute || separateWritebackStage)
  require(
    !separateDecodeRegisterReadStage ||
      (separateDecodeExecute && separateWritebackStage && forwardInExecute),
    "a separate register-read stage extends the conventional five-stage organization"
  )

  val io = IO(new Bundle {
    val instructionAddress = Output(UInt(32.W))
    val instructionNextAddress = Output(UInt(32.W))
    val instruction = Input(UInt(32.W))
    val instructionValid = Input(Bool())

    val dataAddress = Output(UInt(32.W))
    val dataNextAddress = Output(UInt(32.W))
    val dataReadData = Input(UInt(32.W))
    val dataAtomicReadData = Input(UInt(32.W))
    val dataReadEnable = Output(Bool())
    val dataWriteData = Output(UInt(32.W))
    val dataWriteEnable = Output(Bool())
    val dataWriteMask = Output(UInt(4.W))

    val memoryStall = Input(Bool())
    val instructionCacheInvalidate = Output(Bool())

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

  private def instructionUsesRs1Opcode(opcode: UInt, funct3: UInt): Bool =
    opcode === OpcodeJalr || opcode === OpcodeBranch || opcode === OpcodeLoad ||
      opcode === OpcodeStore || opcode === OpcodeOpImm || opcode === OpcodeOp ||
      opcode === OpcodeAtomic ||
      (opcode === OpcodeSystem && !funct3(2) && funct3 =/= 0.U)

  private def instructionUsesRs2Opcode(opcode: UInt): Bool =
    opcode === OpcodeBranch || opcode === OpcodeStore || opcode === OpcodeOp ||
      opcode === OpcodeAtomic

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
  private val decodeRegisterRead = Reg(new DecodeRegisterRead)
  private val decodeExecute = Reg(new DecodeExecute)
  private val executeMemoryWriteback = if (mergeExecuteMemory) {
    Wire(new ExecuteMemoryWriteback)
  } else {
    Reg(new ExecuteMemoryWriteback)
  }
  private val memoryWriteback = Reg(new MemoryWriteback)
  private val memoryWritebackConsumed = RegInit(false.B)
  private val mergedExecutionComplete = WireDefault(true.B)
  private object SerializedState extends ChiselEnum {
    val fetch, decode, execute, memory = Value
  }
  private val serializedState = RegInit(SerializedState.fetch)
  val registers = Reg(Vec(32, UInt(32.W)))
  val reservationValid = RegInit(false.B)
  val reservationAddress = Reg(UInt(32.W))
  private val csrs = Module(new MachineCsrs)
  private val divider = Module(new IterativeDivider)
  private val multiplier = Module(new PipelinedMultiplier)
  val illegalTrap = executeMemoryWriteback.valid && executeMemoryWriteback.illegal &&
    !io.memoryStall && mergedExecutionComplete

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
  val writebackValid = if (separateWritebackStage) {
    memoryWriteback.valid && !memoryWritebackConsumed
  } else {
    executeMemoryWriteback.valid && !io.memoryStall && mergedExecutionComplete
  }
  val writebackPc = if (separateWritebackStage) {
    memoryWriteback.pc
  } else {
    executeMemoryWriteback.pc
  }
  val writebackInstruction = if (separateWritebackStage) {
    memoryWriteback.instruction
  } else {
    executeMemoryWriteback.instruction
  }
  val writebackRd = if (separateWritebackStage) {
    memoryWriteback.rd
  } else {
    executeMemoryWriteback.rd
  }
  val committedData = if (separateWritebackStage) {
    memoryWriteback.data
  } else {
    writebackData
  }
  val writebackRegisterWrite = if (separateWritebackStage) {
    memoryWriteback.registerWrite
  } else {
    executeMemoryWriteback.registerWrite
  }
  val writebackIllegal = if (separateWritebackStage) {
    memoryWriteback.illegal
  } else {
    executeMemoryWriteback.illegal
  }
  val writebackCanCommit = if (separateWritebackStage) true.B else !io.memoryStall
  val writebackForwardActive = (if (separateWritebackStage) {
    memoryWriteback.valid
  } else {
    writebackValid
  }) && writebackRegisterWrite && !writebackIllegal && writebackRd =/= 0.U
  val writebackActive = writebackValid && writebackRegisterWrite && !writebackIllegal &&
    writebackRd =/= 0.U && writebackCanCommit
  val memoryForwardActive = if (separateWritebackStage) {
    executeMemoryWriteback.valid && executeMemoryWriteback.registerWrite &&
      !executeMemoryWriteback.illegal && executeMemoryWriteback.rd =/= 0.U &&
      !executeMemoryWriteback.memoryRead && !executeMemoryWriteback.atomicValid
  } else {
    false.B
  }

  when(writebackActive) {
    registers(writebackRd) := committedData
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
    AtomicAdd -> (io.dataAtomicReadData + executeMemoryWriteback.storeData),
    AtomicSwap -> executeMemoryWriteback.storeData,
    AtomicXor -> (io.dataAtomicReadData ^ executeMemoryWriteback.storeData),
    AtomicAnd -> (io.dataAtomicReadData & executeMemoryWriteback.storeData),
    AtomicOr -> (io.dataAtomicReadData | executeMemoryWriteback.storeData),
    AtomicMin -> Mux(
      io.dataAtomicReadData.asSInt < executeMemoryWriteback.storeData.asSInt,
      io.dataAtomicReadData,
      executeMemoryWriteback.storeData
    ),
    AtomicMax -> Mux(
      io.dataAtomicReadData.asSInt > executeMemoryWriteback.storeData.asSInt,
      io.dataAtomicReadData,
      executeMemoryWriteback.storeData
    ),
    AtomicMinU -> Mux(
      io.dataAtomicReadData < executeMemoryWriteback.storeData,
      io.dataAtomicReadData,
      executeMemoryWriteback.storeData
    ),
    AtomicMaxU -> Mux(
      io.dataAtomicReadData > executeMemoryWriteback.storeData,
      io.dataAtomicReadData,
      executeMemoryWriteback.storeData
    )
  ))
  val atomicStoreAllowed = !executeMemoryWriteback.atomicValid ||
    executeMemoryWriteback.atomicOperation =/= AtomicSc || scSuccess
  val dataWriteActive = executeMemoryWriteback.valid &&
    executeMemoryWriteback.memoryWrite && !executeMemoryWriteback.illegal && atomicStoreAllowed

  io.dataAddress := executeMemoryWriteback.address & "hfffffffc".U
  io.dataReadEnable := executeMemoryWriteback.valid &&
    executeMemoryWriteback.memoryRead && !executeMemoryWriteback.illegal
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
  io.retiredValid := writebackValid && !writebackIllegal && writebackCanCommit
  io.retiredPc := writebackPc
  io.retiredInstruction := writebackInstruction
  io.instructionCacheInvalidate := io.retiredValid &&
    writebackInstruction(6, 0) === OpcodeMiscMem &&
    writebackInstruction(14, 12) === "b001".U
  io.debugRegisterData := Mux(
    io.debugRegisterAddress === 0.U,
    0.U,
    registers(io.debugRegisterAddress)
  )

  val instruction = if (executeFromInstructionPort) {
    io.instruction
  } else if (separateDecodeExecute) {
    decodeExecute.instruction
  } else {
    fetchDecodeExecute.instruction
  }
  val executeValid = (if (executeFromInstructionPort) {
    io.instructionValid
  } else if (separateDecodeExecute) {
    decodeExecute.valid
  } else {
    fetchDecodeExecute.valid
  }) && (!serializedInstructions.B || serializedState === SerializedState.execute)
  val executePc = if (executeFromInstructionPort) {
    fetchPc
  } else if (separateDecodeExecute) {
    decodeExecute.pc
  } else {
    fetchDecodeExecute.pc
  }
  val opcode = instruction(6, 0)
  val rd = instruction(11, 7)
  val funct3 = instruction(14, 12)
  val rs1 = instruction(19, 15)
  val rs2 = instruction(24, 20)
  val funct7 = instruction(31, 25)
  val isMret = instruction === "h30200073".U

  private def readExecuteRegister(address: UInt): UInt =
    Mux(
      address === 0.U,
      0.U,
      Mux(
        writebackActive && writebackRd === address,
        committedData,
        registers(address)
      )
    )

  private def forwardedExecuteRegister(address: UInt, unforwarded: UInt): UInt =
    Mux(
      address === 0.U,
      0.U,
      Mux(
        memoryForwardActive && executeMemoryWriteback.rd === address,
        executeMemoryWriteback.result,
        Mux(
          writebackForwardActive && writebackRd === address,
          committedData,
          unforwarded
        )
      )
    )

  val rs1Value = if (separateDecodeExecute && forwardInExecute) {
    forwardedExecuteRegister(rs1, decodeExecute.rs1Value)
  } else if (separateDecodeExecute) {
    decodeExecute.rs1Value
  } else {
    readExecuteRegister(rs1)
  }
  val rs2Value = if (separateDecodeExecute && forwardInExecute) {
    forwardedExecuteRegister(rs2, decodeExecute.rs2Value)
  } else if (separateDecodeExecute) {
    decodeExecute.rs2Value
  } else {
    readExecuteRegister(rs2)
  }
  val divideInstruction = executeValid && (if (predecodeInFetch) {
    fetchDecodeExecute.divide
  } else {
    opcode === OpcodeOp && funct7 === "b0000001".U && funct3(2)
  })
  val multiplyInstruction = executeValid && (if (predecodeInFetch) {
    fetchDecodeExecute.multiply
  } else {
    opcode === OpcodeOp && funct7 === "b0000001".U && !funct3(2)
  })
  val dividerStart = divideInstruction && !divider.io.busy && !divider.io.done &&
    !illegalTrap
  val multiplierStart = multiplyInstruction && !multiplier.io.busy && !multiplier.io.done &&
    !illegalTrap
  val divideStall = divideInstruction && !divider.io.done
  val multiplyStall = multiplyInstruction && !multiplier.io.done
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
  csrs.io.retired := io.retiredValid
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
      result := executePc + immediateU
    }

    is(OpcodeJal) {
      illegal := false.B
      registerWrite := true.B
      result := executePc + 4.U
      redirect := true.B
      redirectTarget := executePc + immediateJ
    }

    is(OpcodeJalr) {
      when(funct3 === "b000".U) {
        illegal := false.B
        registerWrite := true.B
        result := executePc + 4.U
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
        redirectTarget := executePc + immediateB
      }
    }

    is(OpcodeLoad) {
      address := (if (mergeExecuteMemory) decodeExecute.memoryAddress else rs1Value + immediateI)
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
      address := (if (mergeExecuteMemory) decodeExecute.memoryAddress else rs1Value + immediateS)
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
      address := (if (mergeExecuteMemory) decodeExecute.memoryAddress else rs1Value)
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

  if (mergeExecuteMemory) {
    // Stage 3 executes and consumes the synchronous cache result directly; no
    // execute-to-memory register is present in this organization.
    executeMemoryWriteback.valid := executeValid
    executeMemoryWriteback.pc := executePc
    executeMemoryWriteback.instruction := instruction
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
    executeMemoryWriteback.redirect := redirect
    executeMemoryWriteback.redirectTarget := redirectTarget
  }

  val decodeInstruction = if (separateDecodeRegisterReadStage) {
    decodeRegisterRead.instruction
  } else {
    fetchDecodeExecute.instruction
  }
  val decodeValid = (if (separateDecodeRegisterReadStage) {
    decodeRegisterRead.valid
  } else {
    fetchDecodeExecute.valid
  }) &&
    (!serializedInstructions.B || serializedState === SerializedState.decode)
  val decodePc = if (separateDecodeRegisterReadStage) {
    decodeRegisterRead.pc
  } else {
    fetchDecodeExecute.pc
  }
  val decodeOpcode = decodeInstruction(6, 0)
  val decodeRs1 = decodeInstruction(19, 15)
  val decodeRs2 = decodeInstruction(24, 20)
  val decodeFunct3 = decodeInstruction(14, 12)
  val decodeUsesRs1 = if (separateDecodeRegisterReadStage) {
    decodeRegisterRead.usesRs1
  } else if (predecodeInFetch) {
    fetchDecodeExecute.usesRs1
  } else {
    instructionUsesRs1Opcode(decodeOpcode, decodeFunct3)
  }
  val decodeUsesRs2 = if (separateDecodeRegisterReadStage) {
    decodeRegisterRead.usesRs2
  } else if (predecodeInFetch) {
    fetchDecodeExecute.usesRs2
  } else {
    instructionUsesRs2Opcode(decodeOpcode)
  }

  val executeForwardActive = if (
    separateDecodeExecute && !serializedInstructions && !forwardInExecute
  ) {
    executeValid && registerWrite && !illegal && !memoryRead && !atomicValid && rd =/= 0.U
  } else {
    false.B
  }
  private def readDecodeRegister(address: UInt): UInt =
    Mux(
      address === 0.U,
      0.U,
      Mux(
        executeForwardActive && rd === address,
        result,
        Mux(
          !forwardInExecute.B && memoryForwardActive &&
            executeMemoryWriteback.rd === address,
          executeMemoryWriteback.result,
          Mux(
            writebackForwardActive && writebackRd === address,
            committedData,
            registers(address)
          )
        )
      )
    )

  val decodeRs1Value = readDecodeRegister(decodeRs1)
  val decodeRs2Value = readDecodeRegister(decodeRs2)
  val decodeImmediateI = signExtend(decodeInstruction(31, 20), 12)
  val decodeImmediateS = signExtend(
    Cat(decodeInstruction(31, 25), decodeInstruction(11, 7)),
    12
  )
  val decodeAddressOffset = Mux(
    decodeOpcode === OpcodeStore,
    decodeImmediateS,
    Mux(decodeOpcode === OpcodeLoad, decodeImmediateI, 0.U)
  )
  val decodeMemoryAddress = decodeRs1Value + decodeAddressOffset

  // The merged organization performs effective-address calculation in decode
  // so the synchronous cache lookup and the stage-3 instruction advance on the
  // same edge. Other organizations calculate the address in execute.
  io.dataNextAddress := (if (mergeExecuteMemory) {
    decodeMemoryAddress
  } else {
    address
  }) & "hfffffffc".U

  val executeResultHazard = decodeValid && executeValid && registerWrite && !illegal &&
    (memoryRead || atomicValid) && rd =/= 0.U &&
    ((decodeUsesRs1 && decodeRs1 === rd) || (decodeUsesRs2 && decodeRs2 === rd))
  val writebackResultHazard = decodeValid && executeMemoryWriteback.valid &&
    executeMemoryWriteback.registerWrite && !executeMemoryWriteback.illegal &&
    (executeMemoryWriteback.memoryRead || executeMemoryWriteback.atomicValid) &&
    executeMemoryWriteback.rd =/= 0.U &&
    ((decodeUsesRs1 && decodeRs1 === executeMemoryWriteback.rd) ||
      (decodeUsesRs2 && decodeRs2 === executeMemoryWriteback.rd))
  val unavailableResultHazard = if (
    mergeExecuteMemory || executeFromInstructionPort || serializedInstructions
  ) {
    false.B
  } else if (separateDecodeExecute) {
    executeResultHazard
  } else {
    writebackResultHazard
  }
  val executionStall = divideStall || multiplyStall
  if (mergeExecuteMemory) {
    mergedExecutionComplete := !executionStall
  }
  val pipelineStall = io.memoryStall || executionStall || unavailableResultHazard
  io.pipelineStall := pipelineStall

  val executeRedirect = redirect && executeValid && !illegal
  val legalCsrWrite = executeValid && opcode === OpcodeSystem &&
    validCsrCommand && csrs.io.readValid && csrs.io.writeAllowed && csrWriteRequested
  val mretActive = executeValid && isMret && !executionStall &&
    !io.memoryStall && !illegalTrap
  csrs.io.writeEnable := legalCsrWrite && !executionStall && !io.memoryStall && !illegalTrap
  csrs.io.mret := mretActive

  // A synchronous instruction memory captures this address on the same edge
  // that fetchPc advances. Its output then corresponds to fetchPc throughout
  // the following cycle.
  val fetchPcNext = WireDefault(fetchPc)
  if (serializedInstructions) {
    when(reset.asBool) {
      fetchPcNext := resetVector.U(32.W)
    }.elsewhen(serializedState === SerializedState.memory && !io.memoryStall) {
      when(illegalTrap) {
        fetchPcNext := csrs.io.trapVector
      }.elsewhen(executeMemoryWriteback.redirect) {
        fetchPcNext := executeMemoryWriteback.redirectTarget
      }.otherwise {
        fetchPcNext := fetchPc + 4.U
      }
    }
  } else {
    when(reset.asBool) {
      fetchPcNext := resetVector.U(32.W)
    }.elsewhen(illegalTrap) {
      fetchPcNext := csrs.io.trapVector
    }.elsewhen(!io.memoryStall && !executionStall) {
      when(executeRedirect) {
        fetchPcNext := redirectTarget
      }.elsewhen(!unavailableResultHazard && io.instructionValid) {
        fetchPcNext := fetchPc + 4.U
      }
    }
  }
  io.instructionNextAddress := fetchPcNext

  private def advanceMemoryToWriteback(): Unit = {
    memoryWriteback.valid := executeMemoryWriteback.valid
    memoryWriteback.pc := executeMemoryWriteback.pc
    memoryWriteback.instruction := executeMemoryWriteback.instruction
    memoryWriteback.rd := executeMemoryWriteback.rd
    memoryWriteback.data := writebackData
    memoryWriteback.registerWrite := executeMemoryWriteback.registerWrite
    memoryWriteback.illegal := executeMemoryWriteback.illegal
    memoryWritebackConsumed := false.B
  }

  if (serializedInstructions) {
    when(reset.asBool) {
      serializedState := SerializedState.fetch
      fetchDecodeExecute.valid := false.B
      decodeExecute.valid := false.B
      executeMemoryWriteback.valid := false.B
    }.otherwise {
      switch(serializedState) {
        is(SerializedState.fetch) {
          executeMemoryWriteback.valid := false.B
          when(io.instructionValid) {
            fetchDecodeExecute.valid := true.B
            fetchDecodeExecute.pc := fetchPc
            fetchDecodeExecute.instruction := io.instruction
            serializedState := SerializedState.decode
          }
        }
        is(SerializedState.decode) {
          decodeExecute.valid := decodeValid
          decodeExecute.pc := decodePc
          decodeExecute.instruction := decodeInstruction
          decodeExecute.rs1Value := decodeRs1Value
          decodeExecute.rs2Value := decodeRs2Value
          decodeExecute.memoryAddress := decodeMemoryAddress
          fetchDecodeExecute.valid := false.B
          serializedState := SerializedState.execute
        }
        is(SerializedState.execute) {
          when(!executionStall) {
            executeMemoryWriteback.valid := executeValid
            executeMemoryWriteback.pc := executePc
            executeMemoryWriteback.instruction := instruction
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
            executeMemoryWriteback.redirect := redirect
            executeMemoryWriteback.redirectTarget := redirectTarget
            serializedState := SerializedState.memory
          }
        }
        is(SerializedState.memory) {
          when(!io.memoryStall) {
            fetchPc := fetchPcNext
            decodeExecute.valid := false.B
            executeMemoryWriteback.valid := false.B
            serializedState := SerializedState.fetch
          }
        }
      }
    }
  } else when(illegalTrap) {
    if (separateWritebackStage) {
      memoryWriteback.valid := false.B
      memoryWritebackConsumed := false.B
    }
    if (!mergeExecuteMemory) {
      executeMemoryWriteback := 0.U.asTypeOf(new ExecuteMemoryWriteback)
    }
    decodeExecute.valid := false.B
    if (separateDecodeRegisterReadStage) {
      decodeRegisterRead.valid := false.B
    }
    fetchDecodeExecute.valid := false.B
    fetchPc := csrs.io.trapVector
  }.elsewhen(io.memoryStall) {
    // Hold fetch through memory until the cache hierarchy completes the access.
    // A distinct writeback stage is older than the blocked memory instruction.
    // Retain its payload for forwarding, but mark it consumed after committing
    // once so a prolonged memory stall cannot repeat retirement or a write.
    if (separateWritebackStage) {
      when(memoryWriteback.valid) {
        memoryWritebackConsumed := true.B
      }
    }
  }.elsewhen(executionStall) {
    // Let the older instruction retire while execute, decode, and fetch hold.
    if (separateWritebackStage) {
      advanceMemoryToWriteback()
    }
    if (!mergeExecuteMemory) {
      executeMemoryWriteback := 0.U.asTypeOf(new ExecuteMemoryWriteback)
    }
  }.otherwise {
    if (separateWritebackStage) {
      advanceMemoryToWriteback()
    }
    if (!mergeExecuteMemory) {
      executeMemoryWriteback.valid := executeValid
      executeMemoryWriteback.pc := executePc
      executeMemoryWriteback.instruction := instruction
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
      executeMemoryWriteback.redirect := redirect
      executeMemoryWriteback.redirectTarget := redirectTarget
    }

    when(executeRedirect) {
      if (separateDecodeExecute) {
        decodeExecute.valid := false.B
      }
      if (separateDecodeRegisterReadStage) {
        decodeRegisterRead.valid := false.B
      }
      fetchDecodeExecute.valid := false.B
      fetchPc := redirectTarget
    }.elsewhen(unavailableResultHazard) {
      // The older load/atomic advances; hold its consumer and inject a bubble.
      if (separateDecodeExecute) {
        decodeExecute.valid := false.B
      } else {
        executeMemoryWriteback.valid := false.B
      }
    }.otherwise {
      if (separateDecodeExecute) {
        decodeExecute.valid := decodeValid
        decodeExecute.pc := decodePc
        decodeExecute.instruction := decodeInstruction
        decodeExecute.rs1Value := decodeRs1Value
        decodeExecute.rs2Value := decodeRs2Value
        decodeExecute.memoryAddress := decodeMemoryAddress
      }
      if (separateDecodeRegisterReadStage) {
        decodeRegisterRead.valid := fetchDecodeExecute.valid
        decodeRegisterRead.pc := fetchDecodeExecute.pc
        decodeRegisterRead.instruction := fetchDecodeExecute.instruction
        val registerReadOpcode = fetchDecodeExecute.instruction(6, 0)
        decodeRegisterRead.usesRs1 := instructionUsesRs1Opcode(
          registerReadOpcode,
          fetchDecodeExecute.instruction(14, 12)
        )
        decodeRegisterRead.usesRs2 := instructionUsesRs2Opcode(registerReadOpcode)
      }
      if (executeFromInstructionPort) {
        when(io.instructionValid) {
          fetchPc := fetchPc + 4.U
        }
      } else {
        fetchDecodeExecute.valid := io.instructionValid
        when(io.instructionValid) {
          fetchDecodeExecute.pc := fetchPc
          fetchDecodeExecute.instruction := io.instruction
          val fetchedOpcode = io.instruction(6, 0)
          val fetchedFunct3 = io.instruction(14, 12)
          val fetchedFunct7 = io.instruction(31, 25)
          fetchDecodeExecute.usesRs1 := instructionUsesRs1Opcode(
            fetchedOpcode,
            io.instruction(14, 12)
          )
          fetchDecodeExecute.usesRs2 := instructionUsesRs2Opcode(fetchedOpcode)
          fetchDecodeExecute.divide := fetchedOpcode === OpcodeOp &&
            fetchedFunct7 === "b0000001".U && fetchedFunct3(2)
          fetchDecodeExecute.multiply := fetchedOpcode === OpcodeOp &&
            fetchedFunct7 === "b0000001".U && !fetchedFunct3(2)
          fetchPc := fetchPc + 4.U
        }
      }
    }
  }

  val retiringAtomic = executeMemoryWriteback.valid && executeMemoryWriteback.atomicValid &&
    !executeMemoryWriteback.illegal
  when(!io.memoryStall) {
    when(illegalTrap || mretActive || io.dataWriteEnable ||
      (retiringAtomic && executeMemoryWriteback.atomicOperation === AtomicSc)) {
      reservationValid := false.B
    }.elsewhen(retiringAtomic && executeMemoryWriteback.atomicOperation === AtomicLr) {
      reservationValid := true.B
      reservationAddress := executeMemoryWriteback.address & "hfffffffc".U
    }
  }

  // Payload registers are ignored until their corresponding valid bit is set.
  // Reset only the validity state so the datapath can use reset-free flops.
  if (!serializedInstructions) {
    when(reset.asBool) {
      fetchDecodeExecute.valid := false.B
      if (separateDecodeRegisterReadStage) {
        decodeRegisterRead.valid := false.B
      }
      decodeExecute.valid := false.B
      if (!mergeExecuteMemory) {
        executeMemoryWriteback.valid := false.B
      }
      if (separateWritebackStage) {
        memoryWriteback.valid := false.B
        memoryWritebackConsumed := false.B
      }
    }
  }
}

/** A four-stage processor with fetch, decode/register-read, execute, and
  * memory/writeback stages.
  *
  * Execute and writeback results are forwarded to decode. A load or atomic
  * followed immediately by a dependent instruction incurs one stall cycle.
  */
class RvaiFourStages(resetVector: BigInt = 0)
    extends RvaiPipeline(resetVector, separateDecodeExecute = true)
