package riscvai

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class PipelinedRiscVCoreSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val Nop = BigInt("00000013", 16)

  private def bits(value: Int, width: Int): BigInt =
    BigInt(value) & ((BigInt(1) << width) - 1)

  private def iType(imm: Int, rs1: Int, funct3: Int, rd: Int, opcode: Int = 0x13): BigInt =
    (bits(imm, 12) << 20) | (BigInt(rs1) << 15) | (BigInt(funct3) << 12) |
      (BigInt(rd) << 7) | BigInt(opcode)

  private def rType(funct7: Int, rs2: Int, rs1: Int, funct3: Int, rd: Int): BigInt =
    (BigInt(funct7) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | (BigInt(rd) << 7) | BigInt(0x33)

  private def uType(imm20: Int, rd: Int): BigInt =
    (bits(imm20, 20) << 12) | (BigInt(rd) << 7) | BigInt(0x37)

  private def sType(imm: Int, rs2: Int, rs1: Int, funct3: Int): BigInt = {
    val encoded = bits(imm, 12)
    ((encoded >> 5) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | ((encoded & 0x1f) << 7) | BigInt(0x23)
  }

  private def bType(offset: Int, rs2: Int, rs1: Int, funct3: Int): BigInt = {
    require(offset % 2 == 0)
    val encoded = bits(offset, 13)
    (((encoded >> 12) & 1) << 31) | (((encoded >> 5) & 0x3f) << 25) |
      (BigInt(rs2) << 20) | (BigInt(rs1) << 15) | (BigInt(funct3) << 12) |
      (((encoded >> 1) & 0xf) << 8) | (((encoded >> 11) & 1) << 7) | BigInt(0x63)
  }

  private def initialize(dut: PipelinedRiscVCore): Unit = {
    dut.io.instruction.poke(Nop.U)
    dut.io.dataReadData.poke(0.U)
    dut.io.debugRegisterAddress.poke(0.U)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
  }

  private def expectRegister(dut: PipelinedRiscVCore, register: Int, value: BigInt): Unit = {
    dut.io.debugRegisterAddress.poke(register.U)
    dut.io.debugRegisterData.expect((value & BigInt("ffffffff", 16)).U)
  }

  private def mergeWrite(oldValue: BigInt, newValue: BigInt, mask: BigInt): BigInt =
    (0 until 4).foldLeft(oldValue) { (value, lane) =>
      if (mask.testBit(lane)) {
        val byteMask = BigInt(0xff) << (lane * 8)
        (value & ~byteMask) | (newValue & byteMask)
      } else {
        value
      }
    } & BigInt("ffffffff", 16)

  private def runCycle(
      dut: PipelinedRiscVCore,
      program: Map[BigInt, BigInt],
      memory: collection.mutable.Map[BigInt, BigInt]
  ): Boolean = {
    val fetchAddress = dut.io.instructionAddress.peek().litValue
    dut.io.instruction.poke(program.getOrElse(fetchAddress, Nop).U)

    val dataAddress = dut.io.dataAddress.peek().litValue
    dut.io.dataReadData.poke(memory.getOrElse(dataAddress, BigInt(0)).U)
    if (dut.io.dataWriteEnable.peek().litToBoolean) {
      val oldValue = memory.getOrElse(dataAddress, BigInt(0))
      val newValue = dut.io.dataWriteData.peek().litValue
      val mask = dut.io.dataWriteMask.peek().litValue
      memory(dataAddress) = mergeWrite(oldValue, newValue, mask)
    }

    dut.io.illegalInstruction.expect(false.B)
    val stalled = dut.io.pipelineStall.peek().litToBoolean
    dut.clock.step()
    stalled
  }

  "PipelinedRiscVCore" - {
    "forwards dependent arithmetic results" in {
      simulate(new PipelinedRiscVCore) { dut =>
        initialize(dut)
        val program = Map[BigInt, BigInt](
          BigInt(0x00) -> iType(5, 0, 0, 1),       // addi x1, x0, 5
          BigInt(0x04) -> iType(7, 1, 0, 2),       // addi x2, x1, 7
          BigInt(0x08) -> rType(0, 1, 2, 0, 3),    // add  x3, x2, x1
          BigInt(0x0c) -> iType(1, 3, 1, 4)        // slli x4, x3, 1
        )
        val memory = collection.mutable.Map.empty[BigInt, BigInt]

        for (_ <- 0 until 8) {
          runCycle(dut, program, memory) mustBe false
        }

        expectRegister(dut, 1, 5)
        expectRegister(dut, 2, 12)
        expectRegister(dut, 3, 17)
        expectRegister(dut, 4, 34)
      }
    }

    "stalls once for a load-use dependency" in {
      simulate(new PipelinedRiscVCore) { dut =>
        initialize(dut)
        val program = Map[BigInt, BigInt](
          BigInt(0x00) -> iType(42, 0, 0, 1),      // addi x1, x0, 42
          BigInt(0x04) -> sType(16, 1, 0, 2),      // sw   x1, 16(x0)
          BigInt(0x08) -> iType(16, 0, 2, 2, 3),   // lw   x2, 16(x0)
          BigInt(0x0c) -> rType(0, 2, 2, 0, 3)     // add  x3, x2, x2
        )
        val memory = collection.mutable.Map.empty[BigInt, BigInt]

        val stalls = (0 until 10).count(_ => runCycle(dut, program, memory))

        stalls mustBe 1
        memory(16) mustBe 42
        expectRegister(dut, 2, 42)
        expectRegister(dut, 3, 84)
      }
    }

    "flushes the sequential instruction after a taken branch" in {
      simulate(new PipelinedRiscVCore) { dut =>
        initialize(dut)
        val program = Map[BigInt, BigInt](
          BigInt(0x00) -> iType(1, 0, 0, 1),       // addi x1, x0, 1
          BigInt(0x04) -> bType(8, 1, 1, 0),       // beq  x1, x1, +8
          BigInt(0x08) -> iType(99, 0, 0, 2),      // must be flushed
          BigInt(0x0c) -> iType(7, 0, 0, 2)
        )
        val memory = collection.mutable.Map.empty[BigInt, BigInt]

        for (_ <- 0 until 9) {
          runCycle(dut, program, memory)
        }

        expectRegister(dut, 2, 7)
      }
    }

    "loads and stores signed and unsigned bytes and halfwords" in {
      simulate(new PipelinedRiscVCore) { dut =>
        initialize(dut)
        val program = Map[BigInt, BigInt](
          BigInt(0x00) -> iType(-128, 0, 0, 1),           // addi x1, x0, -128
          BigInt(0x04) -> sType(1, 1, 0, 0),             // sb   x1, 1(x0)
          BigInt(0x08) -> iType(1, 0, 4, 2, 0x03),       // lbu  x2, 1(x0)
          BigInt(0x0c) -> iType(1, 0, 0, 3, 0x03),       // lb   x3, 1(x0)
          BigInt(0x10) -> iType(-2, 0, 0, 4),            // addi x4, x0, -2
          BigInt(0x14) -> sType(2, 4, 0, 1),             // sh   x4, 2(x0)
          BigInt(0x18) -> iType(2, 0, 5, 5, 0x03),       // lhu  x5, 2(x0)
          BigInt(0x1c) -> iType(2, 0, 1, 6, 0x03)        // lh   x6, 2(x0)
        )
        val memory = collection.mutable.Map.empty[BigInt, BigInt]

        for (_ <- 0 until 13) {
          runCycle(dut, program, memory)
        }

        memory(0) mustBe BigInt("fffe8000", 16)
        expectRegister(dut, 2, 128)
        expectRegister(dut, 3, BigInt("ffffff80", 16))
        expectRegister(dut, 5, 65534)
        expectRegister(dut, 6, BigInt("fffffffe", 16))
      }
    }

    "executes the complete RV32M extension including division edge cases" in {
      simulate(new PipelinedRiscVCore) { dut =>
        initialize(dut)
        val program = Map[BigInt, BigInt](
          BigInt(0x00) -> iType(-7, 0, 0, 1),             // addi x1, x0, -7
          BigInt(0x04) -> iType(3, 0, 0, 2),              // addi x2, x0, 3
          BigInt(0x08) -> rType(1, 2, 1, 0, 3),           // mul    x3, x1, x2
          BigInt(0x0c) -> rType(1, 2, 1, 1, 4),           // mulh   x4, x1, x2
          BigInt(0x10) -> rType(1, 2, 1, 2, 5),           // mulhsu x5, x1, x2
          BigInt(0x14) -> rType(1, 2, 1, 3, 6),           // mulhu  x6, x1, x2
          BigInt(0x18) -> rType(1, 2, 1, 4, 7),           // div    x7, x1, x2
          BigInt(0x1c) -> rType(1, 2, 1, 5, 8),           // divu   x8, x1, x2
          BigInt(0x20) -> rType(1, 2, 1, 6, 9),           // rem    x9, x1, x2
          BigInt(0x24) -> rType(1, 2, 1, 7, 10),          // remu   x10, x1, x2
          BigInt(0x28) -> rType(1, 0, 1, 4, 11),          // div    x11, x1, x0
          BigInt(0x2c) -> rType(1, 0, 1, 6, 12),          // rem    x12, x1, x0
          BigInt(0x30) -> uType(0x80000, 13),             // lui    x13, 0x80000
          BigInt(0x34) -> iType(-1, 0, 0, 14),            // addi   x14, x0, -1
          BigInt(0x38) -> rType(1, 14, 13, 4, 15),        // div    x15, x13, x14
          BigInt(0x3c) -> rType(1, 14, 13, 6, 16)         // rem    x16, x13, x14
        )
        val memory = collection.mutable.Map.empty[BigInt, BigInt]

        for (_ <- 0 until 24) {
          runCycle(dut, program, memory)
        }

        expectRegister(dut, 3, BigInt("ffffffeb", 16))
        expectRegister(dut, 4, BigInt("ffffffff", 16))
        expectRegister(dut, 5, BigInt("ffffffff", 16))
        expectRegister(dut, 6, 2)
        expectRegister(dut, 7, BigInt("fffffffe", 16))
        expectRegister(dut, 8, BigInt("55555553", 16))
        expectRegister(dut, 9, BigInt("ffffffff", 16))
        expectRegister(dut, 10, 0)
        expectRegister(dut, 11, BigInt("ffffffff", 16))
        expectRegister(dut, 12, BigInt("fffffff9", 16))
        expectRegister(dut, 15, BigInt("80000000", 16))
        expectRegister(dut, 16, 0)
      }
    }
  }
}
