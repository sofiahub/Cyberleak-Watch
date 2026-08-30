package ncii.crypto

class NativeLibrarySuite extends munit.FunSuite:

  test("the native library reports its version and pinned Lattigo major") {
    val v = NativeLibrary.version
    assert(v.startsWith("ncii-ckks/"), s"unexpected version string: '$v'")
    assert(v.contains("lattigo/v6"), s"expected a pinned Lattigo v6 build, got '$v'")
  }

  test("freeing an unknown handle fails rather than succeeding silently") {
    // A handle registry that accepts any handle would mask use-after-free in the
    // Scala layer, so the failure must propagate.
    val thrown = intercept[NativeException](NativeLibrary.freeHandle(999999L))
    assert(thrown.getMessage.contains("unknown handle"), thrown.getMessage)
  }
