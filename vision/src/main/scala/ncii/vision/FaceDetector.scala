package ncii.vision

import ncii.core.{BoundingBox, DetectedFace, Landmarks5, Point}
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.indexer.FloatIndexer
import org.bytedeco.opencv.global.opencv_core.CV_8UC1
import org.bytedeco.opencv.global.opencv_imgcodecs.{IMREAD_COLOR, imread, imdecode}
import org.bytedeco.opencv.opencv_core.{Mat, Size}
import org.bytedeco.opencv.opencv_objdetect.FaceDetectorYN

import java.nio.file.{Files, Path}

object Images:
  /** Reads an image as 8-bit BGR. Throws if the file is missing or undecodable. */
  def read(path: Path): Mat =
    require(Files.exists(path), s"image not found: $path")
    val mat = imread(path.toString, IMREAD_COLOR)
    require(!mat.empty(), s"could not decode image: $path")
    mat

  /** Decodes encoded image bytes without touching the filesystem.
    *
    * This method exists because the vault's privacy guarantee requires decoding from
    * memory. Writing decrypted bytes to a temp file would put plaintext media back on
    * disk, defeating the vault's purpose. A later reader must not "simplify" this back
    * to a filesystem path.
    */
  def decode(bytes: Array[Byte]): Mat =
    val ptr = new BytePointer(bytes*)
    val buf = new Mat(1, bytes.length, CV_8UC1, ptr)
    try
      val img = imdecode(buf, IMREAD_COLOR)
      if img.empty() then throw new IllegalArgumentException("could not decode image bytes")
      img
    finally
      buf.close()
      ptr.close()

/** Face detection via OpenCV's YuNet model.
  *
  * YuNet emits one row per face with 15 columns:
  * `x, y, w, h`, then five landmark pairs, then the score. The landmark order is
  * right eye, left eye, nose tip, right mouth corner, left mouth corner, "right"
  * meaning the subject's right, which appears on the image-left.
  *
  * Not thread-safe; the underlying detector holds mutable input-size state.
  */
final class FaceDetector private (detector: FaceDetectorYN) extends AutoCloseable:

  def detect(image: Mat): Seq[DetectedFace] =
    require(!image.empty(), "cannot detect faces in an empty image")
    detector.setInputSize(new Size(image.cols, image.rows))

    val results = new Mat()
    try
      detector.detect(image, results)
      if results.empty() || results.rows == 0 then Seq.empty
      else
        val idx = results.createIndexer[FloatIndexer]()
        try
          (0 until results.rows).map { r =>
            def at(c: Int): Float = idx.get(r.toLong, c.toLong)
            DetectedFace(
              box = BoundingBox(at(0), at(1), at(2), at(3)),
              landmarks = Landmarks5(
                subjectRightEye   = Point(at(4), at(5)),
                subjectLeftEye    = Point(at(6), at(7)),
                nose              = Point(at(8), at(9)),
                subjectRightMouth = Point(at(10), at(11)),
                subjectLeftMouth  = Point(at(12), at(13))
              ),
              score = at(14)
            )
          }
        finally idx.close()
    finally results.close()

  def close(): Unit = detector.close()

object FaceDetector:

  def open(
      model: Path = Assets.detectorModel,
      scoreThreshold: Float = 0.6f,
      nmsThreshold: Float = 0.3f,
      topK: Int = 50
  ): FaceDetector =
    require(Files.exists(model), s"detector model not found: $model")
    val detector = FaceDetectorYN.create(
      model.toString,
      "",
      new Size(320, 320), // replaced per image by setInputSize
      scoreThreshold,
      nmsThreshold,
      topK,
      0, // backend_id
      0  // target_id
    )
    new FaceDetector(detector)
