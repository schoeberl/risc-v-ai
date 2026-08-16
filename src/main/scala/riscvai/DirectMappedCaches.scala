package riscvai

import chisel3._
import chisel3.util._

private object CacheGeometry {
  def validate(cacheBytes: Int, lineBytes: Int): Unit = {
    require(cacheBytes > 0 && isPow2(cacheBytes), "cache size must be a power of two")
    require(lineBytes >= 4 && isPow2(lineBytes), "line size must be a power of two")
    require(cacheBytes > lineBytes, "cache must contain at least two lines")
    require(lineBytes % 4 == 0, "cache lines must contain whole 32-bit words")
  }
}

/** Read-only, direct-mapped instruction cache with multiword line refill. */
private[riscvai] class InstructionCache(
    cacheBytes: Int = 1024,
    lineBytes: Int = 16,
    useSky130Sram: Boolean = false
)
    extends Module {
  CacheGeometry.validate(cacheBytes, lineBytes)

  private val lineCount = cacheBytes / lineBytes
  private val wordsPerLine = lineBytes / 4
  private val offsetBits = log2Ceil(lineBytes)
  private val indexBits = log2Ceil(lineCount)
  private val wordBits = log2Ceil(wordsPerLine)
  private val tagBits = 32 - offsetBits - indexBits

  val io = IO(new Bundle {
    val cpuRequest = Input(Bool())
    val cpuAddress = Input(UInt(32.W))
    val cpuData = Output(UInt(32.W))
    val cpuReady = Output(Bool())
    val cpuAccept = Input(Bool())
    val invalidate = Input(Bool())
    val memory = new CacheMemoryPort
  })

  private val wordCount = cacheBytes / 4

  private object State extends ChiselEnum {
    val idle, lookup, complete, refill = Value
  }
  private val state = RegInit(State.idle)
  private val valid = RegInit(VecInit(Seq.fill(lineCount)(false.B)))
  private val tags = Reg(Vec(lineCount, UInt(tagBits.W)))
  private val data = CacheDataMemory(wordCount, useSky130Sram)
  private val requestIndex = Reg(UInt(indexBits.W))
  private val requestTag = Reg(UInt(tagBits.W))
  private val lookupTag = Reg(UInt(tagBits.W))
  private val lookupValid = Reg(Bool())
  private val responseData = Reg(UInt(32.W))
  private val missBase = Reg(UInt(32.W))
  private val missIndex = Reg(UInt(indexBits.W))
  private val missTag = Reg(UInt(tagBits.W))
  private val refillWord = RegInit(0.U(wordBits.W))

  val cpuIndex = io.cpuAddress(offsetBits + indexBits - 1, offsetBits)
  val cpuTag = io.cpuAddress(31, offsetBits + indexBits)
  val cpuWordAddress = io.cpuAddress(offsetBits + indexBits - 1, 2)
  val hit = lookupValid && lookupTag === requestTag

  data.readEnable := state === State.idle && io.cpuRequest
  data.readAddress := cpuWordAddress
  data.writeEnable := state === State.refill && io.memory.ready
  data.writeAddress := Cat(missIndex, refillWord)
  data.writeData := io.memory.readData
  data.writeMask := "b1111".U

  io.cpuData := responseData
  io.cpuReady := state === State.complete
  io.memory.request := state === State.refill
  io.memory.write := false.B
  io.memory.address := missBase + (refillWord << 2)
  io.memory.writeData := 0.U
  io.memory.writeMask := 0.U

  when(io.invalidate) {
    valid.foreach(_ := false.B)
    state := State.idle
  }.otherwise {
    switch(state) {
      is(State.idle) {
        when(io.cpuRequest) {
          requestIndex := cpuIndex
          requestTag := cpuTag
          lookupTag := tags(cpuIndex)
          lookupValid := valid(cpuIndex)
          state := State.lookup
        }
      }
      is(State.lookup) {
        when(hit) {
          responseData := data.readData
          state := State.complete
        }.otherwise {
          missBase := Cat(requestTag, requestIndex, 0.U(offsetBits.W))
          missIndex := requestIndex
          missTag := requestTag
          refillWord := 0.U
          valid(requestIndex) := false.B
          state := State.refill
        }
      }
      is(State.complete) {
        when(io.cpuAccept) {
          state := State.idle
        }
      }
      is(State.refill) {
        when(io.memory.ready) {
          when(refillWord === (wordsPerLine - 1).U) {
            tags(missIndex) := missTag
            valid(missIndex) := true.B
            state := State.idle
          }.otherwise {
            refillWord := refillWord + 1.U
          }
        }
      }
    }
  }
}

/** Direct-mapped write-through data cache. Loads allocate; store misses write around. */
private[riscvai] class DataCache(
    cacheBytes: Int = 1024,
    lineBytes: Int = 16,
    useSky130Sram: Boolean = false
)
    extends Module {
  CacheGeometry.validate(cacheBytes, lineBytes)

  private val lineCount = cacheBytes / lineBytes
  private val wordsPerLine = lineBytes / 4
  private val offsetBits = log2Ceil(lineBytes)
  private val indexBits = log2Ceil(lineCount)
  private val wordBits = log2Ceil(wordsPerLine)
  private val tagBits = 32 - offsetBits - indexBits
  private val lineMask = (~(lineBytes - 1) & 0xffffffffL).U(32.W)

  val io = IO(new Bundle {
    val cpuRequest = Input(Bool())
    val cpuRead = Input(Bool())
    val cpuWrite = Input(Bool())
    val cpuAddress = Input(UInt(32.W))
    val cpuWriteData = Input(UInt(32.W))
    val cpuWriteMask = Input(UInt(4.W))
    val cpuReadData = Output(UInt(32.W))
    val cpuReady = Output(Bool())
    val cpuAccept = Input(Bool())
    val memory = new CacheMemoryPort
  })

  private val wordCount = cacheBytes / 4
  private val memoryAddressBits = log2Ceil(wordCount)

  private object State extends ChiselEnum {
    val idle, lookup, prepareWrite, cacheWrite, complete, refill, writeThrough = Value
  }
  private val state = RegInit(State.idle)
  private val valid = RegInit(VecInit(Seq.fill(lineCount)(false.B)))
  private val tags = Reg(Vec(lineCount, UInt(tagBits.W)))
  private val data = CacheDataMemory(wordCount, useSky130Sram)

  private val requestAddress = Reg(UInt(32.W))
  private val requestIndex = Reg(UInt(indexBits.W))
  private val requestTag = Reg(UInt(tagBits.W))
  private val requestRead = Reg(Bool())
  private val requestWrite = Reg(Bool())
  private val requestWriteMask = Reg(UInt(4.W))
  private val lookupTag = Reg(UInt(tagBits.W))
  private val lookupValid = Reg(Bool())

  private val missBase = Reg(UInt(32.W))
  private val missIndex = Reg(UInt(indexBits.W))
  private val missTag = Reg(UInt(tagBits.W))
  private val refillWord = RegInit(0.U(wordBits.W))

  private val pendingWriteAddress = Reg(UInt(32.W))
  private val pendingWriteData = Reg(UInt(32.W))
  private val pendingWriteMask = Reg(UInt(4.W))
  private val pendingReadData = Reg(UInt(32.W))
  private val pendingCacheHit = Reg(Bool())

  val cpuIndex = io.cpuAddress(offsetBits + indexBits - 1, offsetBits)
  val cpuTag = io.cpuAddress(31, offsetBits + indexBits)
  val cpuWordAddress = io.cpuAddress(offsetBits + indexBits - 1, 2)
  val hit = lookupValid && lookupTag === requestTag
  val hitData = data.readData

  val refillWrite = state === State.refill && io.memory.ready
  val hitWrite = state === State.cacheWrite
  data.readEnable := state === State.idle && io.cpuRequest
  data.readAddress := cpuWordAddress
  data.writeEnable := refillWrite || hitWrite
  data.writeAddress := Mux(
    refillWrite,
    Cat(missIndex, refillWord),
    pendingWriteAddress(memoryAddressBits + 1, 2)
  )
  data.writeData := Mux(refillWrite, io.memory.readData, pendingWriteData)
  data.writeMask := Mux(refillWrite, "b1111".U, pendingWriteMask)

  io.cpuReadData := pendingReadData
  io.cpuReady := state === State.complete
  when(state === State.writeThrough) {
    io.cpuReady := io.memory.ready
  }

  io.memory.request := state === State.refill || state === State.writeThrough
  io.memory.write := state === State.writeThrough
  io.memory.address := Mux(
    state === State.refill,
    missBase + (refillWord << 2),
    pendingWriteAddress
  )
  io.memory.writeData := pendingWriteData
  io.memory.writeMask := pendingWriteMask

  switch(state) {
    is(State.idle) {
      when(io.cpuRequest) {
        requestAddress := io.cpuAddress
        requestIndex := cpuIndex
        requestTag := cpuTag
        requestRead := io.cpuRead
        requestWrite := io.cpuWrite
        requestWriteMask := io.cpuWriteMask
        lookupTag := tags(cpuIndex)
        lookupValid := valid(cpuIndex)
        state := State.lookup
      }
    }
    is(State.lookup) {
      when(requestRead && !hit) {
        // An AMO is both a read and a write. Refill first so its old value is valid.
        missBase := requestAddress & lineMask
        missIndex := requestIndex
        missTag := requestTag
        refillWord := 0.U
        valid(requestIndex) := false.B
        state := State.refill
      }.elsewhen(requestWrite) {
        pendingWriteAddress := requestAddress
        pendingWriteMask := requestWriteMask
        pendingReadData := Mux(hit, hitData, 0.U)
        pendingCacheHit := hit
        state := State.prepareWrite
      }.otherwise {
        pendingReadData := hitData
        state := State.complete
      }
    }
    is(State.prepareWrite) {
      // Give AMO result generation a full cycle after registering the old value.
      pendingWriteData := io.cpuWriteData
      state := Mux(pendingCacheHit, State.cacheWrite, State.writeThrough)
    }
    is(State.cacheWrite) {
      state := State.writeThrough
    }
    is(State.complete) {
      when(io.cpuAccept) {
        state := State.idle
      }
    }
    is(State.refill) {
      when(io.memory.ready) {
        when(refillWord === (wordsPerLine - 1).U) {
          tags(missIndex) := missTag
          valid(missIndex) := true.B
          state := State.idle
        }.otherwise {
          refillWord := refillWord + 1.U
        }
      }
    }
    is(State.writeThrough) {
      when(io.memory.ready) {
        when(io.cpuAccept) {
          state := State.idle
        }.otherwise {
          state := State.complete
        }
      }
    }
  }
}
