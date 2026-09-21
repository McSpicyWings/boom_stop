package boom.common

import chisel3._

// Attribution of this instruction's outgoing prediction, not its fetch origin.
object CommitPredictionSource {
  val width = 3
  val count = 6
  def sequential = 0.U(width.W)
  def f1 = 1.U(width.W)
  def f2 = 2.U(width.W)
  def f3 = 3.U(width.W)
  def decodeRas = 4.U(width.W)
  def core = 5.U(width.W)

  def changed(oldTaken: Bool, oldTarget: UInt, taken: Bool, target: UInt): Bool =
    (oldTaken =/= taken) || (oldTaken && taken && oldTarget =/= target)
}
