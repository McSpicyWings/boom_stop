package boom.ifu

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.{Config, Field, Parameters}
import freechips.rocketchip.util.PlusArg
import boom.common._

case object FerretSimulationKey extends Field[Boolean](false)

class WithFerretSimulation extends Config((site, here, up) => {
  case FerretSimulationKey => true
})

private[ifu] object FerretSimulation {
  def cycleCounter(): UInt = {
    val cycle = RegInit(0.U(32.W))
    cycle := cycle + 1.U
    cycle
  }

  def traceEnabled: Bool = PlusArg("ferret_trace", 0, "Trace Ferret BTB and frontend events").orR
}

private[ifu] class FerretSimulation(implicit val p: Parameters) extends HasBoomFrontendParameters {
  val cycle = FerretSimulation.cycleCounter()
  val trace = FerretSimulation.traceEnabled
  val enableF1 = PlusArg("ferret_f1", 0, "Use prefetched non-branch targets on an F1 miss").orR

  // Leave all native F1 redirects and per-slot target hits in place. The main
  // BTB has a branch bit, so non-branch predictions are verified at F3.
  def candidate(eligible: Bool, mask: UInt, fast: Vec[BranchPrediction],
    prefetched: Vec[FerretBtbSlot]): (Bool, UInt, UInt) = {
    val candidates = VecInit((0 until bankWidth).map { slot =>
      mask(slot) && !fast(slot).predicted_pc.valid &&
        prefetched(slot).target.valid && !prefetched(slot).isBr
    })
    val slot = PriorityEncoder(candidates)
    (eligible && candidates.asUInt.orR, slot, prefetched(slot).target.bits)
  }

  def traceQueue(observer: FerretObserver): Unit = {
    val io = observer.io
    when (trace && io.seed.valid) {
      printf(p"FERRET event=seed cycle=${cycle} pc=0x${Hexadecimal(io.seed.bits)} accept=${!io.drop}\n")
    }
    when (trace && io.response.valid) {
      val response = io.response.bits
      val slots = VecInit(response.slots.map(_.target.valid)).asUInt
      printf(p"FERRET event=response cycle=${cycle} pc=0x${Hexadecimal(response.pc)} slots=0x${Hexadecimal(slots)}\n")
      for ((slot, i) <- response.slots.zipWithIndex) {
        when (slot.target.valid) {
          printf(p"FERRET event=fill cycle=${cycle} pc=0x${Hexadecimal(response.pc)} slot=${i.U} target=0x${Hexadecimal(slot.target.bits)} br=${slot.isBr}\n")
        }
      }
    }
  }
}
