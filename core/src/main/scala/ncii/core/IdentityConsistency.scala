package ncii.core

/** Rejects an upload set that contains more than one person.
  *
  * The method combines a primary centroid-based test with a secondary minimum-pairwise
  * test to detect both outliers and even-split scenarios.
  *
  * **Primary test (centroid):** compute the mean embedding, then measure each member's
  * similarity to it. A set of one person clusters tightly around its own mean; a set
  * containing someone else has at least one member far from it. Measured on legitimate
  * single-identity sets, the minimum similarity to centroid is approximately 0.714 with
  * 0.36 margin to the 0.35 threshold.
  *
  * **Secondary test (minimum-pairwise):** the centroid sits exactly halfway between two
  * equal clusters, so every member looks fine against the centroid even though the set
  * should be rejected. The secondary test computes the worst pairwise similarity across
  * all members. On legitimate sets this is approximately 0.404; on an even split it
  * drops to approximately 0.00; on an intruder set it reaches approximately -0.05.
  * The threshold of 0.175 sits between the legitimate case (~0.40) and both failure modes
  * (~0.00 or negative), providing robust separation while leaving 0.23 margin to the
  * primary threshold.
  *
  * A full clustering algorithm would be more general, but enrolment sets are 5-10 photos
  * and the failure being caught is gross, a different face, not a subtle one.
  *
  * Without this gate a mistaken upload silently poisons the gallery and every later
  * false negative is undiagnosable. See the design spec, section 5.
  */
object IdentityConsistency:

  enum Verdict:
    case Consistent
    /** A set that failed consistency checks.
      *
      * `outlierCount` is the number of members whose similarity to the centroid falls
      * below the primary threshold (0.35). This can be zero even when the verdict is
      * `Mixed`, that indicates the set passed the centroid test but failed the
      * secondary pairwise test, meaning the set splits into two equal clusters without
      * any single member being an outlier against the centroid.
      *
      * `minSimilarity` is the worst pairwise similarity between any two members, the
      * most interpretable diagnostic: "the two photos that agree least".
      */
    case Mixed(outlierCount: Int, minSimilarity: Float)

  def check(
      embeddings: Seq[Embedding],
      threshold: Float = 0.35f,
      splitThreshold: Float = 0.175f
  ): Verdict =
    if embeddings.isEmpty then
      throw new IllegalArgumentException("cannot judge an empty set of embeddings")

    if embeddings.sizeIs == 1 then Verdict.Consistent
    else
      val centroid = Embedding.mean(embeddings)
      val sims     = embeddings.map(_.cosine(centroid))
      val outliers = sims.count(_ < threshold)

      val pairs = for
        i <- embeddings.indices
        j <- (i + 1) until embeddings.size
      yield embeddings(i).cosine(embeddings(j))
      val worstPair = pairs.min

      if outliers > 0 || worstPair < splitThreshold then
        Verdict.Mixed(outliers, worstPair)
      else Verdict.Consistent
