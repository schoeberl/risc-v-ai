package riscvai

import chisel3._
import chisel3.util._

/** Minimal machine-mode CSR bank for a single RV32IMA hart.
  *
  * Trap entry and interrupt sources are intentionally separate future steps.
  * Until a platform timer is connected, the time CSR aliases the cycle counter.
  */
private class MachineCsrs(hartId: BigInt = 0) extends Module {
  val io = IO(new Bundle {
    val address = Input(UInt(12.W))
    val readData = Output(UInt(32.W))
    val readValid = Output(Bool())
    val writeAllowed = Output(Bool())
    val writeEnable = Input(Bool())
    val writeData = Input(UInt(32.W))
    val retired = Input(Bool())
  })

  private val MisaValue = "h40001101".U(32.W) // RV32IMA
  private val MstatusMask = "h00001888".U(32.W) // MPP, MPIE, MIE
  private val InterruptMask = "h00000888".U(32.W) // MEIE, MTIE, MSIE

  private val mstatus = RegInit(0.U(32.W))
  private val mie = RegInit(0.U(32.W))
  private val mtvec = RegInit(0.U(32.W))
  private val mcounteren = RegInit(0.U(32.W))
  private val mscratch = RegInit(0.U(32.W))
  private val mepc = RegInit(0.U(32.W))
  private val mcause = RegInit(0.U(32.W))
  private val mtval = RegInit(0.U(32.W))
  private val mip = RegInit(0.U(32.W))
  private val cycleCounter = RegInit(0.U(64.W))
  private val retiredCounter = RegInit(0.U(64.W))

  cycleCounter := cycleCounter + 1.U
  when(io.retired) {
    retiredCounter := retiredCounter + 1.U
  }

  io.readData := 0.U
  private val readableAddresses = Seq(
    0x300, 0x301, 0x304, 0x305, 0x306,
    0x340, 0x341, 0x342, 0x343, 0x344,
    0xb00, 0xb02, 0xb80, 0xb82,
    0xc00, 0xc01, 0xc02, 0xc80, 0xc81, 0xc82,
    0xf11, 0xf12, 0xf13, 0xf14, 0xf15
  )
  private val writableAddresses = Seq(
    0x300, 0x304, 0x305, 0x306,
    0x340, 0x341, 0x342, 0x343, 0x344,
    0xb00, 0xb02, 0xb80, 0xb82
  )
  io.readValid := readableAddresses.map(address => io.address === address.U).reduce(_ || _)
  io.writeAllowed := writableAddresses.map(address => io.address === address.U).reduce(_ || _)

  switch(io.address) {
    is("h300".U) { io.readData := mstatus }
    is("h301".U) { io.readData := MisaValue }
    is("h304".U) { io.readData := mie }
    is("h305".U) { io.readData := mtvec }
    is("h306".U) { io.readData := mcounteren }
    is("h340".U) { io.readData := mscratch }
    is("h341".U) { io.readData := mepc }
    is("h342".U) { io.readData := mcause }
    is("h343".U) { io.readData := mtval }
    is("h344".U) { io.readData := mip }
    is("hb00".U) { io.readData := cycleCounter(31, 0) }
    is("hb02".U) { io.readData := retiredCounter(31, 0) }
    is("hb80".U) { io.readData := cycleCounter(63, 32) }
    is("hb82".U) { io.readData := retiredCounter(63, 32) }
    is("hc00".U) { io.readData := cycleCounter(31, 0) }
    is("hc01".U) { io.readData := cycleCounter(31, 0) }
    is("hc02".U) { io.readData := retiredCounter(31, 0) }
    is("hc80".U) { io.readData := cycleCounter(63, 32) }
    is("hc81".U) { io.readData := cycleCounter(63, 32) }
    is("hc82".U) { io.readData := retiredCounter(63, 32) }
    is("hf11".U) { io.readData := 0.U }
    is("hf12".U) { io.readData := 0.U }
    is("hf13".U) { io.readData := 0.U }
    is("hf14".U) { io.readData := hartId.U }
    is("hf15".U) { io.readData := 0.U }
  }

  when(io.writeEnable && io.writeAllowed) {
    switch(io.address) {
      is("h300".U) { mstatus := io.writeData & MstatusMask }
      is("h304".U) { mie := io.writeData & InterruptMask }
      is("h305".U) {
        val validMode = io.writeData(1, 0) === 0.U || io.writeData(1, 0) === 1.U
        mtvec := Cat(io.writeData(31, 2), Mux(validMode, io.writeData(1, 0), 0.U))
      }
      is("h306".U) { mcounteren := io.writeData & 7.U }
      is("h340".U) { mscratch := io.writeData }
      is("h341".U) { mepc := io.writeData & "hfffffffc".U }
      is("h342".U) { mcause := io.writeData }
      is("h343".U) { mtval := io.writeData }
      is("h344".U) { mip := (mip & ~InterruptMask) | (io.writeData & InterruptMask) }
      is("hb00".U) { cycleCounter := Cat(cycleCounter(63, 32), io.writeData) }
      is("hb02".U) { retiredCounter := Cat(retiredCounter(63, 32), io.writeData) }
      is("hb80".U) { cycleCounter := Cat(io.writeData, cycleCounter(31, 0)) }
      is("hb82".U) { retiredCounter := Cat(io.writeData, retiredCounter(31, 0)) }
    }
  }
}
