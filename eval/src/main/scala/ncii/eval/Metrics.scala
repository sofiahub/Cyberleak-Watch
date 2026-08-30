package ncii.eval

final case class ScoredPair(score: Float, sameIdentity: Boolean)

final case class RocPoint(threshold: Float, falseAcceptRate: Double, trueAcceptRate: Double)

/** Verification metrics for face recognition.
  *
  * The operating point of interest is TAR at a low FAR, not accuracy. A false
  * accept here means telling someone their likeness appears in intimate imagery
  * when it does not, so the false-accept budget is the constraint and the
  * true-accept rate is whatever that budget allows.
  */
object Metrics:

  /** ROC points, one per candidate threshold, ordered by increasing false-accept rate.
    *
    * Threshold direction: higher score → more similar → more likely genuine.
    * At each threshold, we accept all pairs with score >= threshold.
    *
    * Tie handling: when scores are tied across classes (a genuine and an impostor
    * share the same score), we resolve ties by treating all tied pairs together,
    * so both are either accepted or rejected at any given threshold. This is the
    * "pessimistic" approach: we don't silently split tied pairs by class.
    */
  def roc(pairs: Seq[ScoredPair]): Seq[RocPoint] =
    validate(pairs)
    val genuine  = pairs.count(_.sameIdentity).toDouble
    val impostor = pairs.count(!_.sameIdentity).toDouble

    val thresholds = (pairs.map(_.score).distinct :+ Float.PositiveInfinity).sorted.reverse

    thresholds.map { t =>
      val accepted = pairs.filter(_.score >= t)
      RocPoint(
        threshold       = t,
        falseAcceptRate = accepted.count(!_.sameIdentity) / impostor,
        trueAcceptRate  = accepted.count(_.sameIdentity) / genuine
      )
    }

  /** Area under the ROC curve, by the trapezium rule. */
  def auc(points: Seq[RocPoint]): Double =
    val sorted = points.sortBy(_.falseAcceptRate)
    sorted
      .sliding(2)
      .collect { case Seq(a, b) =>
        (b.falseAcceptRate - a.falseAcceptRate) * (a.trueAcceptRate + b.trueAcceptRate) / 2.0
      }
      .sum

  /** Highest true-accept rate achievable without exceeding `targetFar`,
    * with the threshold that achieves it.
    *
    * Validates that the requested FAR is achievable: rejects FAR values finer than
    * 1/impostorCount, since those cannot be resolved with the given data.
    *
    * Example: with 3000 impostor pairs, the finest resolvable FAR is 1/3000 ≈ 3.33e-4.
    * Requesting FAR = 1e-4 is impossible and will throw.
    */
  def tarAtFar(pairs: Seq[ScoredPair], targetFar: Double): (Double, Float) =
    validate(pairs)
    require(targetFar >= 0.0 && targetFar <= 1.0, s"FAR must be in [0,1], got $targetFar")

    val impostorCount = pairs.count(!_.sameIdentity)
    val finestResolvableFar = 1.0 / impostorCount

    require(
      targetFar >= finestResolvableFar || targetFar == 0.0,
      s"FAR = $targetFar is finer than 1/$impostorCount = $finestResolvableFar; " +
        s"need at least ${math.ceil(1.0 / targetFar).toInt} impostor pairs to resolve FAR = $targetFar"
    )

    val affordable = roc(pairs).filter(_.falseAcceptRate <= targetFar)
    require(
      affordable.nonEmpty,
      s"no threshold achieves a false-accept rate of $targetFar"
    )
    val best = affordable.maxBy(_.trueAcceptRate)
    (best.trueAcceptRate, best.threshold)

  /** The rate at which false accepts and false rejects are equal, with its threshold.
    *
    * EER interpolation: when FAR and FRR do not cross exactly (the common case with
    * finite data), we return the point where they are closest. If multiple points are
    * equally close, we return the first (lowest threshold). The EER value itself is
    * the average of FAR and FRR at that point: (FAR + FRR) / 2.
    */
  def equalErrorRate(pairs: Seq[ScoredPair]): (Double, Float) =
    validate(pairs)
    val closest = roc(pairs).minBy(p => math.abs(p.falseAcceptRate - (1.0 - p.trueAcceptRate)))
    ((closest.falseAcceptRate + (1.0 - closest.trueAcceptRate)) / 2.0, closest.threshold)

  private def validate(pairs: Seq[ScoredPair]): Unit =
    require(pairs.nonEmpty, "cannot compute metrics over no pairs")
    require(pairs.exists(_.sameIdentity), "no genuine pairs in input")
    require(pairs.exists(!_.sameIdentity), "no impostor pairs in input")
