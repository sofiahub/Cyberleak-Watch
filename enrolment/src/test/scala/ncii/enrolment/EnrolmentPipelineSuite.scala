package ncii.enrolment

import ncii.core.{Embedding, IdentityConsistency}
import ncii.store.EnrolmentOutcome

class EnrolmentPipelineSuite extends munit.FunSuite:

  private def unit(seed: Int): Embedding =
    val rng = new scala.util.Random(seed)
    Embedding.normalised(Array.fill(512)(rng.nextGaussian().toFloat))

  private def near(seed: Int, base: Embedding): Embedding =
    val rng = new scala.util.Random(seed)
    Embedding.normalised(base.values.toArray.map(v => v + rng.nextGaussian().toFloat * 0.05f))

  test("too few usable photographs is rejected before any encryption happens") {
    // The spec requires at least three usable photos. Rejecting early matters because
    // the alternative is encrypting a gallery too thin to match reliably.
    val base = unit(1)
    val result = EnrolmentPipeline.decide(Seq(base, near(2, base)), minimumUsable = 3)
    assertEquals(result, EnrolmentResult.Rejected(EnrolmentOutcome.RejectedTooFewPhotos,
      "2 usable photographs, at least 3 required"))
  }

  test("a mixed-identity set is rejected at the minimum usable size") {
    // Four photographs, one of them a different person. This is the smallest set the spec will
    // consider. The intruder is caught here by the pairwise split check, not by the
    // centroid: with only three legitimate photographs the centroid barely moves away
    // from the intruder, so its similarity to the centroid lands around 0.34-0.42 and
    // sits on the wrong side of the 0.35 outlier threshold most of the time (measured:
    // flagged in 2 of 12 random draws). Rejection is what this test pins; the outlier
    // COUNT is not reliable at this size, so asserting on it here would only be pinning
    // one lucky seed. The count is pinned in the next test, at a size where it holds.
    val base = unit(1)
    val set  = Seq(base, near(2, base), near(3, base), unit(999))
    EnrolmentPipeline.decide(set, minimumUsable = 3) match
      case EnrolmentResult.Rejected(EnrolmentOutcome.RejectedMixedIdentity, reason) =>
        // At this size the rejection comes from the pairwise split check with no single
        // outlier, so the reason takes the wording reserved for that case rather than
        // claiming a count of zero photographs shows a different person.
        assert(
          reason.startsWith("the photographs do not all show the same person"),
          s"expected the split wording at the minimum set size, got: '$reason'"
        )
      case other => fail(s"expected a mixed-identity rejection, got $other")
  }

  test("a mixed-identity set names the outlier count once the majority is clear") {
    // Five legitimate photographs plus an intruder. Here the centroid is pulled firmly
    // onto the real identity, the intruder's similarity to it drops to about 0.20-0.31,
    // and the centroid path flags exactly one outlier in every measured draw (12 of 12).
    // This is where asserting the count means something.
    val base = unit(1)
    val set  = (base +: (2 to 5).map(s => near(s, base))) :+ unit(999)
    EnrolmentPipeline.decide(set, minimumUsable = 3) match
      case EnrolmentResult.Rejected(EnrolmentOutcome.RejectedMixedIdentity, reason) =>
        assert(
          reason.startsWith("1 photograph"),
          s"expected the outlier count to lead the reason, got: '$reason'"
        )
      case other => fail(s"expected a mixed-identity rejection, got $other")
  }

  test("a consistent set of sufficient size is accepted with its selected count") {
    val base = unit(1)
    val set = (2 to 8).map(s => near(s, base)) :+ base
    EnrolmentPipeline.decide(set, minimumUsable = 3) match
      case EnrolmentResult.Accepted(selected) =>
        assert(selected.size >= 3 && selected.size <= 5,
          s"expected 3-5 selected vectors, got ${selected.size}")
      case other => fail(s"expected acceptance, got $other")
  }

  test("acceptance selects distinct vectors rather than repeating one") {
    val base = unit(1)
    val set = (2 to 8).map(s => near(s, base)) :+ base
    EnrolmentPipeline.decide(set, minimumUsable = 3) match
      case EnrolmentResult.Accepted(selected) =>
        val distinct = selected.map(_.values.toSeq).distinct
        assertEquals(distinct.size, selected.size, "selection repeated an embedding")
      case other => fail(s"expected acceptance, got $other")
  }
