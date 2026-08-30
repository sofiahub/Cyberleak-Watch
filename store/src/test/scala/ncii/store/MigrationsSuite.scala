package ncii.store

import cats.effect.IO
import doobie.implicits.*

class MigrationsSuite extends PostgresSuite:

  // munit's 30-second default is not sized for this project's integration suites. They
  // load a 166 MB ONNX model, generate CKKS key sets at ~47 MB of Galois keys each, decode
  // video, or start a Postgres container, legitimately slow work that competes with
  // whatever else the machine is running. Three separate suites timed out at 31-197s while
  // asserting nothing wrong, so the limit is set to the work rather than raised one failure
  // at a time.
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  test("migration creates every table the enrolment flow needs") {
    val tables = withDb { xa =>
      sql"""SELECT table_name FROM information_schema.tables
            WHERE table_schema = 'public' ORDER BY table_name"""
        .query[String]
        .to[List]
        .transact(xa)
    }
    assertEquals(
      tables,
      List("encrypted_gallery", "enrolled_user", "enrolment_audit", "key_material")
    )
  }

  test("migration is idempotent") {
    // Applying twice must not fail: the enrolment service runs migrations at startup
    // and may be restarted at any time.
    val count = withDb { xa =>
      Migrations.apply[IO](xa) *>
        sql"SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'"
          .query[Int]
          .unique
          .transact(xa)
    }
    assertEquals(count, 4)
  }

  test("the audit table carries no column that could hold biometric data") {
    // The privacy claim in table form. A BYTEA column here would be a place an embedding
    // could be persisted by accident, so the shape is asserted rather than assumed.
    val types = withDb { xa =>
      sql"""SELECT data_type FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'enrolment_audit'"""
        .query[String]
        .to[List]
        .transact(xa)
    }
    assert(!types.contains("bytea"), s"enrolment_audit has a bytea column: $types")
  }
