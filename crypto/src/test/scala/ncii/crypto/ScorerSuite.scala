package ncii.crypto

import ncii.core.Embedding

class ScorerSuite extends munit.FunSuite:

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

  test("encrypted scores match plaintext cosine") {
    // The correctness spine of the module. Random unit vectors give cosines spread
    // around zero, and one deliberate self-match gives a value of 1.0, so a rotation
    // that gathers the wrong slots cannot pass by landing near the others.
    val ctx = CkksContext.create()
    val keys = KeySet.generate(ctx)
    try
      val gallery = Seq(unit(1), unit(2), unit(3), unit(4))
      val query = unit(1)

      val ct = EncryptedGallery.encrypt(ctx, keys, gallery)
      try
        val scores = Scorer.score(ctx, keys, ct, query)
        try
          val got = Scorer.decryptScores(ctx, keys, scores, gallery.size)
          val expected = gallery.map(_.cosine(query).toDouble).toArray

          assertEquals(got.length, gallery.size)
          val errors = expected.zip(got).map((a, b) => math.abs(a - b))
          val maxErr = errors.max
          val meanErr = errors.sum / errors.length

          // Spec target: mean absolute error around 1e-5 at scale 2^40.
          assert(maxErr < 1e-3, s"max error $maxErr, per-score: ${errors.mkString(", ")}")
          assert(meanErr < 1e-4, s"mean error $meanErr exceeds the 1e-4 bound")

          // The self-match must be 1.0, which pins the rotation schedule: a wrong
          // rotation would still produce plausible-looking small numbers here.
          assertEqualsDouble(got(0), 1.0, 1e-3)
        finally scores.close()
      finally ct.close()
    finally
      keys.close()
      ctx.close()
  }

  test("the server scores without holding a secret key") {
    // The end-to-end privacy claim: scoring is performed by a key set that cannot
    // decrypt, and only the client's key set can read the result. The score must be
    // correct (self-match = 1.0), proving the server produced meaningful results
    // despite not holding the secret key.
    val ctx = CkksContext.create()
    val client = KeySet.generate(ctx)
    try
      val server = KeySet.serverSide(ctx, client.publicKeyBytes, client.galoisKeyBytes)
      val gallery = Seq(unit(11), unit(12))
      val query = unit(11)

      val ct = EncryptedGallery.encrypt(ctx, client, gallery)
      try
        val scores = Scorer.score(ctx, server, ct, query)
        try
          // Server cannot decrypt, even though it performed the scoring
          intercept[NativeException](Scorer.decryptScores(ctx, server, scores, gallery.size))
          // Only client can decrypt, and the score must be correct
          val got = Scorer.decryptScores(ctx, client, scores, gallery.size)
          assertEqualsDouble(got(0), 1.0, 1e-3, "server-scored result must match self-match (privacy + correctness)")
        finally scores.close()
      finally
        ct.close()
        server.close()
    finally
      client.close()
      ctx.close()
  }

  test("an orthogonal query scores near zero") {
    // Guards against a rotation schedule that accidentally sums the whole ciphertext:
    // that would make every score the same, and this test would fail.
    val ctx = CkksContext.create()
    val keys = KeySet.generate(ctx)
    try
      val a = Array.fill(CkksParams.BlockSize)(0.0f)
      a(0) = 1.0f
      val b = Array.fill(CkksParams.BlockSize)(0.0f)
      b(1) = 1.0f

      val gallery = Seq(Embedding.normalised(a))
      val query = Embedding.normalised(b)

      val ct = EncryptedGallery.encrypt(ctx, keys, gallery)
      try
        val scores = Scorer.score(ctx, keys, ct, query)
        try assertEqualsDouble(Scorer.decryptScores(ctx, keys, scores, 1)(0), 0.0, 1e-3)
        finally scores.close()
      finally ct.close()
    finally
      keys.close()
      ctx.close()
  }
