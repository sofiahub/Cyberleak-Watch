package ncii.core

class EmbeddingSuite extends munit.FunSuite:

  test("normalised produces a unit vector") {
    val e = Embedding.normalised(Array(3.0f, 4.0f))
    assertEqualsFloat(e.values(0), 0.6f, 1e-6f)
    assertEqualsFloat(e.values(1), 0.8f, 1e-6f)
  }

  test("cosine of a vector with itself is 1") {
    val e = Embedding.normalised(Array(1.0f, 2.0f, 3.0f))
    assertEqualsFloat(e.cosine(e), 1.0f, 1e-6f)
  }

  test("cosine of orthogonal vectors is 0") {
    val a = Embedding.normalised(Array(1.0f, 0.0f))
    val b = Embedding.normalised(Array(0.0f, 1.0f))
    assertEqualsFloat(a.cosine(b), 0.0f, 1e-6f)
  }

  test("cosine is scale invariant") {
    val a = Embedding.normalised(Array(1.0f, 2.0f))
    val b = Embedding.normalised(Array(10.0f, 20.0f))
    assertEqualsFloat(a.cosine(b), 1.0f, 1e-6f)
  }

  test("cosine rejects a dimension mismatch") {
    val a = Embedding.normalised(Array(1.0f, 0.0f))
    val b = Embedding.normalised(Array(1.0f, 0.0f, 0.0f))
    intercept[IllegalArgumentException](a.cosine(b))
  }

  test("normalised rejects a zero vector") {
    intercept[IllegalArgumentException](Embedding.normalised(Array(0.0f, 0.0f)))
  }

  test("mean of identical embeddings is that embedding") {
    val e = Embedding.normalised(Array(1.0f, 2.0f, 2.0f))
    val m = Embedding.mean(Seq(e, e, e))
    // Element-wise, not by cosine: cosine only establishes direction, so it would hold
    // even if mean returned a differently-scaled vector. The claim here is equality.
    assertEquals(m.dimension, e.dimension)
    (0 until e.dimension).foreach { i =>
      assertEqualsFloat(m.values(i), e.values(i), 1e-6f)
    }
  }

  test("mean lies between its inputs") {
    val a = Embedding.normalised(Array(1.0f, 0.0f))
    val b = Embedding.normalised(Array(0.0f, 1.0f))
    val m = Embedding.mean(Seq(a, b))
    assertEqualsFloat(m.cosine(a), m.cosine(b), 1e-6f)
    assert(m.cosine(a) > 0.7f, s"expected ~0.707, got ${m.cosine(a)}")
  }

  test("mean rejects an empty sequence") {
    intercept[IllegalArgumentException](Embedding.mean(Seq.empty))
  }
