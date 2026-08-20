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
    useAsicSram: Boolean = false
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
    val cpuNextAddress = Input(UInt(32.W))
    val cpuData = Output(UInt(32.W))
    val cpuReady = Output(Bool())
    val cpuAccept = Input(Bool())
    val invalidate = Input(Bool())
    val memory = new CacheMemoryPort
  })

  private val wordCount = cacheBytes / 4

  private object State extends ChiselEnum {
    val clear, prime, lookup, refill = Value
  }
  private val state = RegInit(State.clear)
  private val tags = CacheTagMemory(lineCount, tagBits + 1, useAsicSram)
  private val data = CacheDataMemory(wordCount, useAsicSram)
  private val clearIndex = RegInit(0.U(indexBits.W))
  private val requestIndex = Reg(UInt(indexBits.W))
  private val requestTag = Reg(UInt(tagBits.W))
  private val missBase = Reg(UInt(32.W))
  private val missIndex = Reg(UInt(indexBits.W))
  private val missTag = Reg(UInt(tagBits.W))
  private val refillWord = RegInit(0.U(wordBits.W))
  private val invalidatePending = RegInit(false.B)

  val nextIndex = io.cpuNextAddress(offsetBits + indexBits - 1, offsetBits)
  val nextTag = io.cpuNextAddress(31, offsetBits + indexBits)
  val nextWordAddress = io.cpuNextAddress(offsetBits + indexBits - 1, 2)
  tags.readEnable := io.cpuRequest
  tags.readAddress := nextIndex
  val refillComplete = state === State.refill && io.memory.ready &&
    refillWord === (wordsPerLine - 1).U
  tags.writeEnable := state === State.clear || refillComplete
  tags.writeAddress := Mux(state === State.clear, clearIndex, missIndex)
  tags.writeData := Mux(
    state === State.clear,
    0.U,
    Cat(true.B, missTag)
  )
  val lookupEntry = tags.readData
  val lookupTag = lookupEntry(tagBits - 1, 0)
  val hit = lookupEntry(tagBits) && lookupTag === requestTag

  // The SRAM address register and the core fetch-PC register capture the same
  // next-PC value. The synchronous read data therefore belongs to the current
  // fetch PC without an additional cache response register.
  data.readEnable := io.cpuRequest
  data.readAddress := nextWordAddress
  data.writeEnable := state === State.refill && io.memory.ready
  data.writeAddress := Cat(missIndex, refillWord)
  data.writeData := io.memory.readData
  data.writeMask := "b1111".U

  io.cpuData := data.readData
  io.cpuReady := state === State.lookup && hit
  io.memory.request := state === State.refill
  io.memory.write := false.B
  io.memory.address := missBase + (refillWord << 2)
  io.memory.writeData := 0.U
  io.memory.writeMask := 0.U

  val invalidateRequested = io.invalidate || invalidatePending
  when(state === State.refill && invalidateRequested && !io.memory.ready) {
    // The shared-memory protocol requires a request to remain asserted until
    // ready. Defer FENCE.I invalidation until the active refill word completes.
    invalidatePending := true.B
  }.elsewhen(state =/= State.clear && invalidateRequested) {
    clearIndex := 0.U
    invalidatePending := false.B
    state := State.clear
  }.otherwise {
    switch(state) {
      is(State.clear) {
        invalidatePending := false.B
        when(clearIndex === (lineCount - 1).U) {
          clearIndex := 0.U
          state := State.prime
        }.otherwise {
          clearIndex := clearIndex + 1.U
        }
      }
      is(State.prime) {
        requestIndex := nextIndex
        requestTag := nextTag
        state := State.lookup
      }
      is(State.lookup) {
        when(hit) {
          when(io.cpuAccept) {
            requestIndex := nextIndex
            requestTag := nextTag
          }
        }.otherwise {
          missBase := Cat(requestTag, requestIndex, 0.U(offsetBits.W))
          missIndex := requestIndex
          missTag := requestTag
          refillWord := 0.U
          state := State.refill
        }
      }
      is(State.refill) {
        when(io.memory.ready) {
          when(refillWord === (wordsPerLine - 1).U) {
            // Prime a fresh synchronous read after the refill write. This avoids
            // relying on inferred-memory read-during-write behavior.
            state := State.prime
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
    useAsicSram: Boolean = false
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
    /** Execute-stage address captured by the synchronous tag and data SRAMs. */
    val cpuNextAddress = Input(UInt(32.W))
    val cpuWriteData = Input(UInt(32.W))
    val cpuWriteMask = Input(UInt(4.W))
    val cpuReadData = Output(UInt(32.W))
    val cpuAtomicReadData = Output(UInt(32.W))
    val cpuReady = Output(Bool())
    val cpuAccept = Input(Bool())
    val memory = new CacheMemoryPort
  })

  private val wordCount = cacheBytes / 4
  private val memoryAddressBits = log2Ceil(wordCount)
  private object State extends ChiselEnum {
    val clear, idle, prime, prepareWrite, refill, writeThrough = Value
  }
  private val state = RegInit(State.clear)
  private val tags = CacheTagMemory(lineCount, tagBits + 1, useAsicSram)
  private val data = CacheDataMemory(wordCount, useAsicSram)
  private val clearIndex = RegInit(0.U(indexBits.W))

  private val missBase = Reg(UInt(32.W))
  private val missIndex = Reg(UInt(indexBits.W))
  private val missTag = Reg(UInt(tagBits.W))
  private val refillWord = RegInit(0.U(wordBits.W))

  private val pendingWriteAddress = Reg(UInt(32.W))
  private val pendingWriteData = Reg(UInt(32.W))
  private val pendingWriteMask = Reg(UInt(4.W))
  private val pendingReadData = Reg(UInt(32.W))

  val cpuIndex = io.cpuAddress(offsetBits + indexBits - 1, offsetBits)
  val cpuTag = io.cpuAddress(31, offsetBits + indexBits)
  val completingWrite = state === State.writeThrough && io.memory.ready
  val lookupEnable = state === State.idle || state === State.prime || completingWrite
  // During normal execution both pipeline organizations present the address
  // from execute. After a refill, the held memory/writeback request is reissued
  // once to avoid SRAM read-during-write assumptions.
  val lookupAddress = Mux(state === State.prime, io.cpuAddress, io.cpuNextAddress)
  val lookupIndex = lookupAddress(offsetBits + indexBits - 1, offsetBits)
  val lookupWordAddress = lookupAddress(offsetBits + indexBits - 1, 2)
  tags.readEnable := lookupEnable
  tags.readAddress := lookupIndex
  val refillComplete = state === State.refill && io.memory.ready &&
    refillWord === (wordsPerLine - 1).U
  val invalidateEntry = state === State.idle && io.cpuRequest && io.cpuWrite
  tags.writeEnable := state === State.clear || refillComplete || invalidateEntry
  tags.writeAddress := Mux(
    state === State.clear,
    clearIndex,
    Mux(refillComplete, missIndex, cpuIndex)
  )
  tags.writeData := Mux(
    refillComplete,
    Cat(true.B, missTag),
    0.U
  )
  val lookupEntry = tags.readData
  val lookupTag = lookupEntry(tagBits - 1, 0)
  val hit = lookupEntry(tagBits) && lookupTag === cpuTag
  val hitData = data.readData

  val refillWrite = state === State.refill && io.memory.ready
  data.readEnable := lookupEnable
  data.readAddress := lookupWordAddress
  data.writeEnable := refillWrite
  data.writeAddress := Mux(
    refillWrite,
    Cat(missIndex, refillWord),
    pendingWriteAddress(memoryAddressBits + 1, 2)
  )
  data.writeData := Mux(refillWrite, io.memory.readData, pendingWriteData)
  data.writeMask := Mux(refillWrite, "b1111".U, pendingWriteMask)
  io.cpuReadData := Mux(state === State.idle, hitData, pendingReadData)
  // AMO arithmetic remains behind a registered copy of the old cache word.
  io.cpuAtomicReadData := pendingReadData
  io.cpuReady := state === State.idle && io.cpuRequest && io.cpuRead &&
    !io.cpuWrite && hit
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
    is(State.clear) {
      when(clearIndex === (lineCount - 1).U) {
        clearIndex := 0.U
        state := State.prime
      }.otherwise {
        clearIndex := clearIndex + 1.U
      }
    }
    is(State.idle) {
      when(io.cpuRequest) {
        when(io.cpuRead && !hit) {
          // An AMO is both a read and a write. Refill first so its old value is valid.
          missBase := io.cpuAddress & lineMask
          missIndex := cpuIndex
          missTag := cpuTag
          refillWord := 0.U
          state := State.refill
        }.elsewhen(io.cpuWrite) {
          pendingWriteAddress := io.cpuAddress
          pendingWriteMask := io.cpuWriteMask
          when(io.cpuRead) {
            pendingReadData := hitData
            state := State.prepareWrite
          }.otherwise {
            pendingWriteData := io.cpuWriteData
            state := State.writeThrough
          }
        }
      }
    }
    is(State.prime) {
      state := State.idle
    }
    is(State.prepareWrite) {
      // Give AMO result generation a full cycle after registering the old value.
      pendingWriteData := io.cpuWriteData
      state := State.writeThrough
    }
    is(State.refill) {
      when(io.memory.ready) {
        when(refillWord === (wordsPerLine - 1).U) {
          // Avoid inferred-memory read-during-write behavior. The following
          // prime cycle reissues the held request to both synchronous memories.
          state := State.prime
        }.otherwise {
          refillWord := refillWord + 1.U
        }
      }
    }
    is(State.writeThrough) {
      when(io.memory.ready) {
        state := State.idle
      }
    }
  }
}
