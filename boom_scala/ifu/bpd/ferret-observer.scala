package boom.ifu

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import boom.common._

// Event order is shared by the compact FPGA and Chipyard maps.
object FerretEvents {
  val frontendCount = 23
  val fpga = Seq(6, 7, 8, 9, 10, 11, 12, 13, 22, 23, 24, 25, 26, 27, 28, 32, 34, 48, 49, 50, 51, 52, 53, 16, 17)
  val chipyard = (2 to 24) ++ Seq(62, 63)
}

class FerretBtbSlot(implicit p: Parameters) extends BoomBundle()(p) {
  val target = Valid(UInt(vaddrBitsExtended.W))
  val isBr = Bool()
}

class FerretBtbRead(implicit p: Parameters) extends BoomBundle()(p)
    with HasBoomFrontendParameters {
  val pc = UInt(vaddrBitsExtended.W)
  val slots = Vec(bankWidth, new FerretBtbSlot)
}

// One pending seed, eight direct-mapped first-bank results. This module
// observes F1 lookups and never drives the fetch PC or predictor updates.
class FerretObserver(implicit p: Parameters) extends BoomModule()(p)
    with HasBoomFrontendParameters {
  val io = IO(new Bundle {
    val seed = Input(Valid(UInt(vaddrBitsExtended.W)))
    val read = Decoupled(UInt(vaddrBitsExtended.W))
    val response = Input(Valid(new FerretBtbRead))
    val lookup = Input(Valid(UInt(vaddrBitsExtended.W)))
    val slots = Output(Vec(bankWidth, new FerretBtbSlot))
    val pendingMatch = Output(Bool())
    val drop = Output(Bool())
    val fill = Output(Bool())
    val unusedEviction = Output(Bool())
  })

  val pending = RegInit(false.B)
  val pendingPc = Reg(UInt(vaddrBitsExtended.W))
  io.read.valid := pending
  io.read.bits := pendingPc
  val issued = RegNext(io.read.fire(), false.B)
  val issuedPc = RegEnable(pendingPc, io.read.fire())
  val accept = io.seed.valid && (!pending || io.read.fire())
  io.drop := io.seed.valid && !accept
  when (io.read.fire()) { pending := false.B }
  when (accept) {
    pending := true.B
    pendingPc := io.seed.bits
  }

  val valid = RegInit(VecInit(Seq.fill(8)(false.B)))
  val used = RegInit(VecInit(Seq.fill(8)(false.B)))
  val entries = Reg(Vec(8, new FerretBtbRead))
  def index(pc: UInt): UInt = (pc >> log2Ceil(bankBytes))(2,0)
  val demandIndex = index(io.lookup.bits)
  val fillIndex = index(io.response.bits.pc)
  val hit = io.lookup.valid && valid(demandIndex) && entries(demandIndex).pc === io.lookup.bits
  io.pendingMatch := io.lookup.valid && ((pending && pendingPc === io.lookup.bits) ||
    (issued && issuedPc === io.lookup.bits))
  io.slots := Mux(hit, entries(demandIndex).slots, 0.U.asTypeOf(io.slots))
  when (hit) { used(demandIndex) := true.B }

  // A result written at this edge is visible to F1 starting next cycle.
  io.fill := io.response.valid && io.response.bits.slots.map(_.target.valid).reduce(_ || _)
  val sameEntry = entries(fillIndex).pc === io.response.bits.pc
  io.unusedEviction := io.fill && valid(fillIndex) && !sameEntry && !used(fillIndex) &&
    !(hit && demandIndex === fillIndex)
  when (io.fill) {
    used(fillIndex) := valid(fillIndex) && sameEntry &&
      (used(fillIndex) || (hit && demandIndex === fillIndex))
    valid(fillIndex) := true.B
    entries(fillIndex) := io.response.bits
  }
  assert(!io.response.valid || issued)
}

class FerretFetchSnapshot(implicit p: Parameters) extends BoomBundle()(p)
    with HasBoomFrontendParameters {
  val fast = Vec(fetchWidth, Valid(UInt(vaddrBitsExtended.W)))
  val main = Vec(fetchWidth, new FerretBtbSlot)
  val prefetched = Vec(bankWidth, new FerretBtbSlot)
  val pendingMatch = Bool()
  val replay = Bool()
  val f1Sequential = Bool()
  val f2Resteer = Bool()
  val f2Index = UInt(log2Ceil(fetchWidth).W)
}
