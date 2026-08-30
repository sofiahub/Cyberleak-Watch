package ncii.core

class GallerySelectionSuite extends munit.FunSuite:

  private def unit(seed: Int): Embedding =
    val rng = new scala.util.Random(seed)
    Embedding.normalised(Array.fill(512)(rng.nextGaussian().toFloat))

  private def duplicateOf(e: Embedding): Embedding =
    Embedding.normalised(e.values.toArray)

  test("selection returns the requested count") {
    val set = (1 to 10).map(unit)
    assertEquals(GallerySelection.select(set, 4).size, 4)
  }

  test("near-duplicates are not both selected when a distant option exists") {
    // The property that matters: a gallery of five near-identical headshots spans no
    // variation and matches poorly against anything but that pose.
    val a = unit(1)
    val set = Seq(a, duplicateOf(a), duplicateOf(a), unit(2), unit(3))
    val chosen = GallerySelection.select(set, 3)

    val sims = for
      i <- chosen.indices
      j <- (i + 1) until chosen.size
    yield chosen(i).cosine(chosen(j))

    assert(sims.forall(_ < 0.99f), s"selection kept near-duplicates: $sims")
  }

  test("asking for more than are available returns all of them") {
    val set = (1 to 3).map(unit)
    assertEquals(GallerySelection.select(set, 5).size, 3)
  }

  test("selection is deterministic") {
    // The same upload must produce the same gallery on a re-run, or an enrolment cannot
    // be reproduced and a support question cannot be answered.
    val set = (1 to 8).map(unit)
    val first = GallerySelection.select(set, 4).map(_.values.toSeq)
    val second = GallerySelection.select(set, 4).map(_.values.toSeq)
    assertEquals(first, second)
  }

  test("a count below one is rejected") {
    intercept[IllegalArgumentException](GallerySelection.select(Seq(unit(1)), 0))
  }

  test("a repeated instance does not shrink the selection") {
    // Removal used to compare by reference, so every occurrence of one instance vanished
    // in a single step and the gallery came back short.
    val a   = unit(1)
    val set = Seq(a, a, a, a, unit(2))
    assertEquals(GallerySelection.select(set, 4).size, 4)
  }
