package ncii.enrolment

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import ncii.crypto.{CkksContext, KeySet, ShreddingVault}
import ncii.store.{EnrolledUser, PostgresSuite}
import ncii.vision.{Assets, FacePipeline}
import java.nio.file.{Files, Path}
import java.util.UUID
import scala.jdk.CollectionConverters.*

class EnrolmentStoreSuite extends PostgresSuite:

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

  test("enrolment with database persistence") {
    assume(Assets.available(Assets.lfwDir), Assets.missingMessage(Assets.lfwDir))
    assume(Assets.available(Assets.embedderModel), Assets.missingMessage(Assets.embedderModel))

    withDb { xa =>
      IO {
        val vaultDir = Files.createTempDirectory("ncii-enrol-store")
        val vault = ShreddingVault.open(vaultDir)
        val ctx = CkksContext.create()
        val keys = KeySet.generate(ctx)
        val pipeline = FacePipeline.open()

        try
          val user = EnrolledUser(UUID.randomUUID(), "Database Test")
          val result = EnrolmentPipeline
            .enrolAndStore(vault, pipeline, ctx, keys, xa, user, lfwPhotos(5))
            .unsafeRunSync()

          result match
            case EnrolmentResult.Accepted(selected) =>
              assert(selected.nonEmpty, "accepted with no selected vectors")
            case EnrolmentResult.Rejected(outcome, reason) =>
              fail(s"enrolment rejected: $outcome, $reason")

          // The vault must be shredded
          intercept[ShreddingVault.VaultShreddedException](vault.read("photo-0"))
        finally
          pipeline.close()
          keys.close()
          ctx.close()
          vault.close()
      }
    }
  }
