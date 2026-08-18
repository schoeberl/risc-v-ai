package riscvai

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

import java.nio.file.{Files, Path, Paths}
import java.util.Locale
import scala.collection.mutable

/** Short CoreMark performance/CRC run for pipeline comparisons.
  *
  * Run through `make coremark`. It is intentionally not an official score:
  * one iteration is practical in RTL simulation but does not satisfy the
  * benchmark's ten-second reporting rule.
  */
class CoreMarkBenchmarkSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val ResultStatus = BigInt("ffff0000", 16)
  private val ResultCycles = ResultStatus + 4
  private val ResultInstret = ResultStatus + 8
  private val ResultIterations = ResultStatus + 12
  private val ResultDone = ResultStatus + 16
  private val DoneMagic = BigInt("434d4f4b", 16)
  private val WordMask = BigInt("ffffffff", 16)

  private case class Pending(
      address: BigInt,
      write: Boolean,
      writeData: BigInt,
      writeMask: BigInt,
      readyCycle: Long
  )

  private case class Result(
      pipeline: String,
      latency: Int,
      cycles: BigInt,
      instructions: BigInt,
      iterations: BigInt,
      reads: Long,
      writes: Long,
      fmaxMHz: Double = 0.0
  ) {
    def cpi: Double = cycles.toDouble / instructions.toDouble
    def cyclesPerIteration: Long = cycles.toLong / iterations.toLong
    def projectedIterationsPerSecond: Double = fmaxMHz * 1000000.0 / cyclesPerIteration
  }

  private case class Configuration(
      name: String,
      fmaxMHz: Double,
      generate: () => CachedRvaiPipeline
  )

  private def loadBinary(path: Path): mutable.Map[BigInt, BigInt] = {
    val bytes = Files.readAllBytes(path)
    mutable.Map.from((bytes.indices by 4).map { offset =>
      val word = (0 until 4).foldLeft(BigInt(0)) { (value, lane) =>
        val index = offset + lane
        val byte = if (index < bytes.length) bytes(index) & 0xff else 0
        value | (BigInt(byte) << (8 * lane))
      }
      BigInt(offset) -> word
    })
  }

  private def mergeWrite(oldValue: BigInt, newValue: BigInt, mask: BigInt): BigInt =
    (0 until 4).foldLeft(oldValue) { (value, lane) =>
      if (mask.testBit(lane)) {
        val byteMask = BigInt(0xff) << (lane * 8)
        (value & ~byteMask) | (newValue & byteMask)
      } else value
    } & WordMask

  private def run(
      dut: CachedRvaiPipeline,
      binary: Path,
      latency: Int,
      maximumCycles: Long = 5000000L
  ): Result = {
    require(latency >= 0)
    val memory = loadBinary(binary)
    var pending = Option.empty[Pending]
    var cycle = 0L
    var reads = 0L
    var writes = 0L
    var finished = false
    val retirementTrace = mutable.Queue.empty[(BigInt, BigInt)]

    dut.io.memoryReady.poke(false.B)
    dut.io.memoryReadData.poke(0.U)
    dut.io.debugRegisterAddress.poke(0.U)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)

    def complete(transaction: Pending): Unit = {
      dut.io.memoryReadData.poke(memory.getOrElse(transaction.address, BigInt(0)).U)
      dut.io.memoryReady.poke(true.B)
      if (transaction.write) {
        val updated = mergeWrite(
          memory.getOrElse(transaction.address, BigInt(0)),
          transaction.writeData,
          transaction.writeMask
        )
        memory(transaction.address) = updated
        writes += 1
        if (transaction.address == ResultDone && updated == DoneMagic) finished = true
      } else reads += 1
    }

    while (!finished && cycle < maximumCycles) {
      dut.io.memoryReady.poke(false.B)
      dut.io.memoryReadData.poke(0.U)

      pending match {
        case Some(transaction) =>
          dut.io.memoryRequest.expect(true.B)
          dut.io.memoryAddress.expect(transaction.address.U)
          dut.io.memoryWrite.expect(transaction.write.B)
          if (cycle == transaction.readyCycle) {
            complete(transaction)
            pending = None
          }
        case None if dut.io.memoryRequest.peek().litToBoolean =>
          val transaction = Pending(
            dut.io.memoryAddress.peek().litValue,
            dut.io.memoryWrite.peek().litToBoolean,
            dut.io.memoryWriteData.peek().litValue,
            dut.io.memoryWriteMask.peek().litValue,
            cycle + latency
          )
          if (latency == 0) complete(transaction)
          else pending = Some(transaction)
        case None =>
      }

      if (dut.io.retiredValid.peek().litToBoolean) {
        retirementTrace.enqueue(
          dut.io.retiredPc.peek().litValue -> dut.io.retiredInstruction.peek().litValue
        )
        if (retirementTrace.size > 24) retirementTrace.dequeue()
      }
      if (dut.io.illegalInstruction.peek().litToBoolean) {
        fail(
          f"illegal instruction at simulation cycle $cycle%d, " +
            f"PC 0x${dut.io.retiredPc.peek().litValue}%08x, " +
            f"instruction 0x${dut.io.retiredInstruction.peek().litValue}%08x, " +
            retirementTrace.map { case (pc, instruction) =>
              f"$pc%08x:$instruction%08x"
            }.mkString("recent [", ", ", "]")
        )
      }
      dut.clock.step()
      cycle += 1
    }

    withClue(s"CoreMark did not finish within $maximumCycles cycles: ") {
      finished mustBe true
    }
    withClue("CoreMark algorithm/data-type CRC validation failed: ") {
      memory.getOrElse(ResultStatus, BigInt(0)) mustBe 1
    }

    Result(
      pipeline = "",
      latency = latency,
      cycles = memory(ResultCycles),
      instructions = memory(ResultInstret),
      iterations = memory(ResultIterations),
      reads = reads,
      writes = writes
    )
  }

  "CoreMark compares cached pipeline organizations" in {
    val binaryName = sys.env.getOrElse(
      "COREMARK_BIN",
      cancel("COREMARK_BIN is not set; run this benchmark through `make coremark`")
    )
    val binary = Paths.get(binaryName).toAbsolutePath.normalize()
    Files.isRegularFile(binary) mustBe true

    val allConfigurations = Seq(
      Configuration("Four stages", 49.26, () => new CachedRvaiFourStages()),
      Configuration("Three stages", 45.49, () => new CachedRvaiThreeStages()),
      Configuration(
        "Three stages + fetch predecode",
        43.34,
        () => new CachedRvaiThreeStagesPredecode()
      )
    )
    val configurations = sys.env.get("COREMARK_PIPELINE") match {
      case Some(selected) => allConfigurations.filter(_.name == selected)
      case None => allConfigurations
    }
    configurations.nonEmpty mustBe true
    val latencies = sys.env.get("COREMARK_LATENCY")
      .map(value => Seq(value.toInt))
      .getOrElse(Seq(0, 5))
    val results = for {
      latency <- latencies
      configuration <- configurations
    } yield {
      var measured = Option.empty[Result]
      simulate(configuration.generate()) { dut =>
        measured = Some(run(dut, binary, latency))
      }
      measured.get.copy(pipeline = configuration.name, fmaxMHz = configuration.fmaxMHz)
    }

    println("\nCoreMark short RTL-simulation comparison (CRC checked, not an official score)")
    println("| Pipeline | Memory wait | Cycles/iteration | Instructions | CPI | Projected iterations/s | Reads | Writes |")
    println("|---|---:|---:|---:|---:|---:|---:|---:|")
    results.foreach { result =>
      val cpi = String.format(Locale.ROOT, "%.3f", Double.box(result.cpi))
      val projected = String.format(
        Locale.ROOT,
        "%.2f",
        Double.box(result.projectedIterationsPerSecond)
      )
      println(s"| ${result.pipeline} | ${result.latency} | " +
        s"${result.cyclesPerIteration} | ${result.instructions} | $cpi | " +
        s"$projected | ${result.reads} | ${result.writes} |")
    }
  }
}
