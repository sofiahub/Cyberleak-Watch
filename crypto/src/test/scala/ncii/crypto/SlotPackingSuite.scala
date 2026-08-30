package ncii.crypto

import ncii.core.Embedding

class SlotPackingSuite extends munit.FunSuite:

  private def unit(seed: Int): Embedding =
    val rng = new scala.util.Random(seed)
    Embedding.normalised(Array.fill(CkksParams.BlockSize)(rng.nextGaussian().toFloat))

  test("a gallery packs one embedding per 512-slot block") {
    val gallery = Seq(unit(1), unit(2), unit(3))
    val slots = SlotPacking.packGallery(gallery)

    assertEquals(slots.length, CkksParams.Slots)
    // Block i holds embedding i.
    gallery.zipWithIndex.foreach { (e, i) =>
      val offset = i * CkksParams.BlockSize
      (0 until CkksParams.BlockSize).foreach { j =>
        assertEqualsDouble(slots(offset + j), e.values(j).toDouble, 1e-9)
      }
    }
    // Unused blocks are zeroed, so they contribute nothing to any dot product.
    val used = gallery.size * CkksParams.BlockSize
    assert(slots.drop(used).forall(_ == 0.0), "unused slots must be zero")
  }

  test("packing rejects a gallery larger than the ciphertext holds") {
    val tooMany = Seq.fill(CkksParams.BlocksPerCiphertext + 1)(unit(7))
    intercept[IllegalArgumentException](SlotPacking.packGallery(tooMany))
  }

  test("packing rejects an embedding of the wrong dimension") {
    val wrong = Embedding.normalised(Array.fill(128)(1.0f))
    intercept[IllegalArgumentException](SlotPacking.packGallery(Seq(wrong)))
  }

  test("a query is replicated identically into every block") {
    val q = unit(42)
    val slots = SlotPacking.replicateQuery(q)

    assertEquals(slots.length, CkksParams.Slots)
    (0 until CkksParams.BlocksPerCiphertext).foreach { b =>
      val offset = b * CkksParams.BlockSize
      (0 until CkksParams.BlockSize).foreach { j =>
        assertEqualsDouble(slots(offset + j), q.values(j).toDouble, 1e-9)
      }
    }
  }

  test("block sums are read from each block's first slot") {
    // After rotate-and-sum every slot in a block holds that block's sum; the first is
    // the one we read. Simulate that layout rather than the intermediate state.
    val slots = Array.fill(CkksParams.Slots)(0.0)
    (0 until CkksParams.BlocksPerCiphertext).foreach { b =>
      val v = 0.1 * (b + 1)
      (0 until CkksParams.BlockSize).foreach(j => slots(b * CkksParams.BlockSize + j) = v)
    }

    val sums = SlotPacking.extractBlockSums(slots, 3)
    assertEquals(sums.length, 3)
    assertEqualsDouble(sums(0), 0.1, 1e-12)
    assertEqualsDouble(sums(1), 0.2, 1e-12)
    assertEqualsDouble(sums(2), 0.3, 1e-12)
  }

  test("a packed gallery dotted with a replicated query gives cosine per block") {
    // The whole scheme in plaintext: if this identity does not hold here, no amount of
    // encryption will make it hold later.
    val gallery = Seq(unit(1), unit(2))
    val query = unit(1) // deliberately equal to gallery(0)

    val g = SlotPacking.packGallery(gallery)
    val q = SlotPacking.replicateQuery(query)
    val products = g.zip(q).map((a, b) => a * b)

    val blockSum = (i: Int) =>
      (0 until CkksParams.BlockSize).map(j => products(i * CkksParams.BlockSize + j)).sum

    assertEqualsDouble(blockSum(0), gallery(0).cosine(query).toDouble, 1e-6)
    assertEqualsDouble(blockSum(1), gallery(1).cosine(query).toDouble, 1e-6)
    assertEqualsDouble(blockSum(0), 1.0, 1e-6) // identical vectors
  }

  test("a gallery vector is replicated identically into every block") {
    val g = unit(99)
    val slots = SlotPacking.replicateGalleryVector(g)

    assertEquals(slots.length, CkksParams.Slots)
    (0 until CkksParams.BlocksPerCiphertext).foreach { b =>
      val offset = b * CkksParams.BlockSize
      (0 until CkksParams.BlockSize).foreach { j =>
        assertEqualsDouble(slots(offset + j), g.values(j).toDouble, 1e-9)
      }
    }
  }

  test("queries pack one per block like a gallery") {
    val queries = Seq(unit(11), unit(12), unit(13))
    val slots = SlotPacking.packQueries(queries)

    assertEquals(slots.length, CkksParams.Slots)
    // Block i holds query i.
    queries.zipWithIndex.foreach { (q, i) =>
      val offset = i * CkksParams.BlockSize
      (0 until CkksParams.BlockSize).foreach { j =>
        assertEqualsDouble(slots(offset + j), q.values(j).toDouble, 1e-9)
      }
    }
    // Unused blocks are zeroed.
    val used = queries.size * CkksParams.BlockSize
    assert(slots.drop(used).forall(_ == 0.0), "unused slots must be zero")
  }

  test("a batch larger than the ciphertext holds is rejected") {
    val tooMany = Seq.range(0, CkksParams.BlocksPerCiphertext + 1).map(i => unit(i))
    intercept[IllegalArgumentException](SlotPacking.packQueries(tooMany))
  }

  test("batching mode: replicated gallery dotted with packed queries gives cosine per query") {
    // The batched scoring scheme in plaintext: one gallery replicated, sixteen distinct
    // queries packed. Their dot products form the sixteen scores.
    val enrolled = unit(50)
    val queries = (0 until CkksParams.BlocksPerCiphertext).map(i => unit(100 + i)).toSeq

    val g = SlotPacking.replicateGalleryVector(enrolled)
    val q = SlotPacking.packQueries(queries)
    val products = g.zip(q).map((a, b) => a * b)

    val blockSum = (i: Int) =>
      (0 until CkksParams.BlockSize).map(j => products(i * CkksParams.BlockSize + j)).sum

    // Each block's sum should be the cosine between the enrolled vector and that query
    (0 until queries.length).foreach { i =>
      val expected = enrolled.cosine(queries(i)).toDouble
      assertEqualsDouble(blockSum(i), expected, 1e-6)
    }
  }
