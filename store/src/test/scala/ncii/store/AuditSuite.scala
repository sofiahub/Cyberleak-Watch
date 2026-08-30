package ncii.store

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID

class AuditSuite extends PostgresSuite:

  test("an audit record round trips with its outcome and reason") {
    val u = EnrolledUser(UUID.randomUUID(), "Audited Subject")
    val rec = AuditRecord(u.id, 8, 5, EnrolmentOutcome.Accepted, None)

    val got = withDb { xa =>
      KeyMaterialRepo.insertUser(u).transact(xa) *>
        AuditRepo.record(rec).transact(xa) *>
        AuditRepo.forUser(u.id).transact(xa)
    }
    assertEquals(got.size, 1)
    assertEquals(got.head.photosOffered, 8)
    assertEquals(got.head.photosUsable, 5)
    assertEquals(got.head.outcome, EnrolmentOutcome.Accepted)
    assertEquals(got.head.reason, None)
  }

  test("a rejection records its reason") {
    val u = EnrolledUser(UUID.randomUUID(), "Rejected Subject")
    val rec = AuditRecord(
      u.id, 6, 2, EnrolmentOutcome.RejectedMixedIdentity,
      Some("two distinct identities present")
    )

    val got = withDb { xa =>
      KeyMaterialRepo.insertUser(u).transact(xa) *>
        AuditRepo.record(rec).transact(xa) *>
        AuditRepo.forUser(u.id).transact(xa)
    }
    assertEquals(got.head.outcome, EnrolmentOutcome.RejectedMixedIdentity)
    assertEquals(got.head.reason, Some("two distinct identities present"))
  }

  test("every outcome is stored under its exact name") {
    // Outcome is a TEXT column. Reading it back through EnrolmentOutcome would use the same
    // enum on both sides, so a rename would change write and read together and prove
    // nothing. Asserting the raw strings pins the wire format: renaming a case fails here
    // until the stored data is migrated too.
    val u   = EnrolledUser(UUID.randomUUID(), "All Outcomes")
    val all = EnrolmentOutcome.values.toList

    val raw = withDb { xa =>
      KeyMaterialRepo.insertUser(u).transact(xa) *>
        all.traverse_(o => AuditRepo.record(AuditRecord(u.id, 1, 1, o, None)).transact(xa)) *>
        sql"SELECT outcome FROM enrolment_audit WHERE user_id = ${u.id} ORDER BY id"
          .query[String]
          .to[List]
          .transact(xa)
    }

    assertEquals(raw.size, 4)
    assertEquals(
      raw.toSet,
      Set("Accepted", "RejectedQuality", "RejectedMixedIdentity", "RejectedTooFewPhotos")
    )
  }

  test("fromString throws on unknown outcome") {
    // fromString is called when reading rows written by a newer version that has new
    // outcomes. The exception should name the offending value for diagnostics.
    val ex = intercept[IllegalArgumentException](
      EnrolmentOutcome.fromString("UnknownFutureOutcome")
    )
    assert(ex.getMessage.contains("UnknownFutureOutcome"))
  }
