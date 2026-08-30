package ncii.core

class IdentityConsistencySuite extends munit.FunSuite:

  private def near(seed: Int, base: Embedding, jitter: Float): Embedding =
    val rng = new scala.util.Random(seed)
    Embedding.normalised(
      base.values.toArray.map(v => v + (rng.nextGaussian().toFloat * jitter))
    )

  private def unit(seed: Int): Embedding =
    val rng = new scala.util.Random(seed)
    Embedding.normalised(Array.fill(512)(rng.nextGaussian().toFloat))

  test("photographs of one person are consistent") {
    val base = unit(1)
    val set = base +: (2 to 6).map(s => near(s, base, 0.05f))
    assertEquals(IdentityConsistency.check(set), IdentityConsistency.Verdict.Consistent)
  }

  test("a set containing a second person is rejected") {
    // The failure this gate exists for: someone uploads five photos of themselves and
    // one of a friend. Accepting it would poison the gallery silently.
    val base = unit(1)
    val intruder = unit(999)
    val set = (base +: (2 to 5).map(s => near(s, base, 0.05f))) :+ intruder

    IdentityConsistency.check(set) match
      case IdentityConsistency.Verdict.Mixed(outliers, minSim) =>
        assertEquals(outliers, 1)
        assert(minSim < 0.35f, s"expected a low minimum similarity, got $minSim")
      case other => fail(s"expected Mixed, got $other")
  }

  test("two people in equal numbers are rejected") {
    val a = unit(1)
    val b = unit(999)
    val set = Seq(a, near(2, a, 0.05f), near(3, a, 0.05f), b, near(4, b, 0.05f), near(5, b, 0.05f))
    assert(
      IdentityConsistency.check(set).isInstanceOf[IdentityConsistency.Verdict.Mixed],
      "an evenly split set must be rejected"
    )
  }

  test("a single photograph is trivially consistent") {
    assertEquals(
      IdentityConsistency.check(Seq(unit(1))),
      IdentityConsistency.Verdict.Consistent
    )
  }

  test("an empty set is rejected as an argument error rather than judged") {
    intercept[IllegalArgumentException](IdentityConsistency.check(Nil))
  }

  test("an evenly split set is caught by the pairwise check, not the centroid") {
    // The centroid sits between two equal clusters, so no member is an outlier against it.
    // This is precisely the case the secondary pairwise threshold exists to catch.
    val a   = unit(1)
    val b   = unit(999)
    val set = Seq(a, near(2, a, 0.05f), near(3, a, 0.05f), b, near(4, b, 0.05f), near(5, b, 0.05f))

    IdentityConsistency.check(set) match
      case IdentityConsistency.Verdict.Mixed(outliers, worstPair) =>
        assertEquals(outliers, 0, "the centroid alone should not flag an even split")
        assert(worstPair < 0.175f, s"expected a low worst pair, got $worstPair")
      case other => fail(s"expected Mixed, got $other")
  }
