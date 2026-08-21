package riscvai

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

import java.nio.file.{Files, Path, Paths}
import java.util.Locale
import scala.collection.mutable

private object IdealMemoryBenchmark {
  import chisel3.simulator.PeekPokeAPI._

  val WordMask: BigInt = BigInt("ffffffff", 16)

  case class Configuration(name: String, generate: () => RvaiPipeline)

  val configurations: Seq[Configuration] = Seq(
    Configuration("Multicycle", () => new RvaiMulticycle()),
    Configuration("Two stages", () => new RvaiTwoStages()),
    Configuration("Three stages", () => new RvaiThreeStages()),
    Configuration(
      "Three stages + fetch predecode",
      () => new RvaiThreeStagesPredecode()
    ),
    Configuration(
      "Three stages + execute/memory",
      () => new RvaiThreeStagesExecuteMemory()
    ),
    Configuration("Four stages", () => new RvaiFourStages()),
    Configuration("Five stages", () => new RvaiFiveStages()),
    Configuration("Six stages + ID/RR split", () => new RvaiSixStages()),
    Configuration(
      "Six stages + memory split",
      () => new RvaiSixStagesMemorySplit()
    )
  )

  def selectedConfiguration(environmentName: String): Seq[Configuration] =
    sys.env.get(environmentName) match {
      case Some(selected) => configurations.filter(_.name == selected)
      case None => configurations
    }

  def loadBinary(path: Path): mutable.Map[BigInt, BigInt] = {
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

  def mergeWrite(oldValue: BigInt, newValue: BigInt, mask: BigInt): BigInt =
    (0 until 4).foldLeft(oldValue) { (value, lane) =>
      if (mask.testBit(lane)) {
        val byteMask = BigInt(0xff) << (lane * 8)
        (value & ~byteMask) | (newValue & byteMask)
      } else value
    } & WordMask

  def initialize(dut: RvaiPipeline): Unit = {
    dut.io.instruction.poke(0.U)
    dut.io.instructionValid.poke(true.B)
    dut.io.dataReadData.poke(0.U)
    dut.io.dataAtomicReadData.poke(0.U)
    dut.io.memoryStall.poke(false.B)
    dut.io.debugRegisterAddress.poke(0.U)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
  }

  /** Drives the combinational read ports for the current cycle. */
  def driveReads(dut: RvaiPipeline, memory: mutable.Map[BigInt, BigInt]): Unit = {
    val instructionAddress = dut.io.instructionAddress.peek().litValue & ~BigInt(3)
    dut.io.instruction.poke(memory.getOrElse(instructionAddress, BigInt(0)).U)
    val dataAddress = dut.io.dataAddress.peek().litValue & ~BigInt(3)
    val data = memory.getOrElse(dataAddress, BigInt(0))
    dut.io.dataReadData.poke(data.U)
    dut.io.dataAtomicReadData.poke(data.U)
  }

  def applyStore(
      dut: RvaiPipeline,
      memory: mutable.Map[BigInt, BigInt]
  ): Option[(BigInt, BigInt)] = {
    if (!dut.io.dataWriteEnable.peek().litToBoolean) None
    else {
      val address = dut.io.dataAddress.peek().litValue & ~BigInt(3)
      val updated = mergeWrite(
        memory.getOrElse(address, BigInt(0)),
        dut.io.dataWriteData.peek().litValue,
        dut.io.dataWriteMask.peek().litValue
      )
      memory(address) = updated
      Some(address -> updated)
    }
  }

  def format(value: Double): String =
    String.format(Locale.ROOT, "%.3f", Double.box(value))
}

/** CoreMark CPI with ideal combinational instruction and data memories. */
class IdealMemoryCoreMarkBenchmarkSpec
    extends AnyFreeSpec
    with Matchers
    with ChiselSim {
  import IdealMemoryBenchmark._

  private val ResultStatus = BigInt("ffff0000", 16)
  private val ResultCycles = ResultStatus + 4
  private val ResultInstret = ResultStatus + 8
  private val ResultIterations = ResultStatus + 12
  private val ResultDone = ResultStatus + 16
  private val DoneMagic = BigInt("434d4f4b", 16)

  private case class Result(
      pipeline: String,
      cycles: BigInt,
      instructions: BigInt,
      iterations: BigInt
  ) {
    def cpi: Double = cycles.toDouble / instructions.toDouble
    def cyclesPerIteration: BigInt = cycles / iterations
  }

  private def run(
      dut: RvaiPipeline,
      binary: Path,
      maximumCycles: Long = 5000000L
  ): Result = {
    val memory = loadBinary(binary)
    var simulationCycles = 0L
    var finished = false
    val retirementTrace = mutable.Queue.empty[(BigInt, BigInt)]
    initialize(dut)

    while (!finished && simulationCycles < maximumCycles) {
      driveReads(dut, memory)
      applyStore(dut, memory).foreach { case (address, value) =>
        if (address == ResultDone && value == DoneMagic) finished = true
      }
      if (dut.io.retiredValid.peek().litToBoolean) {
        retirementTrace.enqueue(
          dut.io.retiredPc.peek().litValue -> dut.io.retiredInstruction.peek().litValue
        )
        if (retirementTrace.size > 24) retirementTrace.dequeue()
      }
      if (dut.io.illegalInstruction.peek().litToBoolean) {
        fail(retirementTrace.map { case (pc, instruction) =>
          f"$pc%08x:$instruction%08x"
        }.mkString("illegal instruction; recent [", ", ", "]"))
      }
      dut.clock.step()
      simulationCycles += 1
    }

    withClue(s"CoreMark did not finish within $maximumCycles cycles: ") {
      finished mustBe true
    }
    withClue("CoreMark algorithm/data-type CRC validation failed: ") {
      memory.getOrElse(ResultStatus, BigInt(0)) mustBe 1
    }
    Result("", memory(ResultCycles), memory(ResultInstret), memory(ResultIterations))
  }

  "CoreMark compares ideal-memory pipeline organizations" in {
    val binary = Paths.get(sys.env.getOrElse(
      "COREMARK_BIN",
      cancel("COREMARK_BIN is not set; run this benchmark through `make coremark-ideal`")
    )).toAbsolutePath.normalize()
    Files.isRegularFile(binary) mustBe true
    val selected = selectedConfiguration("COREMARK_PIPELINE")
    selected.nonEmpty mustBe true

    val results = selected.map { configuration =>
      var measured = Option.empty[Result]
      simulate(configuration.generate()) { dut =>
        measured = Some(run(dut, binary))
      }
      measured.get.copy(pipeline = configuration.name)
    }

    println("\nCoreMark ideal-memory CPI comparison (CRC checked, not an official score)")
    println("| Pipeline | Cycles/iteration | Instructions | CPI |")
    println("|---|---:|---:|---:|")
    results.foreach { result =>
      println(s"| ${result.pipeline} | ${result.cyclesPerIteration} | " +
        s"${result.instructions} | ${format(result.cpi)} |")
    }
  }
}

/** Embench-IoT CPI with ideal combinational instruction and data memories. */
class IdealMemoryEmbenchBenchmarkSpec
    extends AnyFreeSpec
    with Matchers
    with ChiselSim {
  import IdealMemoryBenchmark._

  private val ResultStatus = BigInt("ffff0000", 16)
  private val ResultCycles = ResultStatus + 4
  private val ResultInstret = ResultStatus + 8
  private val ResultDone = ResultStatus + 12
  private val ResultValue = ResultStatus + 16
  private val DoneMagic = BigInt("454d4f4b", 16)
  private val defaultBenchmarks = Seq(
    "crc32",
    "edn",
    "huffbench",
    "matmult-int",
    "nettle-aes",
    "nettle-sha256",
    "slre",
    "statemate"
  )

  private case class Result(
      benchmark: String,
      pipeline: String,
      cycles: BigInt,
      instructions: BigInt
  ) {
    def cpi: Double = cycles.toDouble / instructions.toDouble
  }

  private def run(
      dut: RvaiPipeline,
      benchmark: String,
      binary: Path,
      maximumCycles: Long
  ): Result = {
    val memory = loadBinary(binary)
    var simulationCycles = 0L
    var finished = false
    val retirementTrace = mutable.Queue.empty[(BigInt, BigInt)]
    initialize(dut)

    while (!finished && simulationCycles < maximumCycles) {
      driveReads(dut, memory)
      applyStore(dut, memory).foreach { case (address, value) =>
        if (address == ResultDone && value == DoneMagic) finished = true
      }
      if (dut.io.retiredValid.peek().litToBoolean) {
        retirementTrace.enqueue(
          dut.io.retiredPc.peek().litValue -> dut.io.retiredInstruction.peek().litValue
        )
        if (retirementTrace.size > 24) retirementTrace.dequeue()
      }
      if (dut.io.illegalInstruction.peek().litToBoolean) {
        fail(retirementTrace.map { case (pc, instruction) =>
          f"$pc%08x:$instruction%08x"
        }.mkString(s"$benchmark illegal instruction; recent [", ", ", "]"))
      }
      dut.clock.step()
      simulationCycles += 1
    }

    withClue(s"$benchmark did not finish within $maximumCycles cycles: ") {
      finished mustBe true
    }
    withClue(
      f"$benchmark verification failed (result 0x${memory.getOrElse(ResultValue, BigInt(0))}%08x): "
    ) {
      memory.getOrElse(ResultStatus, BigInt(0)) mustBe 1
    }
    Result(benchmark, "", memory(ResultCycles), memory(ResultInstret))
  }

  "Embench-IoT compares ideal-memory pipeline organizations" in {
    val binaryDirectory = Paths.get(sys.env.getOrElse(
      "EMBENCH_BIN_DIR",
      cancel("EMBENCH_BIN_DIR is not set; run this benchmark through `make embench-ideal`")
    )).toAbsolutePath.normalize()
    Files.isDirectory(binaryDirectory) mustBe true
    val benchmarks = sys.env.get("EMBENCH_BENCHMARK").toSeq match {
      case Seq(selected) => Seq(selected)
      case _ => defaultBenchmarks
    }
    val selected = selectedConfiguration("EMBENCH_PIPELINE")
    selected.nonEmpty mustBe true
    val maximumCycles = sys.env.get("EMBENCH_MAX_CYCLES").map(_.toLong).getOrElse(20000000L)

    val results = mutable.ArrayBuffer.empty[Result]
    selected.foreach { configuration =>
      simulate(configuration.generate()) { dut =>
        benchmarks.foreach { benchmark =>
          val binary = binaryDirectory.resolve(s"$benchmark.bin")
          Files.isRegularFile(binary) mustBe true
          val measured = run(dut, benchmark, binary, maximumCycles)
            .copy(pipeline = configuration.name)
          results += measured
          println(
            s"Ideal-memory Embench result: $benchmark, ${configuration.name}, " +
              s"cycles=${measured.cycles}, instructions=${measured.instructions}, " +
              s"CPI=${format(measured.cpi)}"
          )
        }
      }
    }

    println("\nEmbench-IoT ideal-memory CPI comparison")
    println("| Benchmark | " + selected.map(_.name).mkString(" | ") + " |")
    println("|---|" + selected.map(_ => "---:").mkString("|") + "|")
    benchmarks.foreach { benchmark =>
      val row = selected.map { configuration =>
        format(results.find(result =>
          result.benchmark == benchmark && result.pipeline == configuration.name
        ).get.cpi)
      }
      println(s"| $benchmark | ${row.mkString(" | ")} |")
    }
    val aggregate = selected.map { configuration =>
      val pipelineResults = results.filter(_.pipeline == configuration.name)
      pipelineResults.map(_.cycles).sum.toDouble /
        pipelineResults.map(_.instructions).sum.toDouble
    }
    println("| **Instruction-weighted aggregate** | " +
      aggregate.map(value => s"**${format(value)}**").mkString(" | ") + " |")
  }
}
