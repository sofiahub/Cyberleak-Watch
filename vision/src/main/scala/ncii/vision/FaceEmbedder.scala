package ncii.vision

import ncii.core.Embedding
import org.bytedeco.javacpp.indexer.UByteIndexer
import org.bytedeco.opencv.opencv_core.Mat

import java.nio.file.{Files, Path}

/** ArcFace `w600k_r50` inference over an aligned 112x112 face.
  *
  * Preprocessing must match InsightFace: BGR to RGB, scale to [-1, 1] via
  * `(pixel - 127.5) / 127.5`, and channel-first layout. Getting the channel order or
  * the scaling wrong yields embeddings that look healthy, finite, unit length,
  * but carry far less identity signal, which is easy to miss without measurement.
  */
final class FaceEmbedder private (model: OnnxModel) extends AutoCloseable:

  private val inputName = model.inputNames.head
  private val size      = FaceAligner.OutputSize

  def embed(alignedFace: Mat): Embedding =
    require(
      alignedFace.rows == size && alignedFace.cols == size,
      s"expected ${size}x$size, got ${alignedFace.cols}x${alignedFace.rows}"
    )
    require(alignedFace.channels == 3, s"expected 3 channels, got ${alignedFace.channels}")

    val planeSize = size * size
    val input     = new Array[Float](3 * planeSize)
    val idx       = alignedFace.createIndexer[UByteIndexer]()
    try
      var y = 0
      while y < size do
        var x = 0
        while x < size do
          val pixel = y * size + x
          // OpenCV stores BGR; ArcFace expects RGB, so channels are read in reverse.
          val b = idx.get(y.toLong, x.toLong, 0L).toFloat
          val g = idx.get(y.toLong, x.toLong, 1L).toFloat
          val r = idx.get(y.toLong, x.toLong, 2L).toFloat
          input(0 * planeSize + pixel) = (r - 127.5f) / 127.5f
          input(1 * planeSize + pixel) = (g - 127.5f) / 127.5f
          input(2 * planeSize + pixel) = (b - 127.5f) / 127.5f
          x += 1
        y += 1
    finally idx.close()

    val raw = model.run(inputName, input, Array(1L, 3L, size.toLong, size.toLong))
    Embedding.normalised(raw)

  def close(): Unit = model.close()

object FaceEmbedder:

  def open(model: Path = Assets.embedderModel): FaceEmbedder =
    require(Files.exists(model), s"embedder model not found: $model")
    new FaceEmbedder(OnnxModel.open(model))
