package ncii.enrolment

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import ncii.core.{Embedding, GallerySelection, IdentityConsistency}
import ncii.crypto.{CkksContext, EncryptedGallery, KeySet, ShreddingVault}
import ncii.store.{AuditRecord, AuditRepo, EnrolledUser, EnrolmentOutcome, GalleryRepo, KeyMaterialRepo}
import ncii.vision.{FacePipeline, FaceSelection, Images, QualityGate, QualityVerdict}

/** The enrolment flow from the design spec, section 5.
  *
  * `decide` is the pure core: given the embeddings that survived detection and the
  * quality gate, it applies the identity-consistency gate and gallery selection, and
  * says whether the enrolment is accepted. It performs no I/O, holds no vault and
  * touches no database, so it is testable without a container or a model.
  *
  * The surrounding I/O, vault, vision, encryption, persistence and shredding, is
  * assembled in Task 8, which is where the ordering guarantees live.
  */
object EnrolmentPipeline:

  /** The spec requires at least three usable photographs. */
  val DefaultMinimumUsable: Int = 3

  /** Upper bound on the stored gallery. Sixteen 512-slot blocks fit in one ciphertext;
    * the spec asks for 3-5 so the gallery spans variation without wasting slots.
    */
  val MaximumGallery: Int = 5

  def decide(
      usable: Seq[Embedding],
      minimumUsable: Int = DefaultMinimumUsable
  ): EnrolmentResult =
    if usable.sizeIs < minimumUsable then
      EnrolmentResult.Rejected(
        EnrolmentOutcome.RejectedTooFewPhotos,
        s"${usable.size} usable photographs, at least $minimumUsable required"
      )
    else
      IdentityConsistency.check(usable) match
        case IdentityConsistency.Verdict.Mixed(outliers, minSim) =>
          // outliers == 0 is reachable and common at the minimum set size. With only
          // three legitimate photographs the centroid barely moves off an intruder, so
          // nothing is flagged against it and the set is caught by the pairwise split
          // check instead, measured, the centroid flags the intruder in 2 draws out of
          // 12 at that size. Saying "0 photographs show a different person" would be an
          // odd thing to tell someone whose enrolment was just refused, so that case
          // gets its own wording.
          val detail =
            if outliers == 0 then "the photographs do not all show the same person"
            else if outliers == 1 then "1 photograph shows a different person"
            else s"$outliers photographs show a different person"
          EnrolmentResult.Rejected(
            EnrolmentOutcome.RejectedMixedIdentity,
            f"$detail (lowest similarity $minSim%.3f)"
          )
        case IdentityConsistency.Verdict.Consistent =>
          EnrolmentResult.Accepted(GallerySelection.select(usable, MaximumGallery))

  /** Turns the vaulted uploads into embeddings, decides, and encrypts what is kept.
    *
    * Ordering is the point of this method, and it follows the spec's section 5:
    *
    *   1. every upload is encrypted into the vault before anything else touches it;
    *   2. the vision pipeline reads from the vault and decodes in memory, never from an
    *      uploaded buffer and never via a temporary file;
    *   3. the quality gate drops unusable faces;
    *   4. the consistency gate rejects mixed-identity sets;
    *   5. selection picks the most mutually distant embeddings;
    *   6. those are encrypted under the user's public key;
    *   7. the vault is shredded in a `finally`, so it happens on every path including
    *      failure, an exception must not leave readable media behind.
    *
    * Returns the decision, how many photographs yielded a usable face, and the gallery
    * ciphertext when the enrolment was accepted. The usable count is returned rather
    * than recomputed by the caller because it is what the audit record reports, and a
    * rejected enrolment leaves nothing else behind to explain itself.
    */
  private def run(
      vault: ShreddingVault,
      pipeline: FacePipeline,
      ctx: CkksContext,
      keys: KeySet,
      photos: Seq[(String, Array[Byte])]
  ): (EnrolmentResult, Int, Option[Array[Byte]]) =
    try
      photos.foreach((name, bytes) => vault.store(name, bytes.clone()))

      val embeddings = vault.names.sorted.flatMap { name =>
        val image = Images.decode(vault.read(name))
        try
          // Centre-most face, not the first detected. Taking the first cost the LFW
          // evaluation an EER of 4.93% against a published 0.2%; see FaceSelection.
          val faces = pipeline.embedMatWithGeometry(image)
          FaceSelection
            .centreMost(faces, image.cols().toFloat, image.rows().toFloat)
            .flatMap { (face, embedding) =>
              QualityGate.assess(image, face) match
                case QualityVerdict.Accepted(_) => Some(embedding)
                case QualityVerdict.Rejected(_) => None
            }
        finally image.close()
      }

      val result = decide(embeddings)

      val ciphertext = result match
        case EnrolmentResult.Accepted(selected) =>
          val gallery = EncryptedGallery.encrypt(ctx, keys, selected)
          try Some(gallery.toBytes)
          finally gallery.close()
        case EnrolmentResult.Rejected(_, _) =>
          None

      (result, embeddings.size, ciphertext)
    finally vault.shred()

  /** Runs the enrolment flow without touching the database.
    *
    * The gallery ciphertext is discarded. Use this when the decision is what matters,
    * `enrolAndStore` is the variant that persists.
    */
  def enrol(
      vault: ShreddingVault,
      pipeline: FacePipeline,
      ctx: CkksContext,
      keys: KeySet,
      user: EnrolledUser,
      photos: Seq[(String, Array[Byte])]
  ): IO[EnrolmentResult] =
    IO.blocking(run(vault, pipeline, ctx, keys, photos)._1)

  /** Runs the enrolment flow and persists the outcome in a single transaction.
    *
    * An audit record is written on every outcome, accepted or rejected. For a rejected
    * enrolment it is the only trace left, so it carries the counts and the reason,
    * and, by construction, nothing from which a face could be reconstructed.
    *
    * All the writes for one enrolment share one `transact`, so a failure part-way
    * cannot leave a user row with no gallery beside it.
    */
  def enrolAndStore(
      vault: ShreddingVault,
      pipeline: FacePipeline,
      ctx: CkksContext,
      keys: KeySet,
      xa: Transactor[IO],
      user: EnrolledUser,
      photos: Seq[(String, Array[Byte])]
  ): IO[EnrolmentResult] =
    for
      (result, usable, ciphertext) <- IO.blocking(run(vault, pipeline, ctx, keys, photos))
      writes = result match
        case EnrolmentResult.Accepted(selected) =>
          val bytes = ciphertext.getOrElse(
            throw new IllegalStateException("an accepted enrolment produced no ciphertext")
          )
          KeyMaterialRepo.insertUser(user) *>
            KeyMaterialRepo.putKeys(user.id, keys.publicKeyBytes, keys.galoisKeyBytes) *>
            GalleryRepo.putGallery(user.id, bytes, selected.size) *>
            AuditRepo.record(
              AuditRecord(user.id, photos.size, usable, EnrolmentOutcome.Accepted, None)
            )
        case EnrolmentResult.Rejected(outcome, reason) =>
          // No key material and no gallery: a rejected enrolment stores nothing derived
          // from the photographs. The user row exists so the audit record can reference it.
          KeyMaterialRepo.insertUser(user) *>
            AuditRepo.record(
              AuditRecord(user.id, photos.size, usable, outcome, Some(reason))
            )
      _ <- writes.transact(xa)
    yield result
