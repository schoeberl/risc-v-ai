package riscvai

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class CachedPipelinedRiscVCoreSpec extends AnyFreeSpec with Matchers with ChiselSim {
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

  private def aType(funct5: Int, rs2: Int, rs1: Int, rd: Int): BigInt =
    (BigInt(funct5) << 27) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(2) << 12) | (BigInt(rd) << 7) | BigInt(0x2f)

  private def jType(offset: Int, rd: Int): BigInt = {
    require(offset % 2 == 0)
    val encoded = bits(offset, 21)
    (((encoded >> 20) & 1) << 31) | (((encoded >> 1) & 0x3ff) << 21) |
      (((encoded >> 11) & 1) << 20) | (((encoded >> 12) & 0xff) << 12) |
      (BigInt(rd) << 7) | BigInt(0x6f)
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

  private case class Pending(
      address: BigInt,
      write: Boolean,
      writeData: BigInt,
      writeMask: BigInt,
      remaining: Int
  )

  private case class RunResult(readTransfers: Int, writeTransfers: Int, stallCycles: Int)

  private def initialize(dut: CachedPipelinedRiscVCore): Unit = {
    dut.io.memoryReady.poke(false.B)
    dut.io.memoryReadData.poke(0.U)
    dut.io.debugRegisterAddress.poke(0.U)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
  }

  private def expectRegister(
      dut: CachedPipelinedRiscVCore,
      register: Int,
      value: BigInt
  ): Unit = {
    dut.io.debugRegisterAddress.poke(register.U)
    dut.io.debugRegisterData.expect((value & BigInt("ffffffff", 16)).U)
  }

  private def runWithMemory(
      dut: CachedPipelinedRiscVCore,
      memory: collection.mutable.Map[BigInt, BigInt],
      cycles: Int,
      latency: Int = 2
  ): RunResult = {
    var pending = Option.empty[Pending]
    var readTransfers = 0
    var writeTransfers = 0
    var stallCycles = 0

    for (_ <- 0 until cycles) {
      dut.io.memoryReady.poke(false.B)
      dut.io.memoryReadData.poke(0.U)
      if (dut.io.pipelineStall.peek().litToBoolean) stallCycles += 1

      var accepted = false
      pending.foreach { transaction =>
        dut.io.memoryRequest.expect(true.B)
        dut.io.memoryAddress.expect(transaction.address.U)
        dut.io.memoryWrite.expect(transaction.write.B)
        if (transaction.write) {
          dut.io.memoryWriteData.expect(transaction.writeData.U)
          dut.io.memoryWriteMask.expect(transaction.writeMask.U)
        }
        if (transaction.remaining == 0) {
          dut.io.memoryReadData.poke(memory.getOrElse(transaction.address, BigInt(0)).U)
          dut.io.memoryReady.poke(true.B)
          if (transaction.write) {
            val oldValue = memory.getOrElse(transaction.address, BigInt(0))
            memory(transaction.address) =
              mergeWrite(oldValue, transaction.writeData, transaction.writeMask)
            writeTransfers += 1
          } else {
            readTransfers += 1
          }
          accepted = true
        }
      }

      val newRequest = pending.isEmpty && dut.io.memoryRequest.peek().litToBoolean
      val captured = if (newRequest) {
        Some(Pending(
          dut.io.memoryAddress.peek().litValue,
          dut.io.memoryWrite.peek().litToBoolean,
          dut.io.memoryWriteData.peek().litValue,
          dut.io.memoryWriteMask.peek().litValue,
          latency - 1
        ))
      } else {
        None
      }

      dut.io.illegalInstruction.expect(false.B)
      dut.clock.step()

      pending = pending match {
        case Some(_) if accepted => None
        case Some(transaction) => Some(transaction.copy(remaining = transaction.remaining - 1))
        case None => captured
      }
    }

    RunResult(readTransfers, writeTransfers, stallCycles)
  }

  "CachedPipelinedRiscVCore" - {
    "refills both caches and arbitrates a store miss followed by a dependent load" in {
      simulate(new CachedPipelinedRiscVCore(cacheBytes = 256, lineBytes = 16)) { dut =>
        initialize(dut)
        val memory = collection.mutable.Map[BigInt, BigInt](
          BigInt(0x00) -> iType(64, 0, 0, 1),           // addi x1, x0, 64
          BigInt(0x04) -> iType(42, 0, 0, 2),           // addi x2, x0, 42
          BigInt(0x08) -> sType(0, 2, 1, 2),            // sw   x2, 0(x1)
          BigInt(0x0c) -> iType(0, 1, 2, 3, 0x03),      // lw   x3, 0(x1)
          BigInt(0x10) -> rType(0, 2, 3, 0, 4),         // add  x4, x3, x2
          BigInt(0x14) -> jType(0, 0)                   // loop
        )

        val result = runWithMemory(dut, memory, cycles = 180)

        memory(64) mustBe 42
        expectRegister(dut, 3, 42)
        expectRegister(dut, 4, 84)
        result.writeTransfers mustBe 1
        result.readTransfers must be >= 12
        result.readTransfers must be <= 16
        result.stallCycles must be > 0
      }
    }

    "refills before an atomic read-modify-write and preserves the old value" in {
      simulate(new CachedPipelinedRiscVCore(cacheBytes = 256, lineBytes = 16)) { dut =>
        initialize(dut)
        val memory = collection.mutable.Map[BigInt, BigInt](
          BigInt(0x00) -> iType(64, 0, 0, 1),           // addi    x1, x0, 64
          BigInt(0x04) -> iType(5, 0, 0, 2),            // addi    x2, x0, 5
          BigInt(0x08) -> aType(0x00, 2, 1, 3),         // amoadd.w x3, x2, (x1)
          BigInt(0x0c) -> iType(0, 1, 2, 4, 0x03),      // lw      x4, 0(x1)
          BigInt(0x10) -> jType(0, 0),                  // loop
          BigInt(0x40) -> BigInt(10)
        )

        val result = runWithMemory(dut, memory, cycles = 180)

        memory(64) mustBe 15
        expectRegister(dut, 3, 10)
        expectRegister(dut, 4, 15)
        result.writeTransfers mustBe 1
        result.stallCycles must be > 0
      }
    }

    "applies byte write masks through synchronous cache data storage" in {
      simulate(new CachedPipelinedRiscVCore(cacheBytes = 256, lineBytes = 16)) { dut =>
        initialize(dut)
        val memory = collection.mutable.Map[BigInt, BigInt](
          BigInt(0x00) -> iType(64, 0, 0, 1),           // addi x1, x0, 64
          BigInt(0x04) -> iType(127, 0, 0, 2),          // addi x2, x0, 127
          BigInt(0x08) -> sType(1, 2, 1, 0),            // sb   x2, 1(x1)
          BigInt(0x0c) -> iType(1, 1, 4, 3, 0x03),      // lbu  x3, 1(x1)
          BigInt(0x10) -> iType(0, 1, 2, 4, 0x03),      // lw   x4, 0(x1)
          BigInt(0x14) -> jType(0, 0),                  // loop
          BigInt(0x40) -> BigInt("11223344", 16)
        )

        val result = runWithMemory(dut, memory, cycles = 220)

        memory(64) mustBe BigInt("11227f44", 16)
        expectRegister(dut, 3, 127)
        expectRegister(dut, 4, BigInt("11227f44", 16))
        result.writeTransfers mustBe 1
      }
    }

    "invalidates and refills the instruction cache after FENCE.I" in {
      simulate(new CachedPipelinedRiscVCore(cacheBytes = 256, lineBytes = 16)) { dut =>
        initialize(dut)
        val memory = collection.mutable.Map[BigInt, BigInt](
          BigInt(0x00) -> BigInt("0000100f", 16),        // fence.i
          BigInt(0x04) -> iType(7, 0, 0, 1),             // addi x1, x0, 7
          BigInt(0x08) -> jType(0, 0),                   // loop
          BigInt(0x0c) -> BigInt("00000013", 16)        // nop
        )

        val result = runWithMemory(dut, memory, cycles = 120)

        expectRegister(dut, 1, 7)
        result.readTransfers must be >= 8
        result.readTransfers must be <= 12
      }
    }
  }
}
