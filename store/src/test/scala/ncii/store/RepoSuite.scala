package ncii.store

import cats.effect.IO
import cats.syntax.all.*
import doobie.implicits.*
import java.util.UUID

class RepoSuite extends PostgresSuite:

  private def user(): EnrolledUser = EnrolledUser(UUID.randomUUID(), "Test Subject")

  test("key material survives a round trip byte for byte") {
    // Galois keys are ~47 MB in production. A truncating column type or a driver that
    // mangles bytea would corrupt them in a way that only surfaces when scoring fails,
    // so the bytes are compared rather than the lengths.
    val u = user()
    val pk = Array.tabulate(5000)(i => (i % 251).toByte)
    val gk = Array.tabulate(9000)(i => ((i * 7) % 251).toByte)

    val got = withDb { xa =>
      KeyMaterialRepo.insertUser(u).transact(xa) *>
        KeyMaterialRepo.putKeys(u.id, pk, gk).transact(xa) *>
        KeyMaterialRepo.getKeys(u.id).transact(xa)
    }

    val (gotPk, gotGk) = got.getOrElse(fail("no key material returned"))
    assert(gotPk.sameElements(pk), "public key bytes differ after round trip")
    assert(gotGk.sameElements(gk), "galois key bytes differ after round trip")
  }

  test("a multi-megabyte value round trips intact") {
    // Production Galois keys are 47 MB. 4 MB is enough to leave the driver's default
    // buffer sizes behind without making the suite slow.
    val u = user()
    val big = Array.tabulate(4 * 1024 * 1024)(i => ((i * 31) % 256).toByte)

    val got = withDb { xa =>
      KeyMaterialRepo.insertUser(u).transact(xa) *>
        KeyMaterialRepo.putKeys(u.id, big, big).transact(xa) *>
        KeyMaterialRepo.getKeys(u.id).transact(xa)
    }
    val (gotPk, _) = got.getOrElse(fail("no key material returned"))
    assertEquals(gotPk.length, big.length)
    assert(gotPk.sameElements(big), "4 MB value differed after round trip")
  }

  test("an encrypted gallery round trips with its vector count") {
    val u = user()
    val ct = Array.tabulate(3000)(i => ((i * 13) % 251).toByte)

    val got = withDb { xa =>
      KeyMaterialRepo.insertUser(u).transact(xa) *>
        GalleryRepo.putGallery(u.id, ct, 4).transact(xa) *>
        GalleryRepo.getGallery(u.id).transact(xa)
    }
    val (gotCt, count) = got.getOrElse(fail("no gallery returned"))
    assert(gotCt.sameElements(ct), "ciphertext differed after round trip")
    assertEquals(count, 4)
  }

  test("a gallery vector count outside 1..16 is rejected by the database") {
    // Sixteen 512-slot blocks fit in one ciphertext; a count outside that means the
    // packing logic and the stored metadata disagree, which would mis-read scores later.
    val u = user()
    val ct = Array[Byte](1, 2, 3)
    intercept[org.postgresql.util.PSQLException] {
      withDb { xa =>
        KeyMaterialRepo.insertUser(u).transact(xa) *>
          GalleryRepo.putGallery(u.id, ct, 17).transact(xa)
      }
    }
  }

  test("querying an unknown user returns None rather than throwing") {
    val got = withDb(xa => KeyMaterialRepo.getKeys(UUID.randomUUID()).transact(xa))
    assertEquals(got, None)
  }
