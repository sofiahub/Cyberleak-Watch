package ncii.vision

import ncii.core.DetectedFace
import org.bytedeco.opencv.global.opencv_core.{CV_64F, meanStdDev}
import org.bytedeco.opencv.global.opencv_imgproc.{COLOR_BGR2GRAY, Laplacian, cvtColor}
import org.bytedeco.opencv.opencv_core.Mat

enum QualityVerdict:
  case Accepted(sharpness: Double)
  case Rejected(reason: String)

/** Rejects faces too poor to enrol from.
  *
  * The thresholds are deliberately conservative: a rejected photo costs the user
  * one re-upload, whereas a low-quality embedding silently degrades every future
  * match and leaves no trace explaining why.
  */
object QualityGate:

  val MinFaceSize: Float      = 60.0f
  val MinSharpness: Double    = 40.0
  val MaxEyeTiltRatio: Double = 0.35

  /** Variance of the Laplacian, the standard quick blur estimate. Higher is sharper.
    * Computed on the cropped face region, not the original image.
    */
  def sharpness(image: Mat): Double =
    val grey = new Mat()
    val edges = new Mat()
    val mean = new Mat()
    val stdDev = new Mat()
    try
      if image.channels == 1 then image.copyTo(grey)
      else cvtColor(image, grey, COLOR_BGR2GRAY)
      Laplacian(grey, edges, CV_64F)
      meanStdDev(edges, mean, stdDev)
      val sd = stdDev.createIndexer[org.bytedeco.javacpp.indexer.DoubleIndexer]()
      try
        val v = sd.get(0L, 0L)
        v * v
      finally sd.close()
    finally
      grey.close()
      edges.close()
      mean.close()
      stdDev.close()

  def assess(image: Mat, face: DetectedFace): QualityVerdict =
    val box = face.box
    if box.width < MinFaceSize || box.height < MinFaceSize then
      QualityVerdict.Rejected(
        f"face too small: ${box.width}%.0fx${box.height}%.0f, minimum is ${MinFaceSize}%.0f"
      )
    else
      val lm       = face.landmarks
      val eyeDx    = math.abs(lm.subjectLeftEye.x - lm.subjectRightEye.x).toDouble
      val eyeDy    = math.abs(lm.subjectLeftEye.y - lm.subjectRightEye.y).toDouble
      val tilt     = if eyeDx < 1.0 then Double.MaxValue else eyeDy / eyeDx
      if tilt > MaxEyeTiltRatio then
        QualityVerdict.Rejected(f"extreme pose or roll: eye tilt ratio $tilt%.2f")
      else
        val cropped = crop(image, face)
        try
          val s = sharpness(cropped)
          if s < MinSharpness then
            QualityVerdict.Rejected(f"too blurry: sharpness $s%.1f below ${MinSharpness}%.1f")
          else QualityVerdict.Accepted(s)
        finally cropped.close()

  private def crop(image: Mat, face: DetectedFace): Mat =
    val x = math.max(0, face.box.x.toInt)
    val y = math.max(0, face.box.y.toInt)
    val w = math.min(image.cols - x, face.box.width.toInt)
    val h = math.min(image.rows - y, face.box.height.toInt)
    require(w > 0 && h > 0, s"face box lies outside the image: ${face.box}")
    new Mat(image, new org.bytedeco.opencv.opencv_core.Rect(x, y, w, h))
