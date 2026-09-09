package boom.ifu

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._

import boom.common._
import boom.util.{BoomCoreStringPrefix}


class ComposedBranchPredictorBank(implicit p: Parameters) extends BranchPredictorBank()(p)
{

  val (components, resp) = getBPDComponents(io.resp_in(0), p)
  io.resp := resp


  var metas = 0.U(1.W)
  var meta_sz = 0
  for (c <- components) {
    c.io.f0_valid  := io.f0_valid
    c.io.f0_pc     := io.f0_pc
    c.io.f0_mask   := io.f0_mask
    c.io.f1_ghist  := io.f1_ghist
    c.io.f1_lhist  := io.f1_lhist
    c.io.f3_fire   := io.f3_fire
    if (c.metaSz > 0) {
      metas = (metas << c.metaSz) | c.io.f3_meta(c.metaSz-1,0)
    }
    meta_sz = meta_sz + c.metaSz
  }
  require(meta_sz < bpdMaxMetaLength)
  io.f3_meta := metas

  //PerfCounterSupport: aggregate perf from all sub-predictor components
  io.perf.tage_any_hit       := components.map(_.io.perf.tage_any_hit).reduce(_|_)
  io.perf.tage_provider_t0   := components.map(_.io.perf.tage_provider_t0).reduce(_|_)
  io.perf.tage_provider_t1   := components.map(_.io.perf.tage_provider_t1).reduce(_|_)
  io.perf.tage_provider_t2   := components.map(_.io.perf.tage_provider_t2).reduce(_|_)
  io.perf.tage_provider_t3   := components.map(_.io.perf.tage_provider_t3).reduce(_|_)
  io.perf.tage_provider_t4   := components.map(_.io.perf.tage_provider_t4).reduce(_|_)
  io.perf.tage_provider_t5   := components.map(_.io.perf.tage_provider_t5).reduce(_|_)
  io.perf.tage_alloc_attempt := components.map(_.io.perf.tage_alloc_attempt).reduce(_||_)
  io.perf.tage_alloc_success := components.map(_.io.perf.tage_alloc_success).reduce(_||_)
  io.perf.tage_alloc_fail    := components.map(_.io.perf.tage_alloc_fail).reduce(_||_)
  io.perf.tage_alloc_t0      := components.map(_.io.perf.tage_alloc_t0).reduce(_||_)
  io.perf.tage_alloc_t1      := components.map(_.io.perf.tage_alloc_t1).reduce(_||_)
  io.perf.tage_alloc_t2      := components.map(_.io.perf.tage_alloc_t2).reduce(_||_)
  io.perf.tage_alloc_t3      := components.map(_.io.perf.tage_alloc_t3).reduce(_||_)
  io.perf.tage_alloc_t4      := components.map(_.io.perf.tage_alloc_t4).reduce(_||_)
  io.perf.tage_alloc_t5      := components.map(_.io.perf.tage_alloc_t5).reduce(_||_)
  io.perf.tage_alloc_fail_no_provider := components.map(_.io.perf.tage_alloc_fail_no_provider).reduce(_||_)
  io.perf.tage_alloc_fail_provider_t0 := components.map(_.io.perf.tage_alloc_fail_provider_t0).reduce(_||_)
  io.perf.tage_alloc_fail_provider_t1 := components.map(_.io.perf.tage_alloc_fail_provider_t1).reduce(_||_)
  io.perf.tage_alloc_fail_provider_t2 := components.map(_.io.perf.tage_alloc_fail_provider_t2).reduce(_||_)
  io.perf.tage_alloc_fail_provider_t3 := components.map(_.io.perf.tage_alloc_fail_provider_t3).reduce(_||_)
  io.perf.tage_alloc_fail_provider_t4 := components.map(_.io.perf.tage_alloc_fail_provider_t4).reduce(_||_)
  io.perf.loop_override_cnt  := components.map(_.io.perf.loop_override_cnt).reduce(_|_)
  io.perf.loop_override_misp := components.map(_.io.perf.loop_override_misp).reduce(_||_)
  io.perf.loop_pcnt_modified := components.map(_.io.perf.loop_pcnt_modified).reduce(_||_)
  io.perf.loop_conf_degraded := components.map(_.io.perf.loop_conf_degraded).reduce(_||_)

  var update_meta = io.update.bits.meta
  for (c <- components.reverse) {
    c.io.update := io.update
    c.io.update.bits.meta := update_meta
    update_meta = update_meta >> c.metaSz
  }

  val mems = components.map(_.mems).flatten

}
