package ncii.enrolment

import cats.effect.unsafe.implicits.global
import ncii.crypto.{CkksContext, KeySet, ShreddingVault}
import ncii.store.EnrolledUser
import ncii.vision.{Assets, FacePipeline}
import java.nio.file.Files
import java.util.UUID
import scala.jdk.CollectionConverters.*

class EnrolmentEndToEndSuite extends munit.FunSuite:

  // Generating a CKKS key set costs about 47 MB of Galois keys and tens of seconds, and
  // opening the embedder loads a 166 MB ONNX model. Both are read-only here, so the suite
  // builds them once rather than once per test.
  override val munitTimeout = scala.concurrent.duration.Duration(20, "min")

  private lazy val ctx      = CkksContext.create()
  private lazy val keys     = KeySet.generate(ctx)
  private lazy val pipeline = FacePipeline.open()

  override def afterAll(): Unit =
    pipeline.close()
    keys.close()
    ctx.close()
    super.afterAll()

  private def lfwPhotos(n: Int): Seq[(String, Array[Byte])] =
    val dir = Files.list(Assets.lfwDir).iterator.asScala
      .filter(Files.isDirectory(_))
      .find(d => Files.list(d).count() >= n)
      .getOrElse(fail("no LFW identity with enough photographs"))
    Files.list(dir).iterator.asScala
      .filter(_.toString.endsWith(".jpg"))
      .take(n)
      .zipWithIndex
      .map((p, i) => (s"photo-$i", Files.readAllBytes(p)))
      .toSeq

  /** Every byte of every file remaining under `dir`, concatenated. */
  private def bytesRemainingUnder(dir: java.nio.file.Path): Array[Byte] =
    Files.walk(dir).iterator.asScala
      .filter(Files.isRegularFile(_))
      .flatMap(p => Files.readAllBytes(p).iterator)
      .toArray

  test("a real enrolment leaves no plaintext media behind") {
    // THIS TEST IS THE PRIVACY CLAIM, and it must run the actual pipeline to be worth
    // anything. An earlier version stored and shredded a vault by hand without calling
    // enrol at all, which only retested ShreddingVault: the temp-file decode this task
    // had to remove would have passed it untouched, because the pipeline never ran.
    assume(Assets.available(Assets.lfwDir), Assets.missingMessage(Assets.lfwDir))
    assume(Assets.available(Assets.embedderModel), Assets.missingMessage(Assets.embedderModel))

    val vaultDir = Files.createTempDirectory("ncii-enrol-privacy")
    val photos   = lfwPhotos(5)
    assert(photos.nonEmpty, "no photographs loaded")

    // Sanity: the inputs really are JPEGs, so the absence assertions below mean something.
    assert(
      photos.head._2(0) == 0xff.toByte && photos.head._2(1) == 0xd8.toByte,
      "test inputs are not JPEGs; the absence assertion would be vacuous"
    )
    val firstPhoto = photos.head._2.clone()

    val vault = ShreddingVault.open(vaultDir)
    try
      val user = EnrolledUser(UUID.randomUUID(), "Privacy Claim")
      EnrolmentPipeline.enrol(vault, pipeline, ctx, keys, user, photos).unsafeRunSync()

      val remaining = bytesRemainingUnder(vaultDir)

      // Nothing at all should survive, so the two checks below are belt and braces,
      // but they are what fails loudly if shredding ever regresses to leaving ciphertext
      // in place, or if a decode path starts writing images out again.
      assertEquals(
        Files.walk(vaultDir).iterator.asScala.filter(Files.isRegularFile(_)).toList,
        Nil
      )

      val asLatin1 = new String(remaining, "ISO-8859-1")
      assert(!asLatin1.contains("ÿØÿ"), "a JPEG header survived enrolment")

      val needle = new String(firstPhoto.take(64), "ISO-8859-1")
      assert(!asLatin1.contains(needle), "the first photograph's bytes survived enrolment")

      // And the vault itself must refuse to serve anything more.
      intercept[ShreddingVault.VaultShreddedException](vault.read("photo-0"))
    finally vault.close()
  }

  test("a full enrolment is accepted and leaves the vault shredded") {
    assume(Assets.available(Assets.lfwDir), Assets.missingMessage(Assets.lfwDir))
    assume(Assets.available(Assets.embedderModel), Assets.missingMessage(Assets.embedderModel))

    val vaultDir = Files.createTempDirectory("ncii-enrol-full")
    val vault    = ShreddingVault.open(vaultDir)
    try
      val user = EnrolledUser(UUID.randomUUID(), "End To End")
      val result =
        EnrolmentPipeline.enrol(vault, pipeline, ctx, keys, user, lfwPhotos(5)).unsafeRunSync()

      result match
        case EnrolmentResult.Accepted(selected) =>
          assert(selected.nonEmpty, "accepted with no selected vectors")
          assert(selected.sizeIs <= 5, s"stored more than the gallery bound: ${selected.size}")
        case EnrolmentResult.Rejected(outcome, reason) =>
          fail(s"enrolment rejected: $outcome, $reason")

      intercept[ShreddingVault.VaultShreddedException](vault.read("photo-0"))
    finally vault.close()
  }

  test("the vault is shredded even when decoding fails part-way") {
    // The shred lives in a `finally`. A `finally` only ever exercised on the happy path
    // is untested, and this is the path where readable media would otherwise be left on
    // disk after a crash.
    assume(Assets.available(Assets.embedderModel), Assets.missingMessage(Assets.embedderModel))

    val vaultDir = Files.createTempDirectory("ncii-enrol-fail")
    val vault    = ShreddingVault.open(vaultDir)
    try
      val user   = EnrolledUser(UUID.randomUUID(), "Failure Test")
      val photos = Seq(("invalid", Array[Byte](0x00, 0x01, 0x02, 0x03)))

      intercept[IllegalArgumentException] {
        EnrolmentPipeline.enrol(vault, pipeline, ctx, keys, user, photos).unsafeRunSync()
      }

      intercept[ShreddingVault.VaultShreddedException](vault.read("invalid"))
      assertEquals(
        Files.walk(vaultDir).iterator.asScala.filter(Files.isRegularFile(_)).toList,
        Nil,
        "media survived a failed enrolment"
      )
    finally vault.close()
  }
