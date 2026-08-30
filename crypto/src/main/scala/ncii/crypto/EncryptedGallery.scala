package ncii.crypto

import java.lang.foreign.{Arena, FunctionDescriptor, MemorySegment, ValueLayout}
import java.lang.invoke.MethodHandle

import ncii.core.Embedding

/** A CKKS ciphertext holding an encrypted gallery.
  *
  * Holds a native handle, so it must be closed. Not thread-safe.
  */
final class EncryptedGallery private (val handle: Long, val size: Int) extends AutoCloseable:

  private var closed = false

  /** Serialises the ciphertext to bytes. */
  def toBytes: Array[Byte] =
    val sizeResult = EncryptedGallery.ciphertextBytesHandle.invoke(handle, MemorySegment.NULL, 0).asInstanceOf[Int]
    // Negative return is a status code indicating an error
    if sizeResult < 0 then
      throw new NativeException(s"toBytes (size query) failed (status $sizeResult): ${NativeLibrary.lastError}")
    // Zero means the ciphertext marshalled to nothing, which is itself a failure
    if sizeResult == 0 then
      throw new NativeException(s"toBytes marshalled to zero bytes (ciphertext must be non-trivial)")

    val local = Arena.ofConfined()
    try
      val buffer = local.allocate(sizeResult.toLong)
      val written = EncryptedGallery.ciphertextBytesHandle.invoke(handle, buffer, sizeResult).asInstanceOf[Int]
      // Negative return on second call is also an error
      if written < 0 then
        throw new NativeException(s"toBytes (fetch) failed (status $written): ${NativeLibrary.lastError}")
      // Verify that we got back what we asked for
      if written != sizeResult then
        throw new NativeException(s"toBytes: requested $sizeResult bytes but got $written")
      buffer.asSlice(0, written.toLong).toArray(ValueLayout.JAVA_BYTE)
    finally local.close()

  def close(): Unit =
    if !closed then
      closed = true
      NativeLibrary.freeHandle(handle)

object EncryptedGallery:

  private[crypto] lazy val encryptHandle =
    NativeLibrary.handleFor(
      "ckks_encrypt",
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
      )
    )

  private[crypto] lazy val ciphertextBytesHandle =
    NativeLibrary.handleFor(
      "ckks_ciphertext_bytes",
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT
      )
    )

  private[crypto] lazy val ciphertextFromBytesHandle =
    NativeLibrary.handleFor(
      "ckks_ciphertext_from_bytes",
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
      )
    )

  /** Encrypts a gallery under the given key set.
    *
    * Packs the embeddings, encodes, and encrypts under the public key.
    * The returned ciphertext must be closed when finished.
    */
  def encrypt(ctx: CkksContext, keys: KeySet, embeddings: Seq[Embedding]): EncryptedGallery =
    val packed = SlotPacking.packGallery(embeddings)

    val local = Arena.ofConfined()
    try
      val out = local.allocate(ValueLayout.JAVA_LONG)
      val valuesBuffer = local.allocate((packed.length * 8).toLong) // 8 bytes per double
      valuesBuffer.copyFrom(MemorySegment.ofArray(packed))

      val status = encryptHandle
        .invoke(ctx.handle, keys.handle, valuesBuffer, packed.length, out)
        .asInstanceOf[Int]
      NativeLibrary.check(status, "encrypt")
      new EncryptedGallery(out.get(ValueLayout.JAVA_LONG, 0), embeddings.size)
    finally local.close()

  /** Encrypts one gallery vector replicated across all sixteen blocks.
    *
    * Used in batched scoring, where one enrolled vector is repeated into every block
    * so it can be scored against sixteen distinct plaintext queries in a single multiply.
    * The returned ciphertext must be closed when finished.
    */
  def encryptReplicated(ctx: CkksContext, keys: KeySet, enrolled: Embedding): EncryptedGallery =
    val replicated = SlotPacking.replicateGalleryVector(enrolled)

    val local = Arena.ofConfined()
    try
      val out = local.allocate(ValueLayout.JAVA_LONG)
      val valuesBuffer = local.allocate((replicated.length * 8).toLong) // 8 bytes per double
      valuesBuffer.copyFrom(MemorySegment.ofArray(replicated))

      val status = encryptHandle
        .invoke(ctx.handle, keys.handle, valuesBuffer, replicated.length, out)
        .asInstanceOf[Int]
      NativeLibrary.check(status, "encryptReplicated")
      new EncryptedGallery(out.get(ValueLayout.JAVA_LONG, 0), CkksParams.BlocksPerCiphertext)
    finally local.close()

  /** Deserialises a ciphertext from bytes.
    *
    * The returned ciphertext must be closed when finished.
    */
  def fromBytes(ctx: CkksContext, bytes: Array[Byte], size: Int): EncryptedGallery =
    val local = Arena.ofConfined()
    try
      val out = local.allocate(ValueLayout.JAVA_LONG)
      val bytesBuffer = local.allocate(bytes.length.toLong)
      bytesBuffer.copyFrom(MemorySegment.ofArray(bytes))

      val status = ciphertextFromBytesHandle
        .invoke(ctx.handle, bytesBuffer, bytes.length, out)
        .asInstanceOf[Int]
      NativeLibrary.check(status, "ciphertextFromBytes")
      new EncryptedGallery(out.get(ValueLayout.JAVA_LONG, 0), size)
    finally local.close()

/** Homomorphic decryption of ciphertexts. */
object Decryptor:

  private[crypto] lazy val decryptHandle =
    NativeLibrary.handleFor(
      "ckks_decrypt",
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT
      )
    )

  /** Decrypts a ciphertext handle and extracts all slot values.
    *
    * Takes a raw handle rather than an EncryptedGallery to avoid creating duplicate
    * ownership of the underlying native object.
    *
    * Throws NativeException if the key set has no secret key (server-side key sets).
    */
  def decryptSlotsHandle(ctx: CkksContext, keys: KeySet, ctHandle: Long): Array[Double] =
    val local = Arena.ofConfined()
    try
      val out = local.allocate((CkksParams.Slots * 8).toLong) // 8 bytes per double
      val status = decryptHandle
        .invoke(ctx.handle, keys.handle, ctHandle, out, CkksParams.Slots)
        .asInstanceOf[Int]
      NativeLibrary.check(status, "decrypt")
      out.asSlice(0, (CkksParams.Slots * 8).toLong).toArray(ValueLayout.JAVA_DOUBLE)
    finally local.close()

  /** Decrypts a ciphertext and extracts all slot values.
    *
    * Throws NativeException if the key set has no secret key (server-side key sets).
    */
  def decryptSlots(ctx: CkksContext, keys: KeySet, ct: EncryptedGallery): Array[Double] =
    decryptSlotsHandle(ctx, keys, ct.handle)
