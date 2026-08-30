package ncii.crypto

import java.lang.foreign.{Arena, MemorySegment, ValueLayout}

class KeySetSuite extends munit.FunSuite:

  // munit's 30-second default is not sized for this project's integration suites. They
  // load a 166 MB ONNX model, generate CKKS key sets at ~47 MB of Galois keys each, decode
  // video, or start a Postgres container, legitimately slow work that competes with
  // whatever else the machine is running. Three separate suites timed out at 31-197s while
  // asserting nothing wrong, so the limit is set to the work rather than raised one failure
  // at a time.
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  test("generated keys serialise to non-trivial byte arrays") {
    val ctx = CkksContext.create()
    try
      val keys = KeySet.generate(ctx)
      try
        // A Galois key set for nine rotations is large; a few bytes would mean the
        // marshalling silently produced an empty structure.
        assert(keys.publicKeyBytes.length > 1024, s"public key was ${keys.publicKeyBytes.length} bytes")
        assert(keys.galoisKeyBytes.length > 1024, s"galois keys were ${keys.galoisKeyBytes.length} bytes")
      finally keys.close()
    finally ctx.close()
  }

  test("a server-side key set carries no secret key") {
    // The security property this whole module exists for: server-side code must be
    // structurally unable to decrypt.
    val ctx = CkksContext.create()
    try
      val client = KeySet.generate(ctx)
      try
        val server = KeySet.serverSide(ctx, client.publicKeyBytes, client.galoisKeyBytes)
        try assert(!server.canDecrypt, "a server-side key set must not hold a secret key")
        finally server.close()
      finally client.close()
    finally ctx.close()
  }

  test("a client key set can decrypt") {
    val ctx = CkksContext.create()
    try
      val keys = KeySet.generate(ctx)
      try assert(keys.canDecrypt, "a generated key set holds the secret key")
      finally keys.close()
    finally ctx.close()
  }

  test("negative status from native functions propagates from readBytes") {
    // Negative returns from native functions are error codes, not data. This test verifies
    // that readBytes properly propagates them as NativeException rather than silently
    // converting them to empty arrays.
    val ctx = CkksContext.create()
    try
      val keys = KeySet.generate(ctx)
      // Close the key set to free the native handle. The next access will get a negative
      // status code from the native side (unknown handle), which readBytes must propagate.
      keys.close()

      // Accessing a closed key set should throw NativeException from readBytes.
      val thrown = intercept[NativeException] {
        keys.publicKeyBytes
      }
      // Verify the exception message contains both the native error and the key name.
      assert(thrown.getMessage.contains("unknown"), s"should mention unknown handle: ${thrown.getMessage}")
      assert(thrown.getMessage.contains("publicKeyBytes"), s"should name the key being fetched: ${thrown.getMessage}")
    finally ctx.close()
  }
