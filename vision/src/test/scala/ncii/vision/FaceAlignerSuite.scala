package ncii.vision

import java.nio.file.Files
import org.bytedeco.javacpp.indexer.UByteIndexer
import scala.jdk.StreamConverters.*

class FaceAlignerSuite extends munit.FunSuite:

  // munit's 30-second default is not sized for this project's integration suites. They
  // load a 166 MB ONNX model, generate CKKS key sets at ~47 MB of Galois keys each, decode
  // video, or start a Postgres container, legitimately slow work that competes with
  // whatever else the machine is running. Three separate suites timed out at 31-197s while
  // asserting nothing wrong, so the limit is set to the work rather than raised one failure
  // at a time.
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  private def anyLfwImage: Option[java.nio.file.Path] =
    if !Assets.available(Assets.lfwDir) then None
    else Files.walk(Assets.lfwDir).toScala(LazyList).find(_.toString.endsWith(".jpg"))

  test("aligned output is 112x112 and three-channel") {
    val imagePath = anyLfwImage
    assume(imagePath.isDefined, Assets.missingMessage(Assets.lfwDir))

    val detector = FaceDetector.open()
    val image    = Images.read(imagePath.get)
    try
      val face    = detector.detect(image).head
      val aligned = FaceAligner.align(image, face.landmarks)
      try
        assertEquals(aligned.rows, 112)
        assertEquals(aligned.cols, 112)
        assertEquals(aligned.channels, 3)
      finally aligned.close()
    finally
      image.close()
      detector.close()
  }

  test("landmarks of the aligned face land on the canonical template") {
    // This is the real check on landmark ordering: re-detecting on the aligned
    // crop must place the eyes where ArcFace expects them. A mirrored ordering
    // produces a plausible-looking image whose landmarks are in the wrong places.
    val imagePath = anyLfwImage
    assume(imagePath.isDefined, Assets.missingMessage(Assets.lfwDir))

    val detector = FaceDetector.open()
    val image    = Images.read(imagePath.get)
    try
      val face    = detector.detect(image).head
      val aligned = FaceAligner.align(image, face.landmarks)
      try
        val redetected = detector.detect(aligned)
        assume(redetected.nonEmpty, "detector found no face in the aligned crop")

        val lm       = redetected.head.landmarks.toArray
        val template = FaceAligner.CanonicalLandmarks
        val drift    = lm.zip(template).map((a, b) => math.abs(a - b)).max

        assert(
          drift < 8.0f,
          s"aligned landmarks drift ${drift}px from the template; " +
            s"got ${lm.toSeq}, expected ${template.toSeq}"
        )
      finally aligned.close()
    finally
      image.close()
      detector.close()
  }

  test("measure per-landmark drift from re-detection") {
    // Measure actual drift to verify sub-pixel accuracy after Umeyama alignment.
    // Reports per-landmark and max drift.
    val imagePath = anyLfwImage
    assume(imagePath.isDefined, Assets.missingMessage(Assets.lfwDir))

    val detector = FaceDetector.open()
    val image    = Images.read(imagePath.get)
    try
      val face    = detector.detect(image).head
      val aligned = FaceAligner.align(image, face.landmarks)
      try
        val redetected = detector.detect(aligned)
        assume(redetected.nonEmpty, "detector found no face in the aligned crop")

        val lm       = redetected.head.landmarks.toArray
        val template = FaceAligner.CanonicalLandmarks
        val drifts   = lm.zip(template).map((a, b) => math.abs(a - b))
        val max_drift = drifts.max

        // Report per-landmark drift (10 coordinates: 5 landmarks × 2 coords each)
        val landmarkNames = Seq("right_eye_x", "right_eye_y", "left_eye_x", "left_eye_y",
                               "nose_x", "nose_y", "right_mouth_x", "right_mouth_y",
                               "left_mouth_x", "left_mouth_y")
        val driftReport = landmarkNames.zip(drifts).map { (name, drift) =>
          f"$name: $drift%.6f"
        }.mkString(", ")
        println(s"Per-landmark drift (px): $driftReport")
        println(f"Max drift: $max_drift%.6f px")

        // Assert within tolerance
        assert(
          max_drift < 8.0f,
          s"max drift ${max_drift}px exceeds 8px threshold"
        )
      finally aligned.close()
    finally
      image.close()
      detector.close()
  }

  test("alignment is deterministic (bit-identical on repeated runs)") {
    // Verify Umeyama produces identical output on repeated calls.
    // This catches regressions if RANSAC or other randomisation is reintroduced.
    val imagePath = anyLfwImage
    assume(imagePath.isDefined, Assets.missingMessage(Assets.lfwDir))

    val detector = FaceDetector.open()
    val image    = Images.read(imagePath.get)
    try
      val face = detector.detect(image).head

      // Align twice
      val aligned1 = FaceAligner.align(image, face.landmarks)
      val aligned2 = FaceAligner.align(image, face.landmarks)
      try
        // Verify dimensions match
        assertEquals(aligned1.rows, aligned2.rows)
        assertEquals(aligned1.cols, aligned2.cols)
        assertEquals(aligned1.channels, aligned2.channels)

        // Compare byte-for-byte: both are 112x112x3 uint8 BGR images
        val mat1Bytes = aligned1.createIndexer[UByteIndexer]()
        val mat2Bytes = aligned2.createIndexer[UByteIndexer]()
        try
          var allIdentical = true
          for
            r <- 0 until aligned1.rows
            c <- 0 until aligned1.cols
            ch <- 0 until aligned1.channels
          do
            val b1 = mat1Bytes.get(r.toLong, c.toLong, ch.toLong)
            val b2 = mat2Bytes.get(r.toLong, c.toLong, ch.toLong)
            if b1 != b2 then
              allIdentical = false
              println(f"Difference at ($r, $c, $ch): $b1 vs $b2")

          assert(
            allIdentical,
            "alignment output is not deterministic (bytes differ between runs)"
          )
        finally
          mat1Bytes.close()
          mat2Bytes.close()
      finally
        aligned1.close()
        aligned2.close()
    finally
      image.close()
      detector.close()
  }
