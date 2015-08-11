package junctions

import Chisel._

class SMIReq(val dataWidth: Int, val addrWidth: Int) extends Bundle {
  val rw = Bool()
  val addr = UInt(width = addrWidth)
  val data = Bits(width = dataWidth)

  override def cloneType =
    new SMIReq(dataWidth, addrWidth).asInstanceOf[this.type]
}

class SMIIO(val dataWidth: Int, val addrWidth: Int) extends Bundle {
  val req = Decoupled(new SMIReq(dataWidth, addrWidth))
  val resp = Decoupled(Bits(width = dataWidth)).flip

  override def cloneType =
    new SMIIO(dataWidth, addrWidth).asInstanceOf[this.type]
}

abstract class SMIPeripheral extends Module {
  val dataWidth: Int
  val addrWidth: Int

  lazy val io = new SMIIO(dataWidth, addrWidth).flip
}

class SMIMem(val dataWidth: Int, val memDepth: Int) extends SMIPeripheral {
  // override
  val addrWidth = log2Up(memDepth)

  val mem = SeqMem(Bits(width = dataWidth), memDepth)

  val ren = io.req.fire() && !io.req.bits.rw
  val wen = io.req.fire() && io.req.bits.rw

  io.resp.valid := Reg(next = ren)
  io.resp.bits := mem.read(io.req.bits.addr, ren)

  when (wen) { mem.write(io.req.bits.addr, io.req.bits.data) }

  val read_busy = Reg(init = Bool(false))

  when (io.resp.fire()) { read_busy := Bool(false) }
  when (ren) { read_busy := Bool(true) }

  io.resp.valid := read_busy
  io.req.ready := !read_busy || io.resp.ready
}

class SMIArbiter(val n: Int, val dataWidth: Int, val addrWidth: Int)
    extends Module {
  val io = new Bundle {
    val in = Vec.fill(n) { new SMIIO(dataWidth, addrWidth) }.flip
    val out = new SMIIO(dataWidth, addrWidth)
  }

  val reading = Reg(init = Bool(false))
  val choice = Reg(UInt(width = log2Up(n)))

  val req_arb = Module(new RRArbiter(new SMIReq(dataWidth, addrWidth), n))
  req_arb.io.in <> io.in.map(_.req)
  req_arb.io.out.ready := io.out.req.ready && !reading

  io.out.req.bits := req_arb.io.out.bits
  io.out.req.valid := req_arb.io.out.valid && !reading

  when (io.out.req.fire() && !io.out.req.bits.rw) {
    choice := req_arb.io.chosen
    reading := Bool(true)
  }

  when (io.out.resp.fire()) { reading := Bool(false) }

  for ((resp, i) <- io.in.map(_.resp).zipWithIndex) {
    resp.bits := io.out.resp.bits
    resp.valid := io.out.resp.valid && choice === UInt(i)
  }

  io.out.resp.ready := io.in(choice).resp.ready
}

class SMIIONASTIReadIOConverter(val dataWidth: Int, val addrWidth: Int)
    extends NASTIModule {
  val io = new Bundle {
    val ar = Decoupled(new NASTIReadAddressChannel).flip
    val r = Decoupled(new NASTIReadDataChannel)
    val smi = new SMIIO(dataWidth, addrWidth)
  }

  private val maxWordsPerBeat = nastiXDataBits / dataWidth
  private val wordCountBits = log2Up(maxWordsPerBeat)
  private val byteOffBits = log2Up(dataWidth / 8)
  private val addrOffBits = addrWidth + byteOffBits

  assert(!io.ar.valid || io.ar.bits.size >= UInt(byteOffBits),
    "NASTI size must be >= SMI size")

  private def calcWordCount(size: UInt): UInt =
    (UInt(1) << (size - UInt(byteOffBits))) - UInt(1)

  val (s_idle :: s_read :: s_resp :: Nil) = Enum(Bits(), 3)
  val state = Reg(init = s_idle)

  val nWords = Reg(UInt(width = wordCountBits))
  val nBeats = Reg(UInt(width = nastiXLenBits))
  val addr = Reg(UInt(width = addrWidth))
  val id = Reg(UInt(width = nastiRIdBits))

  val sendInd = Reg(init = UInt(0, wordCountBits))
  val recvInd = Reg(init = UInt(0, wordCountBits))
  val sendDone = Reg(init = Bool(false))

  val buffer = Reg(init = Vec.fill(maxWordsPerBeat) { Bits(0, dataWidth) })

  io.ar.ready := (state === s_idle)

  io.smi.req.valid := (state === s_read) && !sendDone
  io.smi.req.bits.rw := Bool(false)
  io.smi.req.bits.addr := addr

  io.smi.resp.ready := (state === s_read)

  io.r.valid := (state === s_resp)
  io.r.bits.resp := Bits(0)
  io.r.bits.data := buffer.toBits
  io.r.bits.id := id
  io.r.bits.last := (nBeats === UInt(0))

  when (io.ar.fire()) {
    nWords := calcWordCount(io.ar.bits.size)
    nBeats := io.ar.bits.len
    addr := io.ar.bits.addr(addrOffBits - 1, byteOffBits)
    id := io.ar.bits.id
    state := s_read
  }

  when (io.smi.req.fire()) {
    addr := addr + UInt(1)
    sendInd := sendInd + UInt(1)
    sendDone := (sendInd === nWords)
  }

  when (io.smi.resp.fire()) {
    recvInd := recvInd + UInt(1)
    buffer(recvInd) := io.smi.resp.bits
    when (recvInd === nWords) { state := s_resp }
  }

  when (io.r.fire()) {
    recvInd := UInt(0)
    sendInd := UInt(0)
    sendDone := Bool(false)
    // clear all the registers in the buffer
    buffer.foreach(_ := Bits(0))
    nBeats := nBeats - UInt(1)
    state := Mux(io.r.bits.last, s_idle, s_read)
  }
}

class SMIIONASTIWriteIOConverter(val dataWidth: Int, val addrWidth: Int)
    extends NASTIModule {
  val io = new Bundle {
    val aw = Decoupled(new NASTIWriteAddressChannel).flip
    val w = Decoupled(new NASTIWriteDataChannel).flip
    val b = Decoupled(new NASTIWriteResponseChannel)
    val smi = new SMIIO(dataWidth, addrWidth)
  }

  private val dataBytes = dataWidth / 8
  private val maxWordsPerBeat = nastiXDataBits / dataWidth
  private val byteOffBits = log2Floor(dataBytes)
  private val addrOffBits = addrWidth + byteOffBits

  assert(!io.aw.valid || io.aw.bits.size >= UInt(byteOffBits),
    "NASTI size must be >= SMI size")

  val id = Reg(UInt(width = nastiWIdBits))
  val addr = Reg(UInt(width = addrWidth))

  def makeStrobe(size: UInt, strb: UInt) = {
    val sizemask = (UInt(1) << (UInt(1) << size)) - UInt(1)
    val bytemask = sizemask & strb
    Vec.tabulate(maxWordsPerBeat){i => bytemask(dataBytes * i)}.toBits
    //val strbmask = Vec.tabulate(maxWordsPerBeat){i => strb(dataBytes * i)}.toBits
    //sizemask & strbmask
  }

  val size = Reg(UInt(width = nastiXSizeBits))
  val strb = Reg(UInt(width = maxWordsPerBeat))
  val data = Reg(UInt(width = nastiXDataBits))
  val last = Reg(Bool())

  val s_idle :: s_data :: s_send :: s_resp :: Nil = Enum(Bits(), 4)
  val state = Reg(init = s_idle)

  io.aw.ready := (state === s_idle)
  io.w.ready := (state === s_data)
  io.smi.req.valid := (state === s_send) && strb(0)
  io.smi.req.bits.rw := Bool(true)
  io.smi.req.bits.addr := addr
  io.smi.req.bits.data := data(dataWidth - 1, 0)
  io.b.valid := (state === s_resp)
  io.b.bits.resp := Bits(0)
  io.b.bits.id := id

  val jump = PriorityMux(strb(maxWordsPerBeat - 1, 1),
    (1 until maxWordsPerBeat).map(UInt(_)))

  when (io.aw.fire()) {
    addr := io.aw.bits.addr(addrOffBits - 1, byteOffBits)
    id := io.aw.bits.id
    //size := io.aw.bits.size - UInt(byteOffBits)
    size := io.aw.bits.size
    last := Bool(false)
    state := s_data
  }

  when (io.w.fire()) {
    last := io.w.bits.last
    strb := makeStrobe(size, io.w.bits.strb)
    data := io.w.bits.data
    state := s_send
  }

  when (state === s_send) {
    when (strb === UInt(0)) {
      state := Mux(last, s_resp, s_data)
    } .elsewhen (io.smi.req.ready || !strb(0)) {
      strb := strb >> jump
      data := data >> Cat(jump, UInt(0, log2Up(dataWidth)))
      addr := addr + jump
    }
  }

  when (io.b.fire()) { state := s_idle }
}

class SMIIONASTISlaveIOConverter(val dataWidth: Int, val addrWidth: Int)
    extends NASTIModule {
  val io = new Bundle {
    val nasti = new NASTISlaveIO
    val smi = new SMIIO(dataWidth, addrWidth)
  }

  require(isPow2(dataWidth))

  val reader = Module(new SMIIONASTIReadIOConverter(dataWidth, addrWidth))
  reader.io.ar <> io.nasti.ar
  io.nasti.r <> reader.io.r

  val writer = Module(new SMIIONASTIWriteIOConverter(dataWidth, addrWidth))
  writer.io.aw <> io.nasti.aw
  writer.io.w <> io.nasti.w
  io.nasti.b <> writer.io.b

  val arb = Module(new SMIArbiter(2, dataWidth, addrWidth))
  arb.io.in(0) <> reader.io.smi
  arb.io.in(1) <> writer.io.smi
  io.smi <> arb.io.out
}