package ncii.crypto

import java.lang.foreign.{Arena, FunctionDescriptor, MemorySegment, ValueLayout}
import java.lang.invoke.MethodHandle

/** A CKKS key set holding the public key and Galois keys for rotations.
  *
  * For generated keys (client-side), `canDecrypt` is true and the secret key is held
  * on the native side. For server-side keys reconstructed from bytes, `canDecrypt` is
  * false and the secret key is structurally absent.
  *
  * Holds a native handle, so it must be closed. Not thread-safe.
  */
final class KeySet private (val handle: Long, val canDecrypt: Boolean) extends AutoCloseable:

  private var closed = false

  def publicKeyBytes: Array[Byte] = readBytes(KeySet.publicKeyBytesHandle, handle, "publicKeyBytes")

  def galoisKeyBytes: Array[Byte] = readBytes(KeySet.galoisKeyBytesHandle, handle, "galoisKeyBytes")

  def close(): Unit =
    if !closed then
      closed = true
      NativeLibrary.freeHandle(handle)

  private def readBytes(handle: MethodHandle, keyHandle: Long, what: String): Array[Byte] =
    val sizeResult = handle.invoke(keyHandle, MemorySegment.NULL, 0).asInstanceOf[Int]
    // Negative return is a status code indicating an error
    if sizeResult < 0 then
      throw new NativeException(s"$what (size query) failed (status $sizeResult): ${NativeLibrary.lastError}")
    // Zero means the key marshalled to nothing, which is itself a failure
    if sizeResult == 0 then
      throw new NativeException(s"$what marshalled to zero bytes (keys must be non-trivial)")

    val local = Arena.ofConfined()
    try
      val buffer = local.allocate(sizeResult.toLong)
      val written = handle.invoke(keyHandle, buffer, sizeResult).asInstanceOf[Int]
      // Negative return on second call is also an error
      if written < 0 then
        throw new NativeException(s"$what (fetch) failed (status $written): ${NativeLibrary.lastError}")
      // Verify that we got back what we asked for
      if written != sizeResult then
        throw new NativeException(s"$what: requested $sizeResult bytes but got $written")
      buffer.asSlice(0, written.toLong).toArray(ValueLayout.JAVA_BYTE)
    finally local.close()

object KeySet:

  private[crypto] lazy val keygenHandle =
    NativeLibrary.handleFor(
      "ckks_keygen",
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS
      )
    )

  private[crypto] lazy val publicKeyBytesHandle =
    NativeLibrary.handleFor(
      "ckks_public_key_bytes",
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT
      )
    )

  private[crypto] lazy val galoisKeyBytesHandle =
    NativeLibrary.handleFor(
      "ckks_galois_key_bytes",
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT
      )
    )

  private[crypto] lazy val keysetFromPublicHandle =
    NativeLibrary.handleFor(
      "ckks_keyset_from_public",
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS
      )
    )

  /** Generates a new CKKS key set for the given context.
    *
    * The returned key set holds the secret key on the native side, so `canDecrypt` is true.
    * When finished, call `close()` to release native resources.
    */
  def generate(ctx: CkksContext): KeySet =
    val local = Arena.ofConfined()
    try
      val out = local.allocate(ValueLayout.JAVA_LONG)
      val status = keygenHandle
        .invoke(ctx.handle, CkksParams.BlockSize, out)
        .asInstanceOf[Int]
      NativeLibrary.check(status, "keygen")
      new KeySet(out.get(ValueLayout.JAVA_LONG, 0), canDecrypt = true)
    finally local.close()

  /** Reconstructs a server-side key set from serialised public and Galois keys.
    *
    * The returned key set has no secret key on the native side, so `canDecrypt` is false.
    * This is the security property: server-side code is structurally unable to decrypt.
    * When finished, call `close()` to release native resources.
    */
  def serverSide(ctx: CkksContext, publicKey: Array[Byte], galoisKeys: Array[Byte]): KeySet =
    val local = Arena.ofConfined()
    try
      val out = local.allocate(ValueLayout.JAVA_LONG)
      val pkBuffer = local.allocate(publicKey.length.toLong)
      pkBuffer.copyFrom(MemorySegment.ofArray(publicKey))
      val gkBuffer = local.allocate(galoisKeys.length.toLong)
      gkBuffer.copyFrom(MemorySegment.ofArray(galoisKeys))

      val status = keysetFromPublicHandle
        .invoke(ctx.handle, pkBuffer, publicKey.length, gkBuffer, galoisKeys.length, out)
        .asInstanceOf[Int]
      NativeLibrary.check(status, "keysetFromPublic")
      new KeySet(out.get(ValueLayout.JAVA_LONG, 0), canDecrypt = false)
    finally local.close()
