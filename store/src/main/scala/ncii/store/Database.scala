package ncii.store

import cats.effect.{Async, Resource}
import doobie.Transactor
import doobie.hikari.HikariTransactor

final case class DbConfig(url: String, user: String, password: String)

object Database:

  /** A pooled transactor. The pool is small on purpose: enrolment is not a high-rate
    * path, and each connection may carry a ~47 MB Galois key write.
    */
  def transactor[F[_]: Async](config: DbConfig): Resource[F, Transactor[F]] =
    for
      pool <- doobie.util.ExecutionContexts.fixedThreadPool[F](4)
      xa <- HikariTransactor.newHikariTransactor[F](
        driverClassName = "org.postgresql.Driver",
        url = config.url,
        user = config.user,
        pass = config.password,
        connectEC = pool
      )
    yield xa
