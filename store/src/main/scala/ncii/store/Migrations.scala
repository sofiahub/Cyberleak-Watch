package ncii.store

import cats.effect.Sync
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*

/** Applies the schema.
  *
  * The DDL uses IF NOT EXISTS throughout, so applying it repeatedly is safe. The
  * enrolment service runs this at startup and may restart at any time.
  */
object Migrations:

  private val ddl: String =
    scala.io.Source
      .fromInputStream(getClass.getResourceAsStream("/db/migration/V1__initial.sql"))
      .mkString

  def apply[F[_]: Sync](xa: Transactor[F]): F[Unit] =
    Fragment.const(ddl).update.run.transact(xa).void
