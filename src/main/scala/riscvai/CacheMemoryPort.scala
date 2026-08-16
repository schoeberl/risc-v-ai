package riscvai

import chisel3._

/** One 32-bit transaction at a time. A requester holds its signals until ready. */
private[riscvai] class CacheMemoryPort extends Bundle {
  val request = Output(Bool())
  val write = Output(Bool())
  val address = Output(UInt(32.W))
  val writeData = Output(UInt(32.W))
  val writeMask = Output(UInt(4.W))
  val ready = Input(Bool())
  val readData = Input(UInt(32.W))
}

/** Locks a requester onto the shared port until the current word transfer completes. */
private[riscvai] class CacheArbiter extends Module {
  val io = IO(new Bundle {
    val instruction = Flipped(new CacheMemoryPort)
    val data = Flipped(new CacheMemoryPort)

    val memoryRequest = Output(Bool())
    val memoryWrite = Output(Bool())
    val memoryAddress = Output(UInt(32.W))
    val memoryWriteData = Output(UInt(32.W))
    val memoryWriteMask = Output(UInt(4.W))
    val memoryReady = Input(Bool())
    val memoryReadData = Input(UInt(32.W))
  })

  private val GrantNone = 0.U(2.W)
  private val GrantInstruction = 1.U(2.W)
  private val GrantData = 2.U(2.W)
  private val grant = RegInit(GrantNone)

  // Data accesses are older than the speculative instruction fetch and get priority.
  val selected = WireDefault(grant)
  when(grant === GrantNone) {
    selected := Mux(
      io.data.request,
      GrantData,
      Mux(io.instruction.request, GrantInstruction, GrantNone)
    )
  }

  val selectInstruction = selected === GrantInstruction
  val selectData = selected === GrantData
  io.memoryRequest := Mux(
    selectData,
    io.data.request,
    Mux(selectInstruction, io.instruction.request, false.B)
  )
  io.memoryWrite := selectData && io.data.write
  io.memoryAddress := Mux(selectData, io.data.address, io.instruction.address)
  io.memoryWriteData := Mux(selectData, io.data.writeData, io.instruction.writeData)
  io.memoryWriteMask := Mux(selectData, io.data.writeMask, io.instruction.writeMask)

  io.instruction.ready := selectInstruction && io.memoryRequest && io.memoryReady
  io.instruction.readData := io.memoryReadData
  io.data.ready := selectData && io.memoryRequest && io.memoryReady
  io.data.readData := io.memoryReadData

  when(grant === GrantNone && selected =/= GrantNone && !io.memoryReady) {
    grant := selected
  }.elsewhen(grant =/= GrantNone && io.memoryReady) {
    grant := GrantNone
  }
}
