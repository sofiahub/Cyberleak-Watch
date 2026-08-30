package ncii.vision

import ncii.core.{BoundingBox, DetectedFace, Landmarks5, Point}
import org.bytedeco.opencv.global.opencv_imgproc.GaussianBlur
import org.bytedeco.opencv.opencv_core.{Mat, Size}

import java.nio.file.Files
import scala.jdk.StreamConverters.*

class QualityGateSuite extends munit.FunSuite:

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

  private def face(box: BoundingBox, eyeGap: Float = 30f, eyeY: Float = 40f): DetectedFace =
    DetectedFace(
      box = box,
      landmarks = Landmarks5(
        subjectRightEye   = Point(box.x + 20, box.y + eyeY),
        subjectLeftEye    = Point(box.x + 20 + eyeGap, box.y + eyeY),
        nose              = Point(box.x + 20 + eyeGap / 2, box.y + eyeY + 20),
        subjectRightMouth = Point(box.x + 22, box.y + eyeY + 40),
        subjectLeftMouth  = Point(box.x + 18 + eyeGap, box.y + eyeY + 40)
      ),
      score = 0.95f
    )

  test("rejects a face smaller than the minimum size") {
    val image = new Mat(480, 640, org.bytedeco.opencv.global.opencv_core.CV_8UC3)
    try
      val verdict = QualityGate.assess(image, face(BoundingBox(10, 10, 30, 30)))
      verdict match
        case QualityVerdict.Rejected(reason) => assert(reason.contains("too small"))
        case other                            => fail(s"expected rejection, got $other")
    finally image.close()
  }

  test("rejects a face whose eye line is far from level") {
    val image = new Mat(480, 640, org.bytedeco.opencv.global.opencv_core.CV_8UC3)
    try
      val tilted = DetectedFace(
        box = BoundingBox(100, 100, 120, 120),
        landmarks = Landmarks5(
          subjectRightEye   = Point(120, 120),
          subjectLeftEye    = Point(180, 200), // 80px vertical offset over 60px horizontal
          nose              = Point(150, 170),
          subjectRightMouth = Point(125, 200),
          subjectLeftMouth  = Point(175, 220)
        ),
        score = 0.95f
      )
      val verdict = QualityGate.assess(image, tilted)
      verdict match
        case QualityVerdict.Rejected(reason) => assert(reason.contains("pose"))
        case other                            => fail(s"expected rejection, got $other")
    finally image.close()
  }

  test("blurring a real photograph lowers its sharpness score") {
    val imagePath = anyLfwImage
    assume(imagePath.isDefined, Assets.missingMessage(Assets.lfwDir))

    val sharp   = Images.read(imagePath.get)
    val blurred = new Mat()
    try
      GaussianBlur(sharp, blurred, new Size(15, 15), 0.0)
      val sharpScore   = QualityGate.sharpness(sharp)
      val blurredScore = QualityGate.sharpness(blurred)
      assert(
        blurredScore < sharpScore / 2,
        s"blur should roughly halve sharpness at least: $blurredScore vs $sharpScore"
      )
    finally
      sharp.close()
      blurred.close()
  }

  test("accepts a well-sized, level face in a real photograph") {
    val imagePath = anyLfwImage
    assume(imagePath.isDefined, Assets.missingMessage(Assets.lfwDir))

    val detector = FaceDetector.open()
    val image    = Images.read(imagePath.get)
    try
      val detected = detector.detect(image).head
      QualityGate.assess(image, detected) match
        case QualityVerdict.Accepted(sharpness) => assert(sharpness > 0.0)
        case QualityVerdict.Rejected(reason)    => fail(s"unexpected rejection: $reason")
    finally
      image.close()
      detector.close()
  }
