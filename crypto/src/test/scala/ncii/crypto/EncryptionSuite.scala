package ncii.crypto

import ncii.core.Embedding

class EncryptionSuite extends munit.FunSuite:

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

  test("an encrypted gallery decrypts back to its slot values") {
    val ctx = CkksContext.create()
    val keys = KeySet.generate(ctx)
    try
      val gallery = Seq(unit(1), unit(2), unit(3))
      val expected = SlotPacking.packGallery(gallery)

      val ct = EncryptedGallery.encrypt(ctx, keys, gallery)
      try
        val got = Decryptor.decryptSlots(ctx, keys, ct)
        assertEquals(got.length, CkksParams.Slots)
        // CKKS is approximate. At scale 2^40 the error should sit around 1e-5, so 1e-4
        // is a generous bound that still fails loudly on a real packing error.
        val maxErr = expected.zip(got).map((a, b) => math.abs(a - b)).max
        println(f"[EncryptionSuite] max slot error: $maxErr%.2e")
        assert(maxErr < 1e-4, s"max slot error $maxErr exceeds 1e-4")
      finally ct.close()
    finally
      keys.close()
      ctx.close()
  }

  test("a server-side key set cannot decrypt") {
    val ctx = CkksContext.create()
    val client = KeySet.generate(ctx)
    try
      val server = KeySet.serverSide(ctx, client.publicKeyBytes, client.galoisKeyBytes)
      val ct = EncryptedGallery.encrypt(ctx, server, Seq(unit(4)))
      try
        // The server can encrypt but must not be able to read. This is the property the
        // whole scheme rests on, enforced natively rather than by Scala convention.
        intercept[NativeException](Decryptor.decryptSlots(ctx, server, ct))
      finally
        ct.close()
        server.close()
    finally
      client.close()
      ctx.close()
  }

  test("a ciphertext survives a serialisation round trip") {
    val ctx = CkksContext.create()
    val keys = KeySet.generate(ctx)
    try
      val gallery = Seq(unit(5))
      val original = EncryptedGallery.encrypt(ctx, keys, gallery)
      val bytes = original.toBytes
      original.close()

      println(f"[EncryptionSuite] ciphertext serialised to ${bytes.length} bytes")
      assert(bytes.length > 1024, s"ciphertext serialised to only ${bytes.length} bytes")

      val restored = EncryptedGallery.fromBytes(ctx, bytes, gallery.size)
      try
        val got = Decryptor.decryptSlots(ctx, keys, restored)
        val expected = SlotPacking.packGallery(gallery)
        val maxErr = expected.zip(got).map((a, b) => math.abs(a - b)).max
        assert(maxErr < 1e-4, s"max slot error after round trip $maxErr")
      finally restored.close()
    finally
      keys.close()
      ctx.close()
  }
