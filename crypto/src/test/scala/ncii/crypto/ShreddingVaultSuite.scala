package ncii.crypto

import java.nio.file.{Files, Path, StandardCopyOption}
import scala.jdk.CollectionConverters.*

class ShreddingVaultSuite extends munit.FunSuite:

  private def tempDir(): Path = Files.createTempDirectory("ncii-vault-test")

  private def bytesOf(p: Path): Array[Byte] = Files.readAllBytes(p)

  /** Every byte written anywhere under `dir`, concatenated. */
  private def allBytesUnder(dir: Path): Array[Byte] =
    Files.walk(dir).iterator.asScala
      .filter(Files.isRegularFile(_))
      .flatMap(p => bytesOf(p).iterator)
      .toArray

  test("a stored item reads back byte for byte while the key lives") {
    val dir = tempDir()
    val vault = ShreddingVault.open(dir)
    try
      val plaintext = "a photograph's bytes".getBytes("UTF-8")
      val expected  = plaintext.clone()
      vault.store("photo-1", plaintext)
      assert(vault.read("photo-1").sameElements(expected))
      assert(plaintext.forall(_ == 0), "store must zero the caller's buffer")
    finally vault.close()
  }

  test("plaintext never appears on disk") {
    // The privacy claim, part one. The vault writes ciphertext; a marker string present
    // in the plaintext must not be findable anywhere under the vault directory.
    val dir = tempDir()
    val vault = ShreddingVault.open(dir)
    try
      val marker = "UNIQUE-PLAINTEXT-MARKER-4f2a9c"
      vault.store("photo-1", marker.getBytes("UTF-8"))

      val onDisk = new String(allBytesUnder(dir), "ISO-8859-1")
      assert(!onDisk.contains(marker), "plaintext marker was found on disk")
    finally vault.close()
  }

  test("shredding makes every stored item permanently unreadable") {
    // The privacy claim, part two. After the key is zeroed the ciphertext is still on
    // disk but no longer decryptable, which is the whole point of crypto-shredding on
    // storage where overwriting cannot be trusted.
    val dir = tempDir()
    val vault = ShreddingVault.open(dir)
    try
      vault.store("photo-1", "some bytes".getBytes("UTF-8"))
      assert(vault.read("photo-1").nonEmpty)

      vault.shred()

      intercept[ShreddingVault.VaultShreddedException](vault.read("photo-1"))
    finally vault.close()
  }

  test("shredding removes the vault files as well as the key") {
    val dir = tempDir()
    val vault = ShreddingVault.open(dir)
    try
      vault.store("photo-1", "some bytes".getBytes("UTF-8"))
      vault.store("photo-2", "other bytes".getBytes("UTF-8"))
      assertEquals(vault.names.sorted, List("photo-1", "photo-2"))

      vault.shred()

      val remaining = Files.walk(dir).iterator.asScala.filter(Files.isRegularFile(_)).toList
      assertEquals(remaining, Nil, s"vault files survived shredding: $remaining")
    finally vault.close()
  }

  test("storing after shredding fails rather than silently starting a new vault") {
    val dir = tempDir()
    val vault = ShreddingVault.open(dir)
    try
      vault.shred()
      intercept[ShreddingVault.VaultShreddedException](
        vault.store("photo-1", "bytes".getBytes("UTF-8"))
      )
    finally vault.close()
  }

  test("one vault cannot decrypt another's ciphertext") {
    // Each enrolment gets its own key, so shredding one vault must not leave another's
    // data readable. A random IV alone would make the ciphertexts differ even under a
    // shared key, so comparing bytes proves nothing; moving a file between vaults and
    // failing to decrypt it is what actually pins per-vault keys.
    val a = ShreddingVault.open(tempDir())
    val b = ShreddingVault.open(tempDir())
    try
      a.store("x", "identical bytes".getBytes("UTF-8"))
      Files.copy(
        a.directory.resolve("x.enc"),
        b.directory.resolve("x.enc"),
        StandardCopyOption.REPLACE_EXISTING
      )
      intercept[javax.crypto.AEADBadTagException](b.read("x"))
    finally
      a.close()
      b.close()
  }

  test("an unsafe item name is rejected rather than resolved") {
    // pathFor is the only thing standing between a caller-supplied string and a resolved
    // path, so its rejection must be real code rather than an elidable assertion.
    val vault = ShreddingVault.open(tempDir())
    try
      intercept[IllegalArgumentException](
        vault.store("../escape", "bytes".getBytes("UTF-8"))
      )
    finally vault.close()
  }

  test("shredding clears the vault even when it holds a subdirectory") {
    // Deleting a non-empty directory throws, and shred() zeroes the key before it starts
    // deleting. A sweep that aborted partway would leave files behind with no key left to
    // report the failure against, so the sweep must tolerate directories.
    val dir   = tempDir()
    val vault = ShreddingVault.open(dir)
    try
      vault.store("photo-1", "some bytes".getBytes("UTF-8"))
      val sub = Files.createDirectories(dir.resolve("nested"))
      Files.write(sub.resolve("stray.tmp"), "stray".getBytes("UTF-8"))

      vault.shred()

      val remaining = Files.walk(dir).iterator.asScala.filter(Files.isRegularFile(_)).toList
      assertEquals(remaining, Nil, s"files survived shredding: $remaining")
    finally vault.close()
  }
