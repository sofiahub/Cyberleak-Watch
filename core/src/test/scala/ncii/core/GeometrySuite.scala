package ncii.core

class GeometrySuite extends munit.FunSuite:

  test("area is width times height") {
    assertEqualsFloat(BoundingBox(0, 0, 4, 5).area, 20.0f, 1e-6f)
  }

  test("IoU of a box with itself is 1") {
    val b = BoundingBox(10, 10, 20, 20)
    assertEqualsFloat(b.intersectionOverUnion(b), 1.0f, 1e-6f)
  }

  test("IoU of disjoint boxes is 0") {
    val a = BoundingBox(0, 0, 10, 10)
    val b = BoundingBox(100, 100, 10, 10)
    assertEqualsFloat(a.intersectionOverUnion(b), 0.0f, 1e-6f)
  }

  test("IoU of boxes touching only at an edge is 0") {
    val a = BoundingBox(0, 0, 10, 10)
    val b = BoundingBox(10, 0, 10, 10)
    assertEqualsFloat(a.intersectionOverUnion(b), 0.0f, 1e-6f)
  }

  test("IoU of half-overlapping boxes is one third") {
    // Each box is 10x10. Overlap is 5x10 = 50. Union is 100 + 100 - 50 = 150.
    val a = BoundingBox(0, 0, 10, 10)
    val b = BoundingBox(5, 0, 10, 10)
    assertEqualsFloat(a.intersectionOverUnion(b), 1.0f / 3.0f, 1e-6f)
  }

  test("IoU is symmetric") {
    val a = BoundingBox(0, 0, 10, 10)
    val b = BoundingBox(3, 4, 12, 8)
    assertEqualsFloat(
      a.intersectionOverUnion(b),
      b.intersectionOverUnion(a),
      1e-6f
    )
  }

  test("landmarks flatten in x,y order starting with the subject's right eye") {
    val lm = Landmarks5(
      subjectRightEye   = Point(1, 2),
      subjectLeftEye    = Point(3, 4),
      nose              = Point(5, 6),
      subjectRightMouth = Point(7, 8),
      subjectLeftMouth  = Point(9, 10)
    )
    assertEquals(
      lm.toArray.toSeq,
      Seq(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f)
    )
  }
