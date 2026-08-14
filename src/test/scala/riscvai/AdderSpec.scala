package riscvai

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class AdderSpec extends AnyFreeSpec with Matchers with ChiselSim {
  "Adder should preserve carry-out" in {
    simulate(new Adder(width = 8)) { dut =>
      dut.io.a.poke(200.U)
      dut.io.b.poke(100.U)
      dut.io.sum.expect(300.U)
    }
  }
}
