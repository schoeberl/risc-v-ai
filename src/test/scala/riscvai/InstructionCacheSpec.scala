package riscvai

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class InstructionCacheSpec extends AnyFreeSpec with ChiselSim {
  "InstructionCache should deliver one instruction per cycle on consecutive hits" in {
    simulate(new InstructionCache(cacheBytes = 256, lineBytes = 16)) { dut =>
      val instructions = Seq(
        BigInt("11111111", 16),
        BigInt("22222222", 16),
        BigInt("33333333", 16),
        BigInt("44444444", 16)
      )

      dut.io.cpuRequest.poke(true.B)
      dut.io.cpuNextAddress.poke(0.U)
      dut.io.cpuAccept.poke(false.B)
      dut.io.invalidate.poke(false.B)
      dut.io.memory.ready.poke(false.B)
      dut.io.memory.readData.poke(0.U)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      var cycles = 0
      var respond = false
      while (!dut.io.cpuReady.peek().litToBoolean && cycles < 32) {
        if (dut.io.memory.request.peek().litToBoolean) {
          if (respond) {
            val address = dut.io.memory.address.peek().litValue
            dut.io.memory.readData.poke(instructions((address / 4).toInt).U)
            dut.io.memory.ready.poke(true.B)
            respond = false
          } else {
            dut.io.memory.ready.poke(false.B)
            respond = true
          }
        } else {
          dut.io.memory.ready.poke(false.B)
          respond = false
        }
        dut.clock.step()
        cycles += 1
      }

      assert(dut.io.cpuReady.peek().litToBoolean, "initial line refill did not complete")
      dut.io.memory.request.expect(false.B)

      dut.io.cpuAccept.poke(true.B)
      for (word <- Seq(1, 2, 3, 0)) {
        dut.io.cpuNextAddress.poke((word * 4).U)
        dut.io.memory.ready.poke(false.B)
        dut.clock.step()

        dut.io.cpuReady.expect(true.B)
        dut.io.memory.request.expect(false.B)
      }
    }
  }
}
