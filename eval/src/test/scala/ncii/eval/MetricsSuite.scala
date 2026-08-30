package ncii.eval

class MetricsSuite extends munit.FunSuite:

  /** Perfectly separated scores: every genuine pair above every impostor pair. */
  private val separable = Seq(
    ScoredPair(0.9f, true),
    ScoredPair(0.8f, true),
    ScoredPair(0.7f, true),
    ScoredPair(0.3f, false),
    ScoredPair(0.2f, false),
    ScoredPair(0.1f, false)
  )

  test("AUC of a perfectly separable set is 1") {
    assertEqualsDouble(Metrics.auc(Metrics.roc(separable)), 1.0, 1e-6)
  }

  test("AUC of a fully inverted set is 0") {
    val inverted = separable.map(p => p.copy(sameIdentity = !p.sameIdentity))
    assertEqualsDouble(Metrics.auc(Metrics.roc(inverted)), 0.0, 1e-6)
  }

  test("TAR at any FAR is 1 for a perfectly separable set") {
    val (tar, threshold) = Metrics.tarAtFar(separable, 0.0)
    assertEqualsDouble(tar, 1.0, 1e-6)
    assert(threshold > 0.3f && threshold <= 0.7f, s"threshold should separate: $threshold")
  }

  test("TAR is capped by the FAR budget") {
    // One impostor scores above two of the three genuine pairs. At FAR = 0 the
    // threshold must exclude it, so only the 0.9 genuine pair survives.
    val overlapping = Seq(
      ScoredPair(0.9f, true),
      ScoredPair(0.5f, true),
      ScoredPair(0.4f, true),
      ScoredPair(0.6f, false),
      ScoredPair(0.2f, false),
      ScoredPair(0.1f, false)
    )
    val (tar, _) = Metrics.tarAtFar(overlapping, 0.0)
    assertEqualsDouble(tar, 1.0 / 3.0, 1e-6)
  }

  test("equal error rate is 0 for a perfectly separable set") {
    val (eer, _) = Metrics.equalErrorRate(separable)
    assertEqualsDouble(eer, 0.0, 1e-6)
  }

  test("equal error rate is about 0.5 for random scores") {
    val alternating = (0 until 100).map(i => ScoredPair(i / 100.0f, i % 2 == 0))
    val (eer, _)    = Metrics.equalErrorRate(alternating)
    assert(eer > 0.4 && eer < 0.6, s"expected an EER near 0.5, got $eer")
  }

  test("metrics reject an input with only one class") {
    val genuineOnly = Seq(ScoredPair(0.9f, true), ScoredPair(0.8f, true))
    intercept[IllegalArgumentException](Metrics.roc(genuineOnly))
  }

  test("metrics reject an empty input") {
    intercept[IllegalArgumentException](Metrics.roc(Seq.empty))
  }

  // --- Tie-handling tests ---

  test("tied scores across classes are treated together in ROC") {
    // Two pairs with identical score 0.5: one genuine, one impostor.
    // At threshold 0.5, both should be accepted together.
    val tied = Seq(
      ScoredPair(0.9f, true),
      ScoredPair(0.5f, true),
      ScoredPair(0.5f, false),  // tie with genuine pair
      ScoredPair(0.1f, false)
    )
    val roc = Metrics.roc(tied)
    // At threshold 0.5, both the genuine and impostor with score 0.5 should be accepted
    val pointAt0_5 = roc.find(_.threshold == 0.5f).get
    assertEqualsDouble(pointAt0_5.trueAcceptRate, 1.0, 1e-6)    // both genuine pairs accepted
    assertEqualsDouble(pointAt0_5.falseAcceptRate, 0.5, 1e-6)   // one of two impostors accepted
  }

  test("a genuine and an impostor sharing a score are never separated") {
    // The pessimistic tie convention: when the only genuine pair and the only impostor
    // pair share a score, no threshold may accept one without the other. The sweep must
    // therefore offer exactly two operating points, reject both, or accept both, and
    // in particular must NOT offer the flattering TAR=1 / FAR=0 point that splitting the
    // tie by class would invent.
    val tightTie = Seq(
      ScoredPair(0.5f, true),
      ScoredPair(0.5f, false)
    )
    val roc = Metrics.roc(tightTie)

    assertEquals(roc.size, 2, s"expected exactly 2 operating points, got ${roc.map(_.threshold)}")

    val rejectBoth = roc.find(p => p.trueAcceptRate == 0.0 && p.falseAcceptRate == 0.0)
    val acceptBoth = roc.find(p => p.trueAcceptRate == 1.0 && p.falseAcceptRate == 1.0)
    assert(rejectBoth.isDefined, s"missing the reject-both point: $roc")
    assert(acceptBoth.isDefined, s"missing the accept-both point: $roc")

    assert(
      !roc.exists(p => p.trueAcceptRate == 1.0 && p.falseAcceptRate == 0.0),
      s"tie was split by class, inventing a perfect operating point: $roc"
    )
  }

  // --- Threshold direction tests ---

  test("higher scores should increase TAR at same FAR") {
    // All-genuine scores shifted up should have same TAR but higher threshold
    val base = Seq(
      ScoredPair(0.6f, true),
      ScoredPair(0.5f, true),
      ScoredPair(0.3f, false),
      ScoredPair(0.2f, false)
    )
    val shifted = base.map(p => if p.sameIdentity then p.copy(score = p.score + 0.2f) else p)

    val (baseTar, baseThresh) = Metrics.tarAtFar(base, 0.5)
    val (shiftedTar, shiftedThresh) = Metrics.tarAtFar(shifted, 0.5)

    assertEqualsDouble(baseTar, shiftedTar, 1e-6)
    assert(shiftedThresh > baseThresh, s"shifted threshold should be higher: $shiftedThresh > $baseThresh")
  }

  // --- FAR resolution validation tests ---

  test("tarAtFar rejects FAR finer than impostor count can resolve") {
    // 3 impostor pairs: finest resolvable FAR is 1/3 ≈ 0.333
    val pairs = Seq(
      ScoredPair(0.9f, true),
      ScoredPair(0.8f, true),
      ScoredPair(0.7f, false),
      ScoredPair(0.6f, false),
      ScoredPair(0.5f, false)
    )
    // Request FAR = 0.1, which is finer than 1/3
    val exc = intercept[IllegalArgumentException](Metrics.tarAtFar(pairs, 0.1))
    assert(exc.getMessage.contains("finer than"), s"expected 'finer than' in error: ${exc.getMessage}")
    assert(exc.getMessage.contains("0.1"), s"expected FAR value in error: ${exc.getMessage}")
    assert(exc.getMessage.contains("3"), s"expected impostor count in error: ${exc.getMessage}")
  }

  test("tarAtFar accepts FAR exactly equal to coarsest resolution") {
    // 2 impostor pairs: finest resolvable FAR is 1/2 = 0.5
    val pairs = Seq(
      ScoredPair(0.9f, true),
      ScoredPair(0.8f, true),
      ScoredPair(0.7f, true),
      ScoredPair(0.6f, false),
      ScoredPair(0.5f, false)
    )
    // Request FAR = 0.5, which is exactly 1/2
    val (tar, _) = Metrics.tarAtFar(pairs, 0.5)
    assert(tar >= 0.0 && tar <= 1.0, s"TAR should be in [0,1], got $tar")
  }

  test("tarAtFar accepts FAR = 0") {
    // FAR = 0 is always achievable (no impostors accepted)
    val (tar, _) = Metrics.tarAtFar(separable, 0.0)
    assert(tar >= 0.0 && tar <= 1.0, s"TAR should be in [0,1], got $tar")
  }

  test("tarAtFar accepts FAR >= coarsest resolution") {
    // 2 impostor pairs: finest resolvable FAR is 1/2 = 0.5
    val pairs = Seq(
      ScoredPair(0.9f, true),
      ScoredPair(0.8f, true),
      ScoredPair(0.7f, true),
      ScoredPair(0.6f, false),
      ScoredPair(0.5f, false)
    )
    // Request FAR = 0.6, which is coarser than 1/2
    val (tar, _) = Metrics.tarAtFar(pairs, 0.6)
    assert(tar >= 0.0 && tar <= 1.0, s"TAR should be in [0,1], got $tar")
  }

  // --- Degenerate input tests ---

  test("roc with single genuine and single impostor pair") {
    val minimal = Seq(
      ScoredPair(0.9f, true),
      ScoredPair(0.1f, false)
    )
    val roc = Metrics.roc(minimal)
    assert(roc.nonEmpty, "roc should produce points")
    val auc = Metrics.auc(roc)
    assertEqualsDouble(auc, 1.0, 1e-6)
  }

  test("roc with all identical scores") {
    // All scores are 0.5: can't separate by score alone
    val identical = Seq(
      ScoredPair(0.5f, true),
      ScoredPair(0.5f, true),
      ScoredPair(0.5f, false),
      ScoredPair(0.5f, false)
    )
    val roc = Metrics.roc(identical)
    // Should have one threshold at 0.5 (and Infinity)
    assert(roc.size >= 2, s"expected at least 2 thresholds, got ${roc.size}")
    val auc = Metrics.auc(roc)
    assertEqualsDouble(auc, 0.5, 1e-6)  // random classifier
  }

  test("eer with all identical scores") {
    val identical = Seq(
      ScoredPair(0.5f, true),
      ScoredPair(0.5f, true),
      ScoredPair(0.5f, false),
      ScoredPair(0.5f, false)
    )
    val (eer, _) = Metrics.equalErrorRate(identical)
    assertEqualsDouble(eer, 0.5, 1e-6)
  }

  test("eer threshold is the one closest to crossing") {
    val pairs = Seq(
      ScoredPair(0.9f, true),
      ScoredPair(0.8f, true),
      ScoredPair(0.7f, true),
      ScoredPair(0.4f, false),
      ScoredPair(0.3f, false),
      ScoredPair(0.2f, false)
    )
    val (eer, threshold) = Metrics.equalErrorRate(pairs)
    val roc = Metrics.roc(pairs)
    val atThreshold = roc.find(_.threshold == threshold).get
    // The threshold should minimize |FAR - FRR|
    val minDiff = roc.map(p => math.abs(p.falseAcceptRate - (1.0 - p.trueAcceptRate))).min
    val actualDiff = math.abs(atThreshold.falseAcceptRate - (1.0 - atThreshold.trueAcceptRate))
    assertEqualsDouble(actualDiff, minDiff, 1e-10)
  }
