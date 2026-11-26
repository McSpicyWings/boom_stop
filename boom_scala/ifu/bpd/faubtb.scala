package boom.ifu

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._

import boom.common._
import boom.util.{BoomCoreStringPrefix, WrapInc}

import scala.math.min

case class BoomFAMicroBTBParams(
  nWays: Int = 16,
  offsetSz: Int = 13
)


class FAMicroBTBBranchPredictorBank(params: BoomFAMicroBTBParams = BoomFAMicroBTBParams())(implicit p: Parameters) extends BranchPredictorBank()(p)
{
  override val nWays         = params.nWays
  val tagSz         = vaddrBitsExtended - log2Ceil(fetchWidth) - 1
  val offsetSz      = params.offsetSz

  def bimWrite(v: UInt, taken: Bool): UInt = {
    val old_bim_sat_taken  = v === 3.U
    val old_bim_sat_ntaken = v === 0.U
    Mux(old_bim_sat_taken  &&  taken, 3.U,
      Mux(old_bim_sat_ntaken && !taken, 0.U,
      Mux(taken, v + 1.U, v - 1.U)))
  }

  require(isPow2(nWays))

  class MicroBTBEntry extends Bundle {
    val offset   = SInt(offsetSz.W)
  }

  class MicroBTBMeta extends Bundle {
    val is_br = Bool()
    val tag   = UInt(tagSz.W)
    val ctr   = UInt(2.W)
    val entry_valid = Bool()
    val target_valid = Bool()
    val ctr_valid = Bool()
    val f3_dir_valid = Bool()
    val f3_taken = Bool()
  }

  def branchTaken(entry: MicroBTBMeta): Bool =
    Mux(entry.f3_dir_valid, entry.f3_taken, entry.ctr_valid && entry.ctr(1))

  class MicroBTBPredictMeta extends Bundle {
    val hits  = Vec(bankWidth, Bool())
    val write_way = UInt(log2Ceil(nWays).W)
  }

  val s1_meta = Wire(new MicroBTBPredictMeta)
  override val metaSz = s1_meta.asUInt.getWidth


  val meta     = RegInit((0.U).asTypeOf(Vec(nWays, Vec(bankWidth, new MicroBTBMeta))))
  val btb      = Reg(Vec(nWays, Vec(bankWidth, new MicroBTBEntry)))

  val mems = Nil

  val s1_req_tag   = s1_idx


  val s1_resp   = Wire(Vec(bankWidth, Valid(UInt(vaddrBitsExtended.W))))
  val s1_taken  = Wire(Vec(bankWidth, Bool()))
  val s1_is_br  = Wire(Vec(bankWidth, Bool()))
  val s1_is_jal = Wire(Vec(bankWidth, Bool()))

  val s1_hit_ohs = VecInit((0 until bankWidth) map { i =>
    VecInit((0 until nWays) map { w =>
      meta(w)(i).entry_valid && meta(w)(i).tag === s1_req_tag(tagSz-1,0)
    })
  })
  val s1_hits     = s1_hit_ohs.map { oh => oh.reduce(_||_) }
  val s1_hit_ways = s1_hit_ohs.map { oh => PriorityEncoder(oh) }

  for (w <- 0 until bankWidth) {
    val entry_meta = meta(s1_hit_ways(w))(w)
    s1_resp(w).valid := s1_valid && s1_hits(w) && entry_meta.target_valid
    s1_resp(w).bits  := (s1_pc.asSInt + (w << 1).S + btb(s1_hit_ways(w))(w).offset).asUInt
    s1_is_br(w)      := s1_resp(w).valid &&  entry_meta.is_br
    s1_is_jal(w)     := s1_resp(w).valid && !entry_meta.is_br
    s1_taken(w)      := !entry_meta.is_br || branchTaken(entry_meta)

    s1_meta.hits(w)     := s1_hits(w)
  }
  // Keep the packed FTQ metadata layout. Writes select a way from the current table.
  s1_meta.write_way := PriorityEncoder(s1_hit_ohs.map(_.asUInt).reduce(_|_))

  for (w <- 0 until bankWidth) {
    io.resp.f1(w).predicted_pc := s1_resp(w)
    io.resp.f1(w).is_br        := s1_is_br(w)
    io.resp.f1(w).is_jal       := s1_is_jal(w)
    io.resp.f1(w).taken        := s1_taken(w)

    io.resp.f2(w) := RegNext(io.resp.f1(w))
    io.resp.f3(w) := RegNext(io.resp.f2(w))
  }
  io.f3_meta := RegNext(RegNext(s1_meta.asUInt))

  val commit = s1_update.bits
  val actualTaken = Mux(commit.cfi_idx.valid && commit.cfi_taken,
    UIntToOH(commit.cfi_idx.bits, bankWidth), 0.U(bankWidth.W))
  val commitMask = commit.br_mask | actualTaken
  val doCommit = s1_update.valid && commit.is_commit_update && commitMask.orR

  val killEarly = io.faubtb_early_kill
  val earlyS1 = RegNext(io.faubtb_early_update.bits)
  val earlyS1Valid = RegNext(io.faubtb_early_update.valid && !killEarly, false.B)
  val canWriteEarly = enableFAuBTBEarlyUpdate.B && earlyS1Valid && !killEarly && !doCommit
  val updatePC = Mux(doCommit, commit.pc, earlyS1.pc)
  val updateTag = fetchIdx(updatePC)(tagSz - 1, 0)
  val updateBrs = Mux(doCommit, commit.br_mask, earlyS1.br_mask)

  val groupHits = VecInit(meta.map { way =>
    way.map(entry => entry.entry_valid && entry.tag === updateTag).reduce(_ || _)
  }).asUInt
  val emptyWays = VecInit(meta.map(way => !way.map(_.entry_valid).reduce(_ || _))).asUInt
  val hashedWay = if (nWays == 1) 0.U else {
    val tagsAndPC = Cat(VecInit(meta.map(way => VecInit(way.map(_.tag)))).asUInt, updateTag)
    val width = log2Ceil(nWays)
    (0 until tagsAndPC.getWidth by width).map { start =>
      tagsAndPC(min(start + width, tagsAndPC.getWidth) - 1, start)
    }.reduce(_ ^ _)
  }
  val updateWay = Mux(groupHits.orR, PriorityEncoder(groupHits),
    Mux(emptyWays.orR, PriorityEncoder(emptyWays), hashedWay))

  val minOffset = (-(BigInt(1) << (offsetSz - 1))).S
  val maxOffset = ((BigInt(1) << (offsetSz - 1)) - 1).S
  val earlyMask = Wire(Vec(bankWidth, Bool()))
  for (slot <- 0 until bankWidth) {
    val entry = meta(updateWay)(slot)
    val data = btb(updateWay)(slot)
    val isBr = updateBrs(slot)
    val same = entry.entry_valid && entry.tag === updateTag && entry.is_br === isBr
    val target = Mux(doCommit, commit.target, earlyS1.targets(slot))
    // Slots past an RVC boundary still use the bank's slot PC as the base.
    val slotPC = updatePC + (slot << 1).U
    val delta = target.zext - slotPC.zext
    val targetFits = !target(0) && delta >= minOffset && delta <= maxOffset
    val dirEnabled = enableFAuBTBEarlyBRDir.B && isBr

    earlyMask(slot) := earlyS1.slot_mask(slot) && targetFits &&
      (!same || !entry.target_valid || data.offset =/= delta ||
        (dirEnabled && branchTaken(entry) =/= earlyS1.taken_mask(slot)))

    when ((doCommit && commitMask(slot)) || (canWriteEarly && earlyMask(slot))) {
      entry.tag := updateTag
      entry.entry_valid := true.B
      entry.is_br := isBr
      when (!doCommit || actualTaken(slot)) {
        entry.target_valid := targetFits
        when (targetFits) { data.offset := delta }
      }.elsewhen (!same) {
        entry.target_valid := false.B
      }

      when (doCommit) {
        entry.ctr_valid := isBr
        when (isBr) {
          entry.ctr := Mux(same && entry.ctr_valid, bimWrite(entry.ctr, actualTaken(slot)),
            Mux(actualTaken(slot), 3.U, 0.U))
        }.otherwise {
          entry.ctr := 1.U
        }
        entry.f3_dir_valid := false.B
      }.otherwise {
        when (!same) {
          entry.ctr := 1.U
          entry.ctr_valid := false.B
        }
        entry.f3_dir_valid := dirEnabled
        entry.f3_taken := earlyS1.taken_mask(slot)
      }
    }
  }
  when (doCommit || (canWriteEarly && earlyMask.asUInt.orR)) {
    assert(PopCount(groupHits) <= 1.U, "FAuBTB group matched multiple ways")
  }
  when (killEarly) {
    for (way <- meta; entry <- way) { entry.f3_dir_valid := false.B }
  }

}

