package ncii.crypto

import java.lang.foreign.{Arena, FunctionDescriptor, MemorySegment, ValueLayout}
import java.lang.invoke.MethodHandle

import ncii.core.Embedding

/** An encrypted score ciphertext. Wraps a native handle and must be closed.
  *
  * Not thread-safe.
  */
final class EncryptedScores private (val handle: Long) extends AutoCloseable:

  private var closed = false

  def close(): Unit =
    if !closed then
      closed = true
      NativeLibrary.freeHandle(handle)

object EncryptedScores:
  private[crypto] def apply(handle: Long): EncryptedScores = new EncryptedScores(handle)

/** Homomorphic dot product of encrypted gallery against plaintext query.
  *
  * Scorer.score performs evaluation (ciphertext × plaintext multiply and rotate-and-sum)
  * without requiring the secret key. The server can run it. Scorer.decryptScores requires
  * the secret key and is run by the client.
  */
object Scorer:

  private[crypto] lazy val scoreHandle =
    NativeLibrary.handleFor(
      "ckks_score",
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
      )
    )

  /** Scores an encrypted gallery against a plaintext query.
    *
    * Replicates the query across all blocks, performs ciphertext × plaintext multiply,
    * and then rotate-and-sum to collapse each block to its dot product. The result is
    * an EncryptedScores wrapping the scored ciphertext.
    *
    * The server can run this without a secret key.
    */
  def score(ctx: CkksContext, keys: KeySet, gallery: EncryptedGallery, query: Embedding): EncryptedScores =
    val replicated = SlotPacking.replicateQuery(query)

    val local = Arena.ofConfined()
    try
      val out = local.allocate(ValueLayout.JAVA_LONG)
      val queryBuffer = local.allocate((replicated.length * 8).toLong) // 8 bytes per double
      queryBuffer.copyFrom(MemorySegment.ofArray(replicated))

      val status = scoreHandle
        .invoke(
          ctx.handle,
          keys.handle,
          gallery.handle,
          queryBuffer,
          replicated.length,
          CkksParams.BlockSize,
          out
        )
        .asInstanceOf[Int]
      NativeLibrary.check(status, "score")
      EncryptedScores(out.get(ValueLayout.JAVA_LONG, 0))
    finally local.close()

  /** Scores an encrypted gallery replicated across blocks against a batch of plaintext queries.
    *
    * Packs the queries (one per block) into plaintext, performs ciphertext × plaintext
    * multiply (where the gallery is identical in each block), and then rotate-and-sum
    * to collapse each block to its dot product. One multiply yields all scores.
    * The result is an EncryptedScores wrapping the scored ciphertext.
    *
    * This is the batched scoring mode: efficient when the same enrolled gallery vector
    * is scored against many distinct queries. The gallery should have been encrypted
    * via encryptReplicated.
    *
    * The server can run this without a secret key.
    */
  def scoreBatch(ctx: CkksContext, keys: KeySet, gallery: EncryptedGallery, queries: Seq[Embedding]): EncryptedScores =
    val packed = SlotPacking.packQueries(queries)

    val local = Arena.ofConfined()
    try
      val out = local.allocate(ValueLayout.JAVA_LONG)
      val queryBuffer = local.allocate((packed.length * 8).toLong) // 8 bytes per double
      queryBuffer.copyFrom(MemorySegment.ofArray(packed))

      val status = scoreHandle
        .invoke(
          ctx.handle,
          keys.handle,
          gallery.handle,
          queryBuffer,
          packed.length,
          CkksParams.BlockSize,
          out
        )
        .asInstanceOf[Int]
      NativeLibrary.check(status, "scoreBatch")
      EncryptedScores(out.get(ValueLayout.JAVA_LONG, 0))
    finally local.close()

  /** Decrypts scores and extracts one per block.
    *
    * Requires a key set with a secret key (client-side only).
    */
  def decryptScores(ctx: CkksContext, keys: KeySet, scores: EncryptedScores, count: Int): Array[Double] =
    val slots = Decryptor.decryptSlotsHandle(ctx, keys, scores.handle)
    SlotPacking.extractBlockSums(slots, count)
