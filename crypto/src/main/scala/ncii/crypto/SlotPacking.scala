package ncii.crypto

import ncii.core.Embedding

/** Maps embeddings onto CKKS slots.
  *
  * A ciphertext carries 8192 slots as sixteen 512-slot blocks, one embedding per
  * block. The query is replicated into every block so a single ciphertext x plaintext
  * multiply produces all sixteen element-wise products at once; a rotate-and-sum then
  * collapses each block to its dot product.
  *
  * Both sides are L2-normalised by construction, so that dot product is cosine
  * similarity. Nothing here renormalises.
  */
object SlotPacking:

  /** Lays out embeddings onto slots, one per block, zero-padding the remainder.
    *
    * This is a private helper used by packGallery and packQueries. Both pack one
    * embedding per block, but have distinct call sites: galleries are encrypted once
    * and scored many times, while queries are plaintext and new for each score.
    */
  private def packBlocks(embeddings: Seq[Embedding]): Array[Double] =
    require(
      embeddings.size <= CkksParams.BlocksPerCiphertext,
      s"embeddings (${embeddings.size}) exceed ${CkksParams.BlocksPerCiphertext} blocks per ciphertext"
    )
    embeddings.zipWithIndex.foreach { (e, i) =>
      require(
        e.dimension == CkksParams.BlockSize,
        s"embedding $i has dimension ${e.dimension}, expected ${CkksParams.BlockSize}"
      )
    }

    val slots = new Array[Double](CkksParams.Slots)
    embeddings.zipWithIndex.foreach { (e, i) =>
      val offset = i * CkksParams.BlockSize
      var j = 0
      while j < CkksParams.BlockSize do
        slots(offset + j) = e.values(j).toDouble
        j += 1
    }
    slots

  /** Packs a gallery: one embedding per block, zero-padding the remainder.
    *
    * Used to prepare plaintext galleries for encryption into EncryptedGallery.
    */
  def packGallery(embeddings: Seq[Embedding]): Array[Double] =
    packBlocks(embeddings)

  /** Packs a batch of queries: one query per block, zero-padding the remainder.
    *
    * Used in batched scoring, where sixteen distinct queries are packed into
    * plaintext and multiplied against one encrypted gallery vector replicated
    * across all sixteen blocks.
    */
  def packQueries(queries: Seq[Embedding]): Array[Double] =
    packBlocks(queries)

  /** Replicates one embedding identically into all sixteen blocks.
    *
    * This is a private helper used by replicateQuery and replicateGalleryVector. Both
    * replicate one embedding into every block, but have distinct call sites: queries
    * are replicated for the correctness path (one query, all blocks), while gallery
    * vectors are replicated for the batching path (one gallery, sixteen queries).
    */
  private def replicate(embedding: Embedding): Array[Double] =
    require(
      embedding.dimension == CkksParams.BlockSize,
      s"embedding has dimension ${embedding.dimension}, expected ${CkksParams.BlockSize}"
    )
    val slots = new Array[Double](CkksParams.Slots)
    var b = 0
    while b < CkksParams.BlocksPerCiphertext do
      val offset = b * CkksParams.BlockSize
      var j = 0
      while j < CkksParams.BlockSize do
        slots(offset + j) = embedding.values(j).toDouble
        j += 1
      b += 1
    slots

  /** Repeats one query across all sixteen blocks. */
  def replicateQuery(query: Embedding): Array[Double] =
    replicate(query)

  /** Replicates one embedding across all sixteen blocks.
    *
    * Used in batched scoring to encrypt one gallery vector into all blocks,
    * which is then scored against sixteen distinct plaintext queries packed
    * via packQueries.
    */
  def replicateGalleryVector(embedding: Embedding): Array[Double] =
    replicate(embedding)

  /** Reads one score per block from each block's first slot. */
  def extractBlockSums(slots: Array[Double], count: Int): Array[Double] =
    require(
      slots.length == CkksParams.Slots,
      s"expected ${CkksParams.Slots} slots, got ${slots.length}"
    )
    require(
      count >= 0 && count <= CkksParams.BlocksPerCiphertext,
      s"count $count outside 0..${CkksParams.BlocksPerCiphertext}"
    )
    Array.tabulate(count)(i => slots(i * CkksParams.BlockSize))
