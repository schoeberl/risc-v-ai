package riscvai

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class DataCacheSpec extends AnyFreeSpec with ChiselSim {
  "DataCache should complete consecutive read hits without a stall cycle" in {
    simulate(new DataCache(cacheBytes = 256, lineBytes = 16)) { dut =>
      val words = Seq(
        BigInt("11111111", 16),
        BigInt("22222222", 16),
        BigInt("33333333", 16),
        BigInt("44444444", 16)
      )

      dut.io.cpuRequest.poke(false.B)
      dut.io.cpuRead.poke(false.B)
      dut.io.cpuWrite.poke(false.B)
      dut.io.cpuAddress.poke(0.U)
      dut.io.cpuNextAddress.poke(0.U)
      dut.io.cpuWriteData.poke(0.U)
      dut.io.cpuWriteMask.poke(0.U)
      dut.io.cpuAccept.poke(false.B)
      dut.io.memory.ready.poke(false.B)
      dut.io.memory.readData.poke(0.U)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      // The tag SRAM is cleared one cache line per cycle after reset.
      dut.clock.step(16)

      // Launch the first lookup after priming the synchronous memories.
      dut.io.cpuNextAddress.poke(0.U)
      dut.clock.step()
      dut.io.cpuRequest.poke(true.B)
      dut.io.cpuRead.poke(true.B)
      dut.io.cpuAddress.poke(0.U)
      dut.clock.step()

      // Refill the missed line one word per cycle.
      for (word <- words.indices) {
        dut.io.memory.request.expect(true.B)
        dut.io.memory.write.expect(false.B)
        dut.io.memory.address.expect((word * 4).U)
        dut.io.memory.readData.poke(words(word).U)
        dut.io.memory.ready.poke(true.B)
        dut.clock.step()
      }
      dut.io.memory.ready.poke(false.B)

      // The post-refill prime cycle aligns the held request with both memories.
      dut.clock.step()
      dut.io.cpuReady.expect(true.B)
      dut.io.cpuReadData.expect(words.head.U)

      // Each accepted hit also clocks the following address into the SRAMs.
      dut.io.cpuAccept.poke(true.B)
      for (word <- words.indices.dropRight(1)) {
        dut.io.cpuAddress.poke((word * 4).U)
        dut.io.cpuNextAddress.poke(((word + 1) * 4).U)
        dut.io.cpuReady.expect(true.B)
        dut.io.cpuReadData.expect(words(word).U)
        dut.io.memory.request.expect(false.B)
        dut.clock.step()
      }

      dut.io.cpuAddress.poke(12.U)
      dut.io.cpuNextAddress.poke(0.U)
      dut.io.cpuReady.expect(true.B)
      dut.io.cpuReadData.expect(words.last.U)
      dut.io.memory.request.expect(false.B)
    }
  }

  "DataCache should pipeline hits to different resident lines" in {
    simulate(new DataCache(cacheBytes = 256, lineBytes = 16)) { dut =>
      val line0 = Seq.tabulate(4)(word => BigInt(0x10 + word))
      val line1 = Seq.tabulate(4)(word => BigInt(0x20 + word))

      dut.io.cpuRequest.poke(false.B)
      dut.io.cpuRead.poke(false.B)
      dut.io.cpuWrite.poke(false.B)
      dut.io.cpuAddress.poke(0.U)
      dut.io.cpuNextAddress.poke(0.U)
      dut.io.cpuWriteData.poke(0.U)
      dut.io.cpuWriteMask.poke(0.U)
      dut.io.cpuAccept.poke(false.B)
      dut.io.memory.ready.poke(false.B)
      dut.io.memory.readData.poke(0.U)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      // The tag SRAM is cleared one cache line per cycle after reset.
      dut.clock.step(16)

      def missAndRefill(base: Int, words: Seq[BigInt]): Unit = {
        dut.io.cpuNextAddress.poke(base.U)
        dut.clock.step()
        dut.io.cpuRequest.poke(true.B)
        dut.io.cpuRead.poke(true.B)
        dut.io.cpuAddress.poke(base.U)
        dut.clock.step()
        for (word <- words.indices) {
          dut.io.memory.request.expect(true.B)
          dut.io.memory.address.expect((base + word * 4).U)
          dut.io.memory.readData.poke(words(word).U)
          dut.io.memory.ready.poke(true.B)
          dut.clock.step()
        }
        dut.io.memory.ready.poke(false.B)
        dut.clock.step()
        dut.io.cpuReady.expect(true.B)
        dut.io.cpuReadData.expect(words.head.U)
      }

      missAndRefill(0, line0)

      // Accept line 0 while launching the lookup that will miss on line 1.
      dut.io.cpuAccept.poke(true.B)
      dut.io.cpuNextAddress.poke(16.U)
      dut.clock.step()
      dut.io.cpuAddress.poke(16.U)
      dut.io.cpuReady.expect(false.B)
      dut.clock.step()
      for (word <- line1.indices) {
        dut.io.memory.request.expect(true.B)
        dut.io.memory.address.expect((16 + word * 4).U)
        dut.io.memory.readData.poke(line1(word).U)
        dut.io.memory.ready.poke(true.B)
        dut.clock.step()
      }
      dut.io.memory.ready.poke(false.B)
      dut.clock.step()
      dut.io.cpuReady.expect(true.B)
      dut.io.cpuReadData.expect(line1.head.U)

      // Alternate between the two resident lines on consecutive clocks.
      dut.io.cpuNextAddress.poke(0.U)
      dut.clock.step()
      dut.io.cpuAddress.poke(0.U)
      dut.io.cpuNextAddress.poke(16.U)
      dut.io.cpuReady.expect(true.B)
      dut.io.cpuReadData.expect(line0.head.U)
      dut.io.memory.request.expect(false.B)
      dut.clock.step()
      dut.io.cpuAddress.poke(16.U)
      dut.io.cpuReady.expect(true.B)
      dut.io.cpuReadData.expect(line1.head.U)
      dut.io.memory.request.expect(false.B)
    }
  }
}
