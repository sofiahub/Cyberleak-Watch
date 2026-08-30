package ncii.store

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID

/** Encrypted gallery storage.
  *
  * The ciphertext is opaque here by design: this module cannot decrypt it and does not
  * try. `vectorCount` records how many of the sixteen 512-slot blocks carry a real
  * embedding, so a reader knows how many scores to extract without decrypting anything.
  */
object GalleryRepo:

  def putGallery(
      userId: UUID,
      ciphertext: Array[Byte],
      vectorCount: Int
  ): ConnectionIO[Unit] =
    require(ciphertext.nonEmpty, "ciphertext is empty")
    sql"""INSERT INTO encrypted_gallery (user_id, ciphertext, vector_count)
          VALUES ($userId, $ciphertext, $vectorCount)
          ON CONFLICT (user_id) DO UPDATE
            SET ciphertext = EXCLUDED.ciphertext,
                vector_count = EXCLUDED.vector_count""".update.run.map(_ => ())

  def getGallery(userId: UUID): ConnectionIO[Option[(Array[Byte], Int)]] =
    sql"SELECT ciphertext, vector_count FROM encrypted_gallery WHERE user_id = $userId"
      .query[(Array[Byte], Int)]
      .option
