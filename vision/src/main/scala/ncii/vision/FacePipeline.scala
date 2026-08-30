package ncii.vision

import ncii.core.{DetectedFace, Embedding}
import org.bytedeco.opencv.opencv_core.Mat

import java.nio.file.Path

/** Detect, align and embed every face in an image.
  *
  * Owns a detector and an embedder, so it is not thread-safe; give each worker
  * thread its own pipeline.
  */
final class FacePipeline private (
    detector: FaceDetector,
    embedder: FaceEmbedder
) extends AutoCloseable:

  /** Every face in `image` with its embedding. The tracker needs both the box and
    * the vector, which `embedMat` discards.
    */
  def detectAndEmbed(image: Mat): Seq[(DetectedFace, Embedding)] =
    detector.detect(image).map { face =>
      val aligned = FaceAligner.align(image, face.landmarks)
      try (face, embedder.embed(aligned))
      finally aligned.close()
    }

  def embedMat(image: Mat): Seq[Embedding] = detectAndEmbed(image).map(_._2)

  /** Detect and embed all faces, returning geometry alongside embeddings.
    *
    * Callers that need to select faces by position (e.g., LFW protocol's centre-face
    * rule) can use this to access the bounding boxes.
    */
  def embedMatWithGeometry(image: Mat): Seq[(DetectedFace, Embedding)] =
    detectAndEmbed(image)

  /** Detect and embed all faces in an image file, returning geometry alongside embeddings.
    *
    * Callers that need to select faces by position can use this.
    */
  def embedImageWithGeometry(path: Path): Seq[(DetectedFace, Embedding)] =
    val image = Images.read(path)
    try embedMatWithGeometry(image)
    finally image.close()

  def embedImage(path: Path): Seq[Embedding] =
    val image = Images.read(path)
    try embedMat(image)
    finally image.close()

  def close(): Unit =
    detector.close()
    embedder.close()

object FacePipeline:

  def open(): FacePipeline =
    new FacePipeline(FaceDetector.open(), FaceEmbedder.open())
