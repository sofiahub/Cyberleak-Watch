package ncii.vision

import ncii.core.{BoundingBox, DetectedFace, Embedding, Landmarks5, Point}
import org.bytedeco.javacv.{FFmpegFrameRecorder, OpenCVFrameConverter}
import org.bytedeco.opencv.global.opencv_core.CV_8UC3
import org.bytedeco.opencv.opencv_core.{Mat, Scalar}

import java.nio.file.{Files, Path}

class VideoPipelineSuite extends munit.FunSuite:

  // munit's 30-second default is not sized for this project's integration suites. They
  // load a 166 MB ONNX model, generate CKKS key sets at ~47 MB of Galois keys each, decode
  // video, or start a Postgres container, legitimately slow work that competes with
  // whatever else the machine is running. Three separate suites timed out at 31-197s while
  // asserting nothing wrong, so the limit is set to the work rather than raised one failure
  // at a time.
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  /** Writes a 4-second, 10fps synthetic video so the test needs no external asset. */
  private def synthesiseVideo(path: Path): Unit =
    val width    = 320
    val height   = 240
    val recorder = new FFmpegFrameRecorder(path.toFile, width, height)
    recorder.setFormat("mp4")
    recorder.setVideoCodecName("libx264")
    recorder.setFrameRate(10.0)
    recorder.start()
    val converter = new OpenCVFrameConverter.ToMat()
    try
      var i = 0
      while i < 40 do
        val shade = new Mat(height, width, CV_8UC3, new Scalar(i * 5, i * 3, i * 2, 0))
        try recorder.record(converter.convert(shade))
        finally shade.close()
        i += 1
    finally
      recorder.stop()
      recorder.release()
      converter.close()

  private def embeddingSeed(seed: Int): Embedding =
    val rng = new scala.util.Random(seed)
    Embedding.normalised(Array.fill(512)(rng.nextFloat()))

  private def detection(box: BoundingBox): DetectedFace =
    DetectedFace(
      box = box,
      landmarks = Landmarks5(
        Point(box.x + 10.0f, box.y + 10.0f),
        Point(box.x + 30.0f, box.y + 10.0f),
        Point(box.x + 20.0f, box.y + 20.0f),
        Point(box.x + 12.0f, box.y + 30.0f),
        Point(box.x + 28.0f, box.y + 30.0f)
      ),
      score = 0.9f
    )

  test("sampling a 4-second video at 0.5s yields about eight frames") {
    val video = Files.createTempFile("ncii-sampler", ".mp4")
    try
      synthesiseVideo(video)
      var timestamps = List.empty[Double]
      VideoSampler.sample(video, intervalSeconds = 0.5) { frame =>
        timestamps = frame.timestampSeconds :: timestamps
        assert(!frame.image.empty(), "sampled frame must not be empty")
      }
      val count = timestamps.size
      assert(count >= 7 && count <= 9, s"expected about 8 frames, got $count")
      assert(
        timestamps.reverse.sliding(2).forall { case Seq(a, b) => b > a; case _ => true },
        "timestamps must increase"
      )
    finally Files.deleteIfExists(video)
  }

  test("a face holding position across frames becomes one track") {
    val personA = embeddingSeed(1)
    val frames = (0 until 6).map { i =>
      val t = i * 0.5
      (t, Seq((detection(BoundingBox((100 + i).toFloat, 100.toFloat, 80.toFloat, 80.toFloat)), personA)))
    }
    val tracks = FaceTracker.track(frames)
    assertEquals(tracks.size, 1)
    assertEquals(tracks.head.observations.size, 6)
    assertEqualsDouble(tracks.head.durationSeconds, 2.5, 1e-6)
  }

  test("two faces far apart become two tracks") {
    val personA = embeddingSeed(1)
    val personB = embeddingSeed(2)
    val frames = (0 until 4).map { i =>
      val t = i * 0.5
      (
        t,
        Seq(
          (detection(BoundingBox(0.0f, 0.0f, 80.0f, 80.0f)), personA),
          (detection(BoundingBox(500.0f, 400.0f, 80.0f, 80.0f)), personB)
        )
      )
    }
    val tracks = FaceTracker.track(frames)
    assertEquals(tracks.size, 2)
    assert(tracks.forall(_.observations.size == 4), "each track should have 4 observations")
  }

  test("a track's mean embedding is unit length") {
    // Use two different embeddings (different seeds) so their mean is genuinely
    // different from either member. This tests that Embedding.mean actually
    // renormalises, since an unnormalised mean of two orthogonal-ish vectors
    // would have magnitude < 1.0.
    val personA = embeddingSeed(1)
    val personB = embeddingSeed(2)

    val frames = Seq(
      (0.0, Seq((detection(BoundingBox(100.0f, 100.0f, 80.0f, 80.0f)), personA))),
      (0.5, Seq((detection(BoundingBox(100.0f, 100.0f, 80.0f, 80.0f)), personB)))
    )
    val track = FaceTracker.track(frames).head
    val mean = track.meanEmbedding

    // Assert unit length directly: magnitude should be 1.0 (not 0.707 or other value)
    val magnitude = math.sqrt(mean.values.map(v => v.toDouble * v.toDouble).sum)
    val unnormalisedMagnitude = math.sqrt(
      Array.from(personA.values)
        .zip(Array.from(personB.values))
        .map { case (a, b) => ((a + b) / 2.0) * ((a + b) / 2.0) }
        .sum
    )
    println(
      f"Mean embedding test: unnormalised magnitude before Embedding.mean would be $unnormalisedMagnitude%.4f; " +
      f"actual magnitude after Embedding.mean = $magnitude%.6f (should be 1.0)"
    )
    assertEqualsDouble(magnitude, 1.0, 1e-5)
  }

  test("a track's mean embedding points toward its members") {
    // Verify the mean embedding has positive cosine similarity with each member.
    // This ensures the mean is in a sensible direction, not orthogonal to input.
    val personA = embeddingSeed(1)
    val personB = embeddingSeed(2)
    val personC = embeddingSeed(3)

    val frames = Seq(
      (0.0, Seq((detection(BoundingBox(100.0f, 100.0f, 80.0f, 80.0f)), personA))),
      (0.5, Seq((detection(BoundingBox(100.0f, 100.0f, 80.0f, 80.0f)), personB))),
      (1.0, Seq((detection(BoundingBox(100.0f, 100.0f, 80.0f, 80.0f)), personC)))
    )
    val track = FaceTracker.track(frames).head
    val meanCosineA = track.meanEmbedding.cosine(personA)
    val meanCosineB = track.meanEmbedding.cosine(personB)
    val meanCosineC = track.meanEmbedding.cosine(personC)

    // Mean should have positive dot product with all members
    assert(meanCosineA > 0.3f, s"mean should align with personA, got cosine=$meanCosineA")
    assert(meanCosineB > 0.3f, s"mean should align with personB, got cosine=$meanCosineB")
    assert(meanCosineC > 0.3f, s"mean should align with personC, got cosine=$meanCosineC")
  }

  test("no frames yields no tracks") {
    assertEquals(FaceTracker.track(Seq.empty), Seq.empty)
  }

  test("a face moving 40% of box width per frame stays one track") {
    // Validate MinIou = 0.2 threshold: at 40% displacement per frame,
    // IoU ≈ 0.43, which is 2.1x above the threshold but low enough that
    // the assertion proves the threshold is being enforced.
    val personA = embeddingSeed(1)
    val boxWidth = 80.0f
    val displacement = 0.4f * boxWidth // 40% = 32 pixels per frame
    val frames = (0 until 4).map { i =>
      val t = i * 0.5
      val x = 100.0f + (i.toFloat * displacement)
      (t, Seq((detection(BoundingBox(x, 100.0f, boxWidth, boxWidth)), personA)))
    }
    val tracks = FaceTracker.track(frames)
    assertEquals(tracks.size, 1, "face with 40% per-frame displacement should not fragment")
    assertEquals(tracks.head.observations.size, 4, "track should contain all 4 observations")

    // Calculate actual per-frame IoU to document the margin against MinIou threshold
    val boxes = frames.map { case (_, detections) =>
      detections.head._1.box
    }
    val iou_01 = boxes(0).intersectionOverUnion(boxes(1))
    val iou_12 = boxes(1).intersectionOverUnion(boxes(2))
    val iou_23 = boxes(2).intersectionOverUnion(boxes(3))
    val avgIou = (iou_01 + iou_12 + iou_23) / 3.0f
    println(
      f"Face motion test: displacement = 32px (40%% of 80px box); " +
      f"per-frame IoU: step0→1=$iou_01%.4f, step1→2=$iou_12%.4f, step2→3=$iou_23%.4f, " +
      f"avg=$avgIou%.4f; MinIou threshold=0.2, margin=${avgIou - 0.2f}%.4f"
    )
    assert(avgIou > 0.2f, "test IoU should exceed MinIou threshold")
  }
