package ncii.core

/** A face embedding, always L2-normalised.
  *
  * Normalisation is enforced at construction so that cosine similarity is a plain
  * dot product everywhere downstream, including inside the homomorphic matcher,
  * where re-normalising would be prohibitively expensive.
  *
  * Construct via [[Embedding.normalised]] or [[Embedding.mean]].
  */
final class Embedding private (val values: IArray[Float]):

  def dimension: Int = values.length

  /** Cosine similarity with `other`. Both are unit vectors, so this is their dot product. */
  def cosine(other: Embedding): Float =
    require(
      dimension == other.dimension,
      s"dimension mismatch: $dimension vs ${other.dimension}"
    )
    var sum = 0.0f
    var i   = 0
    while i < values.length do
      sum += values(i) * other.values(i)
      i += 1
    sum

  override def toString: String = s"Embedding(dim=$dimension)"

object Embedding:

  /** L2-normalises `raw` and wraps it. The input array is copied, not retained. */
  def normalised(raw: Array[Float]): Embedding =
    require(raw.nonEmpty, "embedding must not be empty")
    var sumSq = 0.0
    var i     = 0
    while i < raw.length do
      sumSq += raw(i).toDouble * raw(i).toDouble
      i += 1
    val norm = math.sqrt(sumSq)
    require(norm > 1e-12, "cannot normalise a zero vector")
    val out = new Array[Float](raw.length)
    i = 0
    while i < raw.length do
      out(i) = (raw(i) / norm).toFloat
      i += 1
    new Embedding(IArray.unsafeFromArray(out))

  /** Element-wise mean, renormalised. Used to collapse a video track into one vector. */
  def mean(embeddings: Seq[Embedding]): Embedding =
    require(embeddings.nonEmpty, "cannot average an empty sequence")
    val dim = embeddings.head.dimension
    require(
      embeddings.forall(_.dimension == dim),
      "all embeddings must share a dimension"
    )
    val acc = new Array[Float](dim)
    embeddings.foreach { e =>
      var i = 0
      while i < dim do
        acc(i) += e.values(i)
        i += 1
    }
    normalised(acc)
