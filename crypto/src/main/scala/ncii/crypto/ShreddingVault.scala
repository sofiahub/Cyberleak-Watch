package ncii.crypto

import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.SecureRandom
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import javax.crypto.{Cipher, KeyGenerator, SecretKey}
import scala.jdk.CollectionConverters.*

/** Holds uploaded media encrypted under a key that exists only in memory.
  *
  * Uploads are encrypted before they touch the filesystem, so no plaintext media is
  * ever written. When enrolment finishes, `shred()` zeroes the key and unlinks the
  * files: the ciphertext becomes permanently unreadable even to this process.
  *
  * **Why crypto-shredding rather than overwriting.** On SSDs and copy-on-write
  * filesystems, overwrite-in-place does not reliably destroy data, wear levelling
  * preserves the original blocks. Destroying the only key to an AES-GCM ciphertext is
  * defensible on hardware where overwriting is not. See the design spec, section 5.
  *
  * Not thread-safe. One vault belongs to one enrolment.
  */
final class ShreddingVault private (val directory: Path, private var key: Array[Byte])
    extends AutoCloseable:

  import ShreddingVault.*

  private val random = new SecureRandom()

  private def requireLive(): Unit =
    if key == null then
      throw new VaultShreddedException("this vault has been shredded")

  private def secretKey: SecretKey = new SecretKeySpec(key, "AES")

  /** Resolves an item name to its file, rejecting anything that is not a plain name.
    *
    * This is a plain `if`/`throw` rather than `require`, because `require` compiles away
    * under `-Xdisable-assertions` and this check is the only thing constraining a
    * caller-supplied path component.
    */
  private def pathFor(name: String): Path =
    if !name.matches("[A-Za-z0-9._-]+") then
      throw new IllegalArgumentException(s"unsafe vault item name: '$name'")
    directory.resolve(s"$name.enc")

  /** Encrypts and writes. The plaintext array is zeroed before returning, so the
    * caller's buffer does not linger in the heap.
    */
  def store(name: String, plaintext: Array[Byte]): Unit =
    requireLive()
    val iv = new Array[Byte](GcmIvBytes)
    random.nextBytes(iv)

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GcmTagBits, iv))
    val ciphertext = cipher.doFinal(plaintext)

    val out = new Array[Byte](iv.length + ciphertext.length)
    System.arraycopy(iv, 0, out, 0, iv.length)
    System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length)

    Files.write(
      pathFor(name),
      out,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE
    )

    java.util.Arrays.fill(plaintext, 0.toByte)

  def read(name: String): Array[Byte] =
    requireLive()
    val stored = Files.readAllBytes(pathFor(name))
    if stored.length <= GcmIvBytes then
      throw new IllegalArgumentException(s"vault item '$name' is truncated")

    val iv = stored.take(GcmIvBytes)
    val body = stored.drop(GcmIvBytes)

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GcmTagBits, iv))
    cipher.doFinal(body)

  def names: List[String] =
    requireLive()
    Files
      .list(directory)
      .iterator
      .asScala
      .map(_.getFileName.toString)
      .filter(_.endsWith(".enc"))
      .map(_.dropRight(4))
      .toList

  /** Zeroes the key and unlinks the vault files.
    *
    * Idempotent: shredding an already-shredded vault is a no-op, because the enrolment
    * pipeline shreds in a `finally` and may reach it twice on a failure path.
    */
  def shred(): Unit =
    if key != null then
      java.util.Arrays.fill(key, 0.toByte)
      key = null
      // Deletion is hygiene, not the security boundary. The key is already gone above,
      // so whatever remains on disk is undecryptable. Deepest-first so a subdirectory is
      // emptied before it is removed, and best-effort per entry so one undeletable file
      // cannot abort the sweep and strand the rest. The directory itself is kept: it is
      // empty, and callers may still hold a handle to it.
      if Files.isDirectory(directory) then
        val entries = Files.walk(directory).iterator.asScala.toList
          .filterNot(_ == directory)
          .sortBy(p => -p.getNameCount)
        entries.foreach { p =>
          try Files.deleteIfExists(p)
          catch case _: java.io.IOException => ()
        }

  def close(): Unit = shred()

object ShreddingVault:

  /** GCM's standard nonce length. Twelve bytes lets the cipher use the value directly
    * rather than hashing it, which is both faster and the case the security proofs cover.
    */
  private val GcmIvBytes = 12

  /** GCM's full tag length. */
  private val GcmTagBits = 128

  final class VaultShreddedException(message: String) extends RuntimeException(message)

  /** Opens a vault in `dir`, generating a fresh AES-256 key held only in memory.
    *
    * The key is never written anywhere. If the process dies, the vault contents become
    * unreadable, which is the intended failure mode, not a defect.
    *
    * The precise guarantee: the key never reaches disk, and `shred()` zeroes the array
    * this class holds. It does not zero the copies the JDK makes, `SecretKeySpec` clones
    * the array in its constructor and `Cipher` copies key material internally, which
    * linger on the heap until garbage collection. That is acceptable for the threat model
    * here (one vault, one enrolment, one process), but it is not an erasure guarantee
    * against an attacker who can read this process's memory.
    */
  def open(dir: Path): ShreddingVault =
    Files.createDirectories(dir)
    val gen = KeyGenerator.getInstance("AES")
    gen.init(256)
    new ShreddingVault(dir, gen.generateKey().getEncoded)
