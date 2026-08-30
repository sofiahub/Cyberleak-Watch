package ncii.eval

import ncii.core.Embedding
import java.nio.file.{Files, Path}

class DeepfakeProtocolSuite extends munit.FunSuite:

  private def makeCorpus(root: Path): Unit =
    val alice = root.resolve("alice")
    Files.createDirectories(alice.resolve("gallery"))
    Files.createDirectories(alice.resolve("fake"))
    Files.createFile(alice.resolve("gallery").resolve("a1.jpg"))
    Files.createFile(alice.resolve("gallery").resolve("a2.jpg"))
    Files.createFile(alice.resolve("fake").resolve("f1.mp4"))

    val bob = root.resolve("bob")
    Files.createDirectories(bob.resolve("gallery"))
    Files.createDirectories(bob.resolve("fake"))
    Files.createFile(bob.resolve("gallery").resolve("b1.jpg"))
    Files.createFile(bob.resolve("fake").resolve("f1.mp4"))
    Files.createFile(bob.resolve("fake").resolve("f2.mp4"))

  test("discover finds one case per identity directory") {
    val root = Files.createTempDirectory("ncii-deepfake")
    try
      makeCorpus(root)
      val cases = DeepfakeProtocol.discover(root).sortBy(_.name)
      assertEquals(cases.map(_.name), Seq("alice", "bob"))
      assertEquals(cases.head.galleryImages.size, 2)
      assertEquals(cases.head.manipulatedVideos.size, 1)
      assertEquals(cases(1).manipulatedVideos.size, 2)
    finally deleteRecursively(root)
  }

  test("discover skips an identity with no gallery") {
    val root = Files.createTempDirectory("ncii-deepfake")
    try
      val orphan = root.resolve("carol")
      Files.createDirectories(orphan.resolve("fake"))
      Files.createFile(orphan.resolve("fake").resolve("f1.mp4"))
      assertEquals(DeepfakeProtocol.discover(root), Seq.empty)
    finally deleteRecursively(root)
  }

  test("discover on a missing root fails loudly") {
    intercept[IllegalArgumentException](
      DeepfakeProtocol.discover(java.nio.file.Paths.get("data/no-such-corpus"))
    )
  }

  test("report states match rate, rank-1 rate, and threshold used") {
    val results = Seq(
      // alice: 0.55 vs own, 0.30 vs other (bob) -> rank-1 correct, matched
      DeepfakeResult("alice", java.nio.file.Paths.get("a.mp4"), ownIdentityScore = 0.55f,
        bestOtherIdentityScore = 0.30f, bestOtherIdentity = "bob", rank1Correct = true, ownRank = 1, candidateCount = 2, matched = true),
      // alice: 0.21 vs own, 0.40 vs other (bob) -> rank-1 wrong, not matched
      DeepfakeResult("alice", java.nio.file.Paths.get("b.mp4"), ownIdentityScore = 0.21f,
        bestOtherIdentityScore = 0.40f, bestOtherIdentity = "bob", rank1Correct = false, ownRank = 2, candidateCount = 2, matched = false),
      // bob: 0.61 vs own, 0.25 vs other (alice) -> rank-1 correct, matched
      DeepfakeResult("bob", java.nio.file.Paths.get("c.mp4"), ownIdentityScore = 0.61f,
        bestOtherIdentityScore = 0.25f, bestOtherIdentity = "alice", rank1Correct = true, ownRank = 1, candidateCount = 2, matched = true),
      // bob: 0.48 vs own, 0.45 vs other (alice) -> rank-1 correct, matched
      DeepfakeResult("bob", java.nio.file.Paths.get("d.mp4"), ownIdentityScore = 0.48f,
        bestOtherIdentityScore = 0.45f, bestOtherIdentity = "alice", rank1Correct = true, ownRank = 1, candidateCount = 2, matched = true)
    )
    val text = DeepfakeProtocol.report(results, threshold = 0.35f)
    assert(text.contains("0.7500"), s"expected a 75% match rate in:\n$text")
    assert(text.contains("0.7500"), s"expected a 75% rank-1 rate in:\n$text")
    assert(text.contains("0.3500"), s"expected the threshold in:\n$text")
    assert(text.contains("alice"), s"expected a per-identity breakdown in:\n$text")
  }

  test("scoreTracks finds the best match between tracks and gallery") {
    // Create simple test embeddings using Embedding.normalised
    // Track 1: [0.5, 0.5, 0.707]
    val track1 = Embedding.normalised(Array(0.5f, 0.5f, 0.707f))

    // Gallery 1: [1.0, 0.0, 0.0]
    val gallery1 = Embedding.normalised(Array(1.0f, 0.0f, 0.0f))

    // Gallery 2: [0.0, 1.0, 0.0]
    val gallery2 = Embedding.normalised(Array(0.0f, 1.0f, 0.0f))

    val tracks = Seq(track1)
    val gallery = Seq(gallery1, gallery2)

    val score = DeepfakeProtocol.scoreTracks(tracks, gallery)
    assert(score.isDefined, "scoreTracks should return a score")
    assert(score.get > 0.0f, "score should be positive for non-orthogonal vectors")
    assert(score.get <= 1.0f, "cosine similarity should be <= 1.0")
  }

  test("scoreTracks returns None for empty tracks") {
    val gallery1 = Embedding.normalised(Array(1.0f, 0.0f, 0.0f))
    val score = DeepfakeProtocol.scoreTracks(Seq.empty, Seq(gallery1))
    assertEquals(score, None)
  }

  private def deleteRecursively(path: Path): Unit =
    if Files.isDirectory(path) then
      val entries = Files.list(path)
      try entries.forEach(deleteRecursively)
      finally entries.close()
    Files.deleteIfExists(path)

  // --- Scoring modes (E1) ---------------------------------------------------------------

  private def probe(id: String, scores: (String, Float)*): ProbeScores =
    ProbeScores(id, java.nio.file.Paths.get(s"/tmp/$id.mp4"), scores.toSeq)

  test("ScoringMode.parse accepts the documented spellings and rejects others") {
    assertEquals(ScoringMode.parse("raw"), ScoringMode.Raw)
    assertEquals(ScoringMode.parse("probe-z"), ScoringMode.ProbeZ)
    assertEquals(ScoringMode.parse("gallery-z"), ScoringMode.GalleryZ)
    intercept[IllegalArgumentException](ScoringMode.parse("z"))
  }

  test("probe-z cannot change ranking - it is monotonic, and is kept as a control") {
    // Subtracting a probe's own mean and dividing by its own standard deviation are
    // constants FOR THAT PROBE, so the ordering of its gallery scores is untouched.
    // Asserted rather than assumed: if this ever fails, the ranking code has a bug rather
    // than an improvement. GalleryZ is the variant that can genuinely reorder.
    val probes = Seq(
      probe("B", "A" -> 0.60f, "B" -> 0.55f),
      probe("B", "A" -> 0.61f, "B" -> 0.50f),
      probe("C", "A" -> 0.20f, "C" -> 0.90f)
    )
    val raw = DeepfakeProtocol.rank(probes, 0.25f, ScoringMode.Raw)
    val pz  = DeepfakeProtocol.rank(probes, 0.25f, ScoringMode.ProbeZ)
    assertEquals(pz.map(_.rank1Correct), raw.map(_.rank1Correct))
  }

  test("gallery-z demotes a hub identity that scores high against everything") {
    // Gallery A scores about 0.60 against every probe with almost no spread; gallery B
    // ranges widely. On raw cosine A wins the first probe (0.60 > 0.55). Normalised per
    // gallery, A's 0.60 is merely typical for A while B's 0.55 sits well above B's own
    // mean, so the true identity wins.
    val probes = Seq(
      probe("B", "A" -> 0.60f, "B" -> 0.55f),
      probe("B", "A" -> 0.61f, "B" -> 0.50f),
      probe("B", "A" -> 0.59f, "B" -> 0.45f)
    )

    val raw = DeepfakeProtocol.rank(probes, 0.25f, ScoringMode.Raw)
    assert(!raw.head.rank1Correct, "expected the hub to win on raw cosine")

    val gz = DeepfakeProtocol.rank(probes, 0.25f, ScoringMode.GalleryZ)
    assert(gz.head.rank1Correct, s"gallery-z should have demoted the hub: $gz")
  }

  test("the match decision ignores the scoring mode entirely") {
    // The guardrail. Normalisation may reorder identities but must never change whether a
    // video is detected, because matched is decided on the raw own-identity cosine.
    val probes = Seq(
      probe("B", "A" -> 0.60f, "B" -> 0.55f),
      probe("B", "A" -> 0.61f, "B" -> 0.50f),
      probe("B", "A" -> 0.59f, "B" -> 0.20f)
    )
    val modes = Seq(ScoringMode.Raw, ScoringMode.ProbeZ, ScoringMode.GalleryZ)
    val decisions = modes.map(m => DeepfakeProtocol.rank(probes, 0.25f, m).map(_.matched))
    assertEquals(decisions.distinct.size, 1, s"match decisions varied by mode: $decisions")
    assertEquals(decisions.head, Seq(true, true, false))
  }

  test("a zero-variance gallery is left unnormalised rather than dividing by zero") {
    val probes = Seq(
      probe("B", "A" -> 0.60f, "B" -> 0.55f),
      probe("B", "A" -> 0.60f, "B" -> 0.50f)
    )
    val gz = DeepfakeProtocol.rank(probes, 0.25f, ScoringMode.GalleryZ)
    assert(gz.forall(r => r.ownIdentityScore.isFinite), "scores must stay finite")
    assertEquals(gz.size, 2)
  }

  test("cumulative match recovers a true identity displaced to second place") {
    // The shape target leakage predicts: the true identity is not lost, it is displaced by
    // exactly one competitor. Two probes rank it second, one ranks it first, so top-1 is
    // 1/3 and top-2 is 3/3.
    val results = Seq(
      DeepfakeResult("a", java.nio.file.Paths.get("1.mp4"), 0.50f, 0.60f, "t", false, 2, 5, true),
      DeepfakeResult("b", java.nio.file.Paths.get("2.mp4"), 0.52f, 0.61f, "t", false, 2, 5, true),
      DeepfakeResult("c", java.nio.file.Paths.get("3.mp4"), 0.70f, 0.30f, "t", true, 1, 5, true)
    )
    val text = DeepfakeProtocol.cumulativeMatch(results, Seq(1, 2))
    assert(text.contains("0.3333"), s"expected top-1 of 1/3 in:\n$text")
    assert(text.contains("1.0000"), s"expected top-2 of 3/3 in:\n$text")
    assert(text.contains("of rank-1 misses, share sitting at rank 2 : 1.0000"),
      s"both misses sat at rank 2, so recovery should be 1.0:\n$text")
  }

  test("cumulative match is monotonic in k") {
    val results = Seq(
      DeepfakeResult("a", java.nio.file.Paths.get("1.mp4"), 0.5f, 0.6f, "t", false, 4, 10, true),
      DeepfakeResult("b", java.nio.file.Paths.get("2.mp4"), 0.5f, 0.6f, "t", false, 2, 10, true),
      DeepfakeResult("c", java.nio.file.Paths.get("3.mp4"), 0.7f, 0.3f, "t", true, 1, 10, true)
    )
    val text = DeepfakeProtocol.cumulativeMatch(results, Seq(1, 2, 3, 5))
    val rates = """top-\d+\s+:\s+([\d.]+)""".r.findAllMatchIn(text).map(_.group(1).toDouble).toSeq
    assertEquals(rates.size, 4)
    assert(rates.sliding(2).forall(w => w(1) >= w(0)), s"recall must not decrease with k: $rates")
  }
