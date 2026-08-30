package ncii.crypto

class CkksContextSuite extends munit.FunSuite:

  // munit's 30-second default is not sized for this project's integration suites. They
  // load a 166 MB ONNX model, generate CKKS key sets at ~47 MB of Galois keys each, decode
  // video, or start a Postgres container, legitimately slow work that competes with
  // whatever else the machine is running. Three separate suites timed out at 31-197s while
  // asserting nothing wrong, so the limit is set to the work rather than raised one failure
  // at a time.
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  test("the fixed parameter set matches the design") {
    // These are spec-level constants, not tunables: the slot geometry below depends on
    // them, and changing one silently changes ciphertext layout.
    assertEquals(CkksParams.LogN, 14)
    assertEquals(CkksParams.RingDegree, 16384)
    assertEquals(CkksParams.Slots, 8192)
    assertEquals(CkksParams.LogScale, 40)
    assertEquals(CkksParams.BlockSize, 512)
    assertEquals(CkksParams.BlocksPerCiphertext, 16)
    assertEquals(CkksParams.RotationsPerBlock, 9)
  }

  test("slot geometry is internally consistent") {
    assertEquals(CkksParams.RingDegree, 1 << CkksParams.LogN)
    assertEquals(CkksParams.Slots, CkksParams.RingDegree / 2)
    assertEquals(CkksParams.BlockSize * CkksParams.BlocksPerCiphertext, CkksParams.Slots)
    // Summing 512 values by rotate-and-double takes log2(512) = 9 rotations.
    assertEquals(1 << CkksParams.RotationsPerBlock, CkksParams.BlockSize)
  }

  test("a context reports the slot count the parameters imply") {
    val ctx = CkksContext.create()
    try assertEquals(ctx.slotCount, CkksParams.Slots)
    finally ctx.close()
  }

  test("using a context after closing it fails loudly") {
    val ctx = CkksContext.create()
    ctx.close()
    intercept[NativeException](ctx.slotCount)
  }
