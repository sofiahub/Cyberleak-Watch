package ncii.store

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID

final case class EnrolledUser(id: UUID, displayName: String)

/** Public and Galois key storage.
  *
  * There is deliberately no column and no method for a secret key. The client holds it
  * and never uploads it; a server-side secret key would defeat the whole design.
  */
object KeyMaterialRepo:

  def insertUser(user: EnrolledUser): ConnectionIO[Unit] =
    sql"""INSERT INTO enrolled_user (id, display_name)
          VALUES (${user.id}, ${user.displayName})""".update.run.map(_ => ())

  def putKeys(
      userId: UUID,
      publicKey: Array[Byte],
      galoisKeys: Array[Byte]
  ): ConnectionIO[Unit] =
    require(publicKey.nonEmpty, "public key is empty")
    require(galoisKeys.nonEmpty, "galois keys are empty")
    sql"""INSERT INTO key_material (user_id, public_key, galois_keys)
          VALUES ($userId, $publicKey, $galoisKeys)
          ON CONFLICT (user_id) DO UPDATE
            SET public_key = EXCLUDED.public_key,
                galois_keys = EXCLUDED.galois_keys""".update.run.map(_ => ())

  def getKeys(userId: UUID): ConnectionIO[Option[(Array[Byte], Array[Byte])]] =
    sql"SELECT public_key, galois_keys FROM key_material WHERE user_id = $userId"
      .query[(Array[Byte], Array[Byte])]
      .option
