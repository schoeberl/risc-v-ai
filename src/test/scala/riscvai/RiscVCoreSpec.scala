package riscvai

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class RiscVCoreSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val Nop = BigInt("00000013", 16) // ADDI x0, x0, 0

  private def bits(value: Int, width: Int): BigInt =
    BigInt(value) & ((BigInt(1) << width) - 1)

  private def iType(imm: Int, rs1: Int, funct3: Int, rd: Int, opcode: Int = 0x13): BigInt =
    (bits(imm, 12) << 20) | (BigInt(rs1) << 15) | (BigInt(funct3) << 12) |
      (BigInt(rd) << 7) | BigInt(opcode)

  private def rType(funct7: Int, rs2: Int, rs1: Int, funct3: Int, rd: Int): BigInt =
    (BigInt(funct7) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | (BigInt(rd) << 7) | BigInt(0x33)

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

  private def jType(offset: Int, rd: Int): BigInt = {
    require(offset % 2 == 0)
    val encoded = bits(offset, 21)
    (((encoded >> 20) & 1) << 31) | (((encoded >> 1) & 0x3ff) << 21) |
      (((encoded >> 11) & 1) << 20) | (((encoded >> 12) & 0xff) << 12) |
      (BigInt(rd) << 7) | BigInt(0x6f)
  }

  private def initialize(dut: RiscVCore): Unit = {
    dut.io.instruction.poke(Nop.U)
    dut.io.dataReadData.poke(0.U)
    dut.io.debugRegisterAddress.poke(0.U)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
  }

  private def runProgram(dut: RiscVCore, program: Map[BigInt, BigInt], cycles: Int): Unit = {
    for (_ <- 0 until cycles) {
      val pc = dut.io.instructionAddress.peek().litValue
      dut.io.instruction.poke(program.getOrElse(pc, Nop).U)
      dut.io.dataReadData.poke(0.U)
      dut.io.illegalInstruction.expect(false.B, s"illegal instruction at PC 0x${pc.toString(16)}")
      dut.clock.step()
    }
  }

  private def expectRegister(dut: RiscVCore, register: Int, value: BigInt): Unit = {
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

  "RiscVCore" - {
    "executes integer arithmetic and keeps x0 hardwired to zero" in {
      simulate(new RiscVCore) { dut =>
        initialize(dut)
        val program = Map[BigInt, BigInt](
          BigInt(0x00) -> iType(5, 0, 0, 1),              // addi x1, x0, 5
          BigInt(0x04) -> iType(7, 0, 0, 2),              // addi x2, x0, 7
          BigInt(0x08) -> rType(0x00, 2, 1, 0, 3),        // add  x3, x1, x2
          BigInt(0x0c) -> rType(0x20, 1, 3, 0, 4),        // sub  x4, x3, x1
          BigInt(0x10) -> iType(2, 4, 1, 5),              // slli x5, x4, 2
          BigInt(0x14) -> iType(3, 5, 6, 6),              // ori  x6, x5, 3
          BigInt(0x18) -> rType(0x00, 3, 6, 7, 7),        // and  x7, x6, x3
          BigInt(0x1c) -> iType(6, 1, 2, 8),              // slti x8, x1, 6
          BigInt(0x20) -> iType(99, 0, 0, 0)              // addi x0, x0, 99
        )

        runProgram(dut, program, cycles = 9)

        expectRegister(dut, 0, 0)
        expectRegister(dut, 3, 12)
        expectRegister(dut, 4, 7)
        expectRegister(dut, 5, 28)
        expectRegister(dut, 6, 31)
        expectRegister(dut, 7, 12)
        expectRegister(dut, 8, 1)
      }
    }

    "takes branches and writes jump return addresses" in {
      simulate(new RiscVCore) { dut =>
        initialize(dut)
        val program = Map[BigInt, BigInt](
          BigInt(0x00) -> iType(1, 0, 0, 1),              // addi x1, x0, 1
          BigInt(0x04) -> bType(8, 1, 1, 0),              // beq  x1, x1, +8
          BigInt(0x08) -> iType(99, 0, 0, 2),             // skipped
          BigInt(0x0c) -> iType(7, 0, 0, 2),
          BigInt(0x10) -> jType(8, 3),                     // jal  x3, +8
          BigInt(0x14) -> iType(99, 0, 0, 4),             // skipped
          BigInt(0x18) -> iType(11, 0, 0, 4),
          BigInt(0x1c) -> iType(36, 0, 0, 5, 0x67),       // jalr x5, 36(x0)
          BigInt(0x20) -> iType(99, 0, 0, 6),             // skipped
          BigInt(0x24) -> iType(13, 0, 0, 6)
        )

        runProgram(dut, program, cycles = 7)

        expectRegister(dut, 2, 7)
        expectRegister(dut, 3, 20)
        expectRegister(dut, 4, 11)
        expectRegister(dut, 5, 32)
        expectRegister(dut, 6, 13)
        dut.io.instructionAddress.expect(40.U)
      }
    }

    "stores and loads a word through the data-memory interface" in {
      simulate(new RiscVCore) { dut =>
        initialize(dut)
        val program = Map[BigInt, BigInt](
          BigInt(0x00) -> iType(42, 0, 0, 1),             // addi x1, x0, 42
          BigInt(0x04) -> sType(16, 1, 0, 2),             // sw   x1, 16(x0)
          BigInt(0x08) -> iType(16, 0, 2, 2, 0x03)        // lw   x2, 16(x0)
        )
        var memory = Map.empty[BigInt, BigInt]

        for (_ <- 0 until 3) {
          val pc = dut.io.instructionAddress.peek().litValue
          dut.io.instruction.poke(program(pc).U)
          val address = dut.io.dataAddress.peek().litValue
          dut.io.dataReadData.poke(memory.getOrElse(address, BigInt(0)).U)
          dut.io.illegalInstruction.expect(false.B)
          if (dut.io.dataWriteEnable.peek().litToBoolean) {
            val oldValue = memory.getOrElse(address, BigInt(0))
            val newValue = dut.io.dataWriteData.peek().litValue
            val mask = dut.io.dataWriteMask.peek().litValue
            memory += address -> mergeWrite(oldValue, newValue, mask)
          }
          dut.clock.step()
        }

        memory(16) mustBe 42
        expectRegister(dut, 2, 42)
      }
    }

    "loads and stores signed and unsigned bytes and halfwords" in {
      simulate(new RiscVCore) { dut =>
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
        var memory = Map.empty[BigInt, BigInt]

        for (_ <- 0 until program.size) {
          val pc = dut.io.instructionAddress.peek().litValue
          dut.io.instruction.poke(program(pc).U)
          val address = dut.io.dataAddress.peek().litValue
          dut.io.dataReadData.poke(memory.getOrElse(address, BigInt(0)).U)
          dut.io.illegalInstruction.expect(false.B)
          if (dut.io.dataWriteEnable.peek().litToBoolean) {
            val oldValue = memory.getOrElse(address, BigInt(0))
            val newValue = dut.io.dataWriteData.peek().litValue
            val mask = dut.io.dataWriteMask.peek().litValue
            memory += address -> mergeWrite(oldValue, newValue, mask)
          }
          dut.clock.step()
        }

        memory(0) mustBe BigInt("fffe8000", 16)
        expectRegister(dut, 2, 128)
        expectRegister(dut, 3, BigInt("ffffff80", 16))
        expectRegister(dut, 5, 65534)
        expectRegister(dut, 6, BigInt("fffffffe", 16))
      }
    }
  }
}
