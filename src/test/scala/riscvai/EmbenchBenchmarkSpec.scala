package riscvai

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

import java.nio.file.{Files, Path, Paths}
import java.util.Locale
import scala.collection.mutable

/** Short, correctness-checked Embench-IoT runs for pipeline CPI comparison.
  *
  * The bare-metal port executes one fundamental benchmark repetition after one
  * warm-up repetition. Run the complete matrix through `make embench`, or use
  * EMBENCH_BENCHMARK and EMBENCH_PIPELINE to select one case.
  */
class EmbenchBenchmarkSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val ResultStatus = BigInt("ffff0000", 16)
  private val ResultCycles = ResultStatus + 4
  private val ResultInstret = ResultStatus + 8
  private val ResultDone = ResultStatus + 12
  private val ResultValue = ResultStatus + 16
  private val ResultActive = ResultStatus + 20
  private val DoneMagic = BigInt("454d4f4b", 16)
  private val WordMask = BigInt("ffffffff", 16)

  private val allBenchmarkNames = Seq(
    "aha-mont64",
    "crc32",
    "cubic",
    "edn",
    "huffbench",
    "matmult-int",
    "minver",
    "nbody",
    "nettle-aes",
    "nettle-sha256",
    "nsichneu",
    "picojpeg",
    "qrduino",
    "sglib-combined",
    "slre",
    "st",
    "statemate",
    "ud",
    "wikisort"
  )

  private val defaultBenchmarkNames = Seq(
    "crc32",
    "edn",
    "huffbench",
    "matmult-int",
    "nettle-aes",
    "nettle-sha256",
    "slre",
    "statemate"
  )

  private case class Transaction(
      address: BigInt,
      write: Boolean,
      writeData: BigInt,
      writeMask: BigInt
  )

  private case class Result(
      benchmark: String,
      pipeline: String,
      cycles: BigInt,
      instructions: BigInt,
      instructionReads: Long,
      dataReads: Long,
      writes: Long
  ) {
    def cpi: Double = cycles.toDouble / instructions.toDouble
  }

  private case class Configuration(
      name: String,
      generate: () => CachedRvaiPipeline
  )

  private val allConfigurations = Seq(
    Configuration("Two stages", () => new CachedRvaiTwoStages()),
    Configuration("Three stages", () => new CachedRvaiThreeStages()),
    Configuration(
      "Three stages + fetch predecode",
      () => new CachedRvaiThreeStagesPredecode()
    ),
    Configuration(
      "Three stages + execute/memory",
      () => new CachedRvaiThreeStagesExecuteMemory()
    ),
    Configuration("Four stages", () => new CachedRvaiFourStages()),
    Configuration("Five stages", () => new CachedRvaiFiveStages()),
    Configuration("Six stages + ID/RR split", () => new CachedRvaiSixStages()),
    Configuration(
      "Six stages + memory split",
      () => new CachedRvaiSixStagesMemorySplit()
    )
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
      benchmark: String,
      binary: Path,
      maximumCycles: Long
  ): Result = {
    val memory = loadBinary(binary)
    var cycle = 0L
    var instructionReads = 0L
    var dataReads = 0L
    var writes = 0L
    var measurementActive = false
    var finished = false
    val retirementTrace = mutable.Queue.empty[(BigInt, BigInt)]

    dut.io.memoryReady.poke(false.B)
    dut.io.memoryReadData.poke(0.U)
    dut.io.debugRegisterAddress.poke(0.U)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)

    def complete(transaction: Transaction): Unit = {
      dut.io.memoryReadData.poke(memory.getOrElse(transaction.address, BigInt(0)).U)
      dut.io.memoryReady.poke(true.B)
      if (transaction.write) {
        val updated = mergeWrite(
          memory.getOrElse(transaction.address, BigInt(0)),
          transaction.writeData,
          transaction.writeMask
        )
        memory(transaction.address) = updated
        if (measurementActive && transaction.address != ResultActive) writes += 1
        if (transaction.address == ResultActive) measurementActive = updated != 0
        if (transaction.address == ResultDone && updated == DoneMagic) finished = true
      } else if (measurementActive && dut.io.memoryInstruction.peek().litToBoolean) {
        instructionReads += 1
      } else if (measurementActive) {
        dataReads += 1
      }
    }

    while (!finished && cycle < maximumCycles) {
      dut.io.memoryReady.poke(false.B)
      dut.io.memoryReadData.poke(0.U)

      if (dut.io.memoryRequest.peek().litToBoolean) {
        complete(Transaction(
          dut.io.memoryAddress.peek().litValue,
          dut.io.memoryWrite.peek().litToBoolean,
          dut.io.memoryWriteData.peek().litValue,
          dut.io.memoryWriteMask.peek().litValue
        ))
      }

      if (dut.io.retiredValid.peek().litToBoolean) {
        retirementTrace.enqueue(
          dut.io.retiredPc.peek().litValue -> dut.io.retiredInstruction.peek().litValue
        )
        if (retirementTrace.size > 24) retirementTrace.dequeue()
      }
      if (dut.io.illegalInstruction.peek().litToBoolean) {
        fail(
          s"$benchmark executed an illegal instruction at simulation cycle $cycle: " +
            retirementTrace.map { case (pc, instruction) =>
              f"$pc%08x:$instruction%08x"
            }.mkString("recent [", ", ", "]")
        )
      }
      dut.clock.step()
      cycle += 1
    }

    withClue(s"$benchmark did not finish within $maximumCycles cycles: ") {
      finished mustBe true
    }
    withClue(
      f"$benchmark verification failed (result 0x${memory.getOrElse(ResultValue, BigInt(0))}%08x): "
    ) {
      memory.getOrElse(ResultStatus, BigInt(0)) mustBe 1
    }
    val instructions = memory(ResultInstret)
    withClue(s"$benchmark retired no measured instructions: ") {
      instructions must be > BigInt(0)
    }

    Result(
      benchmark = benchmark,
      pipeline = "",
      cycles = memory(ResultCycles),
      instructions = instructions,
      instructionReads = instructionReads,
      dataReads = dataReads,
      writes = writes
    )
  }

  private def format(value: Double): String =
    String.format(Locale.ROOT, "%.3f", Double.box(value))

  "Embench-IoT compares cached pipeline organizations" in {
    val binaryDirectory = Paths.get(sys.env.getOrElse(
      "EMBENCH_BIN_DIR",
      cancel("EMBENCH_BIN_DIR is not set; run this benchmark through `make embench`")
    )).toAbsolutePath.normalize()
    Files.isDirectory(binaryDirectory) mustBe true

    val selectedBenchmarks = sys.env.get("EMBENCH_BENCHMARK") match {
      case Some(selected) => allBenchmarkNames.filter(_ == selected)
      case None => defaultBenchmarkNames
    }
    val configurations = sys.env.get("EMBENCH_PIPELINE") match {
      case Some(selected) => allConfigurations.filter(_.name == selected)
      case None => allConfigurations
    }
    selectedBenchmarks.nonEmpty mustBe true
    configurations.nonEmpty mustBe true
    val maximumCycles = sys.env.get("EMBENCH_MAX_CYCLES").map(_.toLong).getOrElse(20000000L)

    val binaries = selectedBenchmarks.map { benchmark =>
      val path = binaryDirectory.resolve(s"$benchmark.bin")
      withClue(s"missing Embench binary for $benchmark: ") {
        Files.isRegularFile(path) mustBe true
      }
      benchmark -> path
    }

    val results = mutable.ArrayBuffer.empty[Result]
    configurations.foreach { configuration =>
      simulate(configuration.generate()) { dut =>
        binaries.foreach { case (benchmark, binary) =>
          val measured = run(dut, benchmark, binary, maximumCycles)
          results += measured.copy(pipeline = configuration.name)
          println(
            s"Embench result: $benchmark, ${configuration.name}, " +
              s"cycles=${measured.cycles}, instructions=${measured.instructions}, " +
              s"CPI=${format(measured.cpi)}"
          )
        }
      }
    }

    println("\nEmbench-IoT short RTL-simulation CPI comparison")
    println("| Benchmark | " + configurations.map(_.name).mkString(" | ") + " |")
    println("|---|" + configurations.map(_ => "---:").mkString("|") + "|")
    selectedBenchmarks.foreach { benchmark =>
      val row = configurations.map { configuration =>
        format(results.find(r => r.benchmark == benchmark && r.pipeline == configuration.name).get.cpi)
      }
      println(s"| $benchmark | ${row.mkString(" | ")} |")
    }

    val aggregate = configurations.map { configuration =>
      val pipelineResults = results.filter(_.pipeline == configuration.name)
      val cycles = pipelineResults.map(_.cycles).sum
      val instructions = pipelineResults.map(_.instructions).sum
      configuration.name -> (cycles.toDouble / instructions.toDouble)
    }
    println("| **Instruction-weighted aggregate** | " +
      aggregate.map { case (_, cpi) => s"**${format(cpi)}**" }.mkString(" | ") + " |")
  }
}
