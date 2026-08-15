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

  private def uType(imm20: Int, rd: Int): BigInt =
    (bits(imm20, 20) << 12) | (BigInt(rd) << 7) | BigInt(0x37)

  private def aType(funct5: Int, rs2: Int, rs1: Int, rd: Int): BigInt =
    (BigInt(funct5) << 27) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(2) << 12) | (BigInt(rd) << 7) | BigInt(0x2f)

  private def csrType(csr: Int, source: Int, funct3: Int, rd: Int): BigInt =
    (BigInt(csr) << 20) | (BigInt(source) << 15) | (BigInt(funct3) << 12) |
      (BigInt(rd) << 7) | BigInt(0x73)

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

    "executes the complete RV32M extension including division edge cases" in {
      simulate(new RiscVCore) { dut =>
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

        runProgram(dut, program, cycles = program.size)

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

    "executes single-hart RV32A atomics and tracks reservations" in {
      simulate(new RiscVCore) { dut =>
        initialize(dut)
        val program = Map[BigInt, BigInt](
          BigInt(0x00) -> iType(0, 0, 0, 1),              // addi x1, x0, 0
          BigInt(0x04) -> iType(5, 0, 0, 2),              // addi x2, x0, 5
          BigInt(0x08) -> iType(-1, 0, 0, 3),             // addi x3, x0, -1
          BigInt(0x0c) -> iType(9, 0, 0, 4),              // addi x4, x0, 9
          BigInt(0x10) -> aType(0x00, 2, 1, 5),           // amoadd.w  x5, x2, (x1)
          BigInt(0x14) -> aType(0x01, 2, 1, 6),           // amoswap.w x6, x2, (x1)
          BigInt(0x18) -> aType(0x04, 2, 1, 7),           // amoxor.w  x7, x2, (x1)
          BigInt(0x1c) -> aType(0x0c, 2, 1, 8),           // amoand.w  x8, x2, (x1)
          BigInt(0x20) -> aType(0x08, 2, 1, 9),           // amoor.w   x9, x2, (x1)
          BigInt(0x24) -> aType(0x10, 3, 1, 10),          // amomin.w  x10, x3, (x1)
          BigInt(0x28) -> aType(0x14, 2, 1, 11),          // amomax.w  x11, x2, (x1)
          BigInt(0x2c) -> aType(0x18, 3, 1, 12),          // amominu.w x12, x3, (x1)
          BigInt(0x30) -> aType(0x1c, 3, 1, 13),          // amomaxu.w x13, x3, (x1)
          BigInt(0x34) -> aType(0x02, 0, 1, 14),          // lr.w      x14, (x1)
          BigInt(0x38) -> aType(0x03, 4, 1, 15),          // sc.w      x15, x4, (x1)
          BigInt(0x3c) -> aType(0x03, 2, 1, 16),          // sc.w      x16, x2, (x1)
          BigInt(0x40) -> aType(0x02, 0, 1, 17),          // lr.w      x17, (x1)
          BigInt(0x44) -> sType(4, 2, 1, 2),              // sw        x2, 4(x1)
          BigInt(0x48) -> aType(0x03, 4, 1, 18)           // sc.w      x18, x4, (x1)
        )
        var memory = Map[BigInt, BigInt](BigInt(0) -> BigInt(10))

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

        expectRegister(dut, 5, 10)
        expectRegister(dut, 6, 15)
        expectRegister(dut, 7, 5)
        expectRegister(dut, 8, 0)
        expectRegister(dut, 9, 0)
        expectRegister(dut, 10, 5)
        expectRegister(dut, 11, BigInt("ffffffff", 16))
        expectRegister(dut, 12, 5)
        expectRegister(dut, 13, 5)
        expectRegister(dut, 14, BigInt("ffffffff", 16))
        expectRegister(dut, 15, 0)
        expectRegister(dut, 16, 1)
        expectRegister(dut, 17, 9)
        expectRegister(dut, 18, 1)
        memory(0) mustBe 9
        memory(4) mustBe 5
      }
    }

    "executes fences and all CSR instruction forms" in {
      simulate(new RiscVCore) { dut =>
        initialize(dut)
        val program = Map[BigInt, BigInt](
          BigInt(0x00) -> iType(0x88, 0, 0, 1),          // addi   x1, x0, 0x88
          BigInt(0x04) -> csrType(0x300, 1, 1, 2),       // csrrw  x2, mstatus, x1
          BigInt(0x08) -> csrType(0x300, 0, 2, 3),       // csrrs  x3, mstatus, x0
          BigInt(0x0c) -> csrType(0x300, 8, 7, 4),       // csrrci x4, mstatus, 8
          BigInt(0x10) -> csrType(0x300, 0, 2, 5),       // csrrs  x5, mstatus, x0
          BigInt(0x14) -> csrType(0x340, 31, 5, 6),      // csrrwi x6, mscratch, 31
          BigInt(0x18) -> csrType(0x340, 0, 2, 7),       // csrrs  x7, mscratch, x0
          BigInt(0x1c) -> BigInt("0000000f", 16),        // fence
          BigInt(0x20) -> BigInt("0000100f", 16),        // fence.i
          BigInt(0x24) -> iType(1, 0, 0, 8),             // addi   x8, x0, 1
          BigInt(0x28) -> csrType(0x301, 0, 2, 9),       // csrrs  x9, misa, x0
          BigInt(0x2c) -> csrType(0xf14, 0, 2, 10),      // csrrs  x10, mhartid, x0
          BigInt(0x30) -> iType(1, 0, 0, 11),            // addi   x11, x0, 1
          BigInt(0x34) -> csrType(0x340, 11, 3, 12),     // csrrc  x12, mscratch, x11
          BigInt(0x38) -> csrType(0x340, 1, 6, 13),      // csrrsi x13, mscratch, 1
          BigInt(0x3c) -> csrType(0x340, 0, 2, 14)       // csrrs  x14, mscratch, x0
        )

        runProgram(dut, program, cycles = program.size)

        expectRegister(dut, 2, 0)
        expectRegister(dut, 3, 0x88)
        expectRegister(dut, 4, 0x88)
        expectRegister(dut, 5, 0x80)
        expectRegister(dut, 6, 0)
        expectRegister(dut, 7, 31)
        expectRegister(dut, 8, 1)
        expectRegister(dut, 9, BigInt("40001101", 16))
        expectRegister(dut, 10, 0)
        expectRegister(dut, 12, 31)
        expectRegister(dut, 13, 30)
        expectRegister(dut, 14, 31)
      }
    }
  }
}
