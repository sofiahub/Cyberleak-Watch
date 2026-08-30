package ncii.store

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import doobie.Transactor
import doobie.implicits.*
import org.testcontainers.utility.DockerImageName
import scala.concurrent.duration.*

/** Brings up a Postgres container for the suite and yields a migrated transactor.
  *
  * Postgres is not installed natively on the development machine, so tests own their
  * database rather than assuming one. Each suite gets a fresh container, which keeps
  * tests independent of each other's leftovers.
  */
trait PostgresSuite extends munit.FunSuite with TestContainerForAll:

  // The integration suites in this project are legitimately slow, see the note in
  // OnnxModelSuite. Container startup competes with ONNX loading and CKKS keygen.
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:17-alpine"))

  /** Blocks until the database actually answers a query.
    *
    * Testcontainers reports the container started once its port is mapped, but the
    * mapped port accepts TCP before the postmaster is serving, and under Colima the
    * port forward itself lags behind. `Transactor.fromDriverManager` opens a fresh
    * unpooled connection with no retry, so that gap surfaces as
    * "Connection to localhost:PORT refused" and fails whichever suite happened to run
    * first. It is a race, so it moved between suites from run to run: three AuditSuite
    * failures on one run, one MigrationsSuite failure on the next, and full passes in
    * between. Probing until the database answers removes the race rather than
    * re-running until the machine happens to be quiet.
    */
  private def awaitReady(xa: Transactor[IO]): IO[Unit] =
    val probe = sql"SELECT 1".query[Int].unique.transact(xa).void
    def attempt(remaining: Int): IO[Unit] =
      probe.handleErrorWith { err =>
        if remaining <= 0 then IO.raiseError(err)
        else IO.sleep(250.millis) *> attempt(remaining - 1)
      }
    attempt(120) // 30s ceiling; a container that slow has genuinely failed

  /** Runs `f` against a migrated database. */
  def withDb[A](f: Transactor[IO] => IO[A]): A =
    withContainers { pg =>
      val xa = Transactor.fromDriverManager[IO](
        driver = "org.postgresql.Driver",
        url = pg.jdbcUrl,
        user = pg.username,
        password = pg.password,
        logHandler = None
      )
      (awaitReady(xa) *> Migrations.apply[IO](xa) *> f(xa)).unsafeRunSync()
    }
