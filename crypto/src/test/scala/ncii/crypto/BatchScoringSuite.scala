package ncii.crypto

import ncii.core.Embedding

class BatchScoringSuite extends munit.FunSuite:

  // munit's 30-second default is not sized for this project's integration suites. They
  // load a 166 MB ONNX model, generate CKKS key sets at ~47 MB of Galois keys each, decode
  // video, or start a Postgres container, legitimately slow work that competes with
  // whatever else the machine is running. Three separate suites timed out at 31-197s while
  // asserting nothing wrong, so the limit is set to the work rather than raised one failure
  // at a time.
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  private def unit(seed: Int): Embedding =
    val rng = new scala.util.Random(seed)
    Embedding.normalised(Array.fill(CkksParams.BlockSize)(rng.nextGaussian().toFloat))

  test("batched scoring yields sixteen distinct scores, with self-match = 1.0") {
    // The acceptance criterion of the batching mode: replicate one enrolled vector
    // across all blocks and score sixteen distinct queries. One multiply yields all
    // sixteen scores. The query at index 5 is identical to the enrolled vector,
    // so its score must be 1.0, this pins the packing layout.
    val ctx = CkksContext.create()
    val keys = KeySet.generate(ctx)
    try
      // Generate sixteen distinct queries, with index 5 being the enrolled vector
      val queries = (0 until CkksParams.BlocksPerCiphertext).map { i =>
        unit(1000 + i)
      }.toSeq
      val enrolled = queries(5)

      // Encrypt the enrolled vector replicated into all blocks
      val ct = EncryptedGallery.encryptReplicated(ctx, keys, enrolled)
      try
        // Score all queries in a single multiply
        val scores = Scorer.scoreBatch(ctx, keys, ct, queries)
        try
          val got = Scorer.decryptScores(ctx, keys, scores, CkksParams.BlocksPerCiphertext)
          val expected = queries.map(_.cosine(enrolled).toDouble).toArray

          assertEquals(got.length, CkksParams.BlocksPerCiphertext)

          // The self-match at index 5 must be exactly 1.0 (within tolerance)
          assertEqualsDouble(got(5), 1.0, 1e-3, "self-match must score 1.0")

          // Calculate errors and verify bounds
          val errors = expected.zip(got).map((a, b) => math.abs(a - b))
          val maxErr = errors.max
          val meanErr = errors.sum / errors.length

          assert(maxErr < 1e-3, s"max error $maxErr exceeds 1e-3, per-score: ${errors.mkString(", ")}")
          assert(meanErr < 1e-4, s"mean error $meanErr exceeds 1e-4")

          // Log measurements for the report
          println(s"[BatchScoringSuite] self-match score: ${got(5)}")
          println(s"[BatchScoringSuite] max error: $maxErr")
          println(s"[BatchScoringSuite] mean error: $meanErr")
        finally scores.close()
      finally ct.close()
    finally
      keys.close()
      ctx.close()
  }
