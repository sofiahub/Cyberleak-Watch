package ncii.store

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID

enum EnrolmentOutcome:
  case Accepted, RejectedQuality, RejectedMixedIdentity, RejectedTooFewPhotos

object EnrolmentOutcome:
  def fromString(s: String): EnrolmentOutcome =
    values.find(_.toString == s).getOrElse(
      throw new IllegalArgumentException(s"unknown enrolment outcome: '$s'")
    )

  given Meta[EnrolmentOutcome] =
    Meta[String].timap(fromString)(_.toString)

/** What happened during an enrolment.
  *
  * Counts and outcomes only. This record is what survives an enrolment alongside the
  * ciphertext, and it must contain nothing from which a face could be reconstructed,
  * no embedding, no image bytes, no derived vector. See the design spec, section 5.
  */
final case class AuditRecord(
    userId: UUID,
    photosOffered: Int,
    photosUsable: Int,
    outcome: EnrolmentOutcome,
    reason: Option[String]
)

object AuditRepo:

  def record(r: AuditRecord): ConnectionIO[Unit] =
    sql"""INSERT INTO enrolment_audit
            (user_id, photos_offered, photos_usable, outcome, reason)
          VALUES (${r.userId}, ${r.photosOffered}, ${r.photosUsable},
                  ${r.outcome}, ${r.reason})""".update.run.map(_ => ())

  def forUser(userId: UUID): ConnectionIO[List[AuditRecord]] =
    sql"""SELECT user_id, photos_offered, photos_usable, outcome, reason
          FROM enrolment_audit WHERE user_id = $userId ORDER BY id"""
      .query[AuditRecord]
      .to[List]
