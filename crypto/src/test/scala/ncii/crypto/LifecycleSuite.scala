package ncii.crypto

import ncii.core.Embedding

class LifecycleSuite extends munit.FunSuite:

  // The "hundred cycles" test generates 100 CKKS key sets with ~47 MB Galois keys each,
  // a CPU-bound workload that slows badly on a loaded machine. The test's value is
  // detecting per-iteration handle leaks, which does not depend on wall-clock time.
  // A generous timeout keeps the assertion meaningful without making it a performance
  // test by accident. Do not reduce the iteration count to make it faster; a hundred
  // cycles is what catches a leak of one handle per iteration.
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  private def unit(seed: Int): Embedding =
    val rng = new scala.util.Random(seed)
    Embedding.normalised(Array.fill(CkksParams.BlockSize)(rng.nextGaussian().toFloat))

  test("a full score cycle leaves no live native handles") {
    val before = NativeLibrary.liveHandleCount

    val ctx = CkksContext.create()
    val keys = KeySet.generate(ctx)
    try
      val ct = EncryptedGallery.encrypt(ctx, keys, Seq(unit(1), unit(2)))
      try
        val scores = Scorer.score(ctx, keys, ct, unit(1))
        scores.close()
      finally ct.close()
    finally
      keys.close()
      ctx.close()

    assertEquals(
      NativeLibrary.liveHandleCount,
      before,
      "native handles leaked across a score cycle"
    )
  }

  test("closing twice is safe") {
    // Create two contexts so we have something to verify didn't get freed.
    val before = NativeLibrary.liveHandleCount
    val ctx1 = CkksContext.create()
    val ctx2 = CkksContext.create()
    val afterCreation = NativeLibrary.liveHandleCount

    // Close ctx1 twice.
    ctx1.close()
    val afterFirstClose = NativeLibrary.liveHandleCount
    ctx1.close() // Second close must not throw and must not free anything else.

    // Verify the count dropped by exactly one (not two): the first close freed ctx1,
    // the second close hit the idempotency guard and did nothing.
    assertEquals(
      afterFirstClose,
      afterCreation - 1,
      "first close should have freed exactly one handle"
    )
    assertEquals(
      NativeLibrary.liveHandleCount,
      afterFirstClose,
      "second close must not free any additional handle"
    )

    // Verify ctx2 is still usable: a freed handle would throw or return garbage here.
    val slotCount = ctx2.slotCount
    assert(slotCount > 0, "second context must still be usable after closing the first twice")

    // Clean up and verify we're back to baseline.
    ctx2.close()
    assertEquals(
      NativeLibrary.liveHandleCount,
      before,
      "both contexts must be freed, returning to baseline"
    )
  }

  test("a hundred cycles do not accumulate handles") {
    // A single-cycle check can pass while a leak of one handle per iteration hides in
    // the noise. This is the version that catches it.
    val before = NativeLibrary.liveHandleCount
    (1 to 100).foreach { i =>
      val ctx = CkksContext.create()
      val keys = KeySet.generate(ctx)
      val ct = EncryptedGallery.encrypt(ctx, keys, Seq(unit(i)))
      val scores = Scorer.score(ctx, keys, ct, unit(i))
      scores.close(); ct.close(); keys.close(); ctx.close()
    }
    assertEquals(NativeLibrary.liveHandleCount, before, "handles accumulated over 100 cycles")
  }
