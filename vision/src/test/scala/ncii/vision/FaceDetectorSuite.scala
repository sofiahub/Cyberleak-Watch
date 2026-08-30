package ncii.vision

import java.nio.file.Files
import scala.jdk.StreamConverters.*

class FaceDetectorSuite extends munit.FunSuite:

  // munit's 30-second default is not sized for this project's integration suites. They
  // load a 166 MB ONNX model, generate CKKS key sets at ~47 MB of Galois keys each, decode
  // video, or start a Postgres container, legitimately slow work that competes with
  // whatever else the machine is running. Three separate suites timed out at 31-197s while
  // asserting nothing wrong, so the limit is set to the work rather than raised one failure
  // at a time.
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  /** First LFW image on disk, whichever it happens to be. */
  private def anyLfwImage: Option[java.nio.file.Path] =
    if !Assets.available(Assets.lfwDir) then None
    else
      Files
        .walk(Assets.lfwDir)
        .toScala(LazyList)
        .find(_.toString.endsWith(".jpg"))

  test("detects exactly one face in a portrait and returns plausible landmarks") {
    val imagePath = anyLfwImage
    assume(imagePath.isDefined, Assets.missingMessage(Assets.lfwDir))

    val detector = FaceDetector.open()
    val image    = Images.read(imagePath.get)
    try
      val faces = detector.detect(image)
      assertEquals(faces.size, 1, s"expected one face in ${imagePath.get}")

      val face = faces.head
      assert(face.score > 0.6f, s"low detection score: ${face.score}")
      assert(face.box.width > 20 && face.box.height > 20, s"tiny box: ${face.box}")

      // The subject's right eye is on the image-left, so it has the smaller x.
      assert(
        face.landmarks.subjectRightEye.x < face.landmarks.subjectLeftEye.x,
        s"landmark ordering looks mirrored: ${face.landmarks}"
      )
      // Eyes sit above the mouth.
      assert(
        face.landmarks.subjectRightEye.y < face.landmarks.subjectRightMouth.y,
        s"eyes should be above the mouth: ${face.landmarks}"
      )
    finally
      image.close()
      detector.close()
  }

  test("returns nothing for a blank image") {
    val detector = FaceDetector.open()
    val blank    = new org.bytedeco.opencv.opencv_core.Mat(
      480,
      640,
      org.bytedeco.opencv.global.opencv_core.CV_8UC3,
      new org.bytedeco.opencv.opencv_core.Scalar(0, 0, 0, 0)
    )
    try assertEquals(detector.detect(blank), Seq.empty, "should detect no faces in blank image")
    finally
      blank.close()
      detector.close()
  }

  test("reading a missing image fails loudly") {
    intercept[IllegalArgumentException](
      Images.read(Assets.dataDir.resolve("no-such-image.jpg"))
    )
  }
