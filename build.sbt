val scala3Version   = "3.8.4"
val munitVersion    = "1.3.5"
val munitCheckVer   = "1.3.0"
val onnxVersion     = "1.28.0"
val javacvVersion   = "1.5.14"
val javacppVersion  = "1.5.14"
val opencvVersion   = "4.14.0-1.5.14"
val ffmpegVersion   = "8.1.2-1.5.14"
val openblasVersion = "0.3.34-1.5.14"
val nativePlatform  = "macosx-arm64"
val doobieVersion   = "1.0.0-RC10"
val catsEffectVer   = "3.6.3"
val postgresVersion = "42.7.7"
val tcScalaVersion  = "0.43.0"

// Derive Docker socket path from `docker context inspect` for testcontainers.
// Falls back to the default socket if the command fails. Ryuk (testcontainers'
// cleanup container) is disabled under Colima because it cannot bind the Docker
// socket inside itself.
def dockerSocketPath(): String = {
  try {
    // Use Seq for proper command parsing instead of shell string
    val cmd = Seq("docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}")
    val output = scala.sys.process.Process(cmd).!!.trim
    // Extract the path from unix:///path/to/socket format (note: three slashes total)
    if (output.startsWith("unix://")) {
      output.substring(7) // Remove "unix://" (7 chars), leaving /path/to/socket
    } else if (output.nonEmpty) {
      output
    } else {
      "/var/run/docker.sock"
    }
  } catch {
    case _: Throwable =>
      "/var/run/docker.sock"
  }
}

def testcontainersEnv(): Map[String, String] = {
  val socketPath = dockerSocketPath()
  Map(
    "DOCKER_HOST" -> s"unix://$socketPath",
    // Ryuk fails on Colima because it tries to mount the socket inside itself.
    // Disabling it trades cleanup automation for test reliability.
    "TESTCONTAINERS_RYUK_DISABLED" -> "true"
  )
}

ThisBuild / scalaVersion  := scala3Version
ThisBuild / version       := "0.1.0-SNAPSHOT"
ThisBuild / organization  := "ncii"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-source:future")

// Every test module here is integration-heavy, ONNX and OpenCV inference, a hundred
// CKKS key generations, a Postgres container. Running them concurrently makes them
// contend for CPU and blow timeouts non-deterministically, and none of them gains
// anything from the parallelism. One at a time is slower and honest.
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)

lazy val testDeps = Seq(
  libraryDependencies ++= Seq(
    "org.scalameta" %% "munit"            % munitVersion  % Test,
    "org.scalameta" %% "munit-scalacheck" % munitCheckVer % Test
  )
)

// Native artifacts are listed twice on purpose: the plain coordinate supplies the
// Java bindings, the classifier coordinate supplies the platform's shared libraries.
lazy val nativeDeps = Seq(
  libraryDependencies ++= Seq(
    "org.bytedeco" % "javacv"   % javacvVersion,
    "org.bytedeco" % "javacpp"  % javacppVersion,
    "org.bytedeco" % "javacpp"  % javacppVersion  classifier nativePlatform,
    "org.bytedeco" % "opencv"   % opencvVersion,
    "org.bytedeco" % "opencv"   % opencvVersion   classifier nativePlatform,
    "org.bytedeco" % "ffmpeg"   % ffmpegVersion,
    "org.bytedeco" % "ffmpeg"   % ffmpegVersion   classifier nativePlatform,
    "org.bytedeco" % "openblas" % openblasVersion,
    "org.bytedeco" % "openblas" % openblasVersion classifier nativePlatform
  )
)

lazy val core = project
  .in(file("core"))
  .settings(name := "ncii-core", testDeps)

lazy val vision = project
  .in(file("vision"))
  .dependsOn(core)
  .settings(
    name := "ncii-vision",
    testDeps,
    nativeDeps,
    libraryDependencies += "com.microsoft.onnxruntime" % "onnxruntime" % onnxVersion
  )

lazy val eval = project
  .in(file("eval"))
  .dependsOn(core, vision)
  .settings(name := "ncii-eval", testDeps)

lazy val crypto = project
  .in(file("crypto"))
  .dependsOn(core)
  .settings(
    name := "ncii-crypto",
    testDeps,
    // FFM is final in JDK 22+; this module will not compile on 21.
    javacOptions ++= Seq("--release", "25"),
    Test / fork := true,
    Test / javaOptions += "--enable-native-access=ALL-UNNAMED",
    Test / envVars := Map("NCII_CKKS_LIB" -> (baseDirectory.value.getParentFile / "native" / "build" / "libncii_ckks.dylib").getAbsolutePath)
  )

lazy val store = project
  .in(file("store"))
  .dependsOn(core)
  .settings(
    name := "ncii-store",
    testDeps,
    libraryDependencies ++= Seq(
      "org.tpolecat"  %% "doobie-core"     % doobieVersion,
      "org.tpolecat"  %% "doobie-hikari"   % doobieVersion,
      "org.tpolecat"  %% "doobie-postgres" % doobieVersion,
      "org.typelevel" %% "cats-effect"     % catsEffectVer,
      "org.postgresql" % "postgresql"      % postgresVersion,
      "com.dimafeng"  %% "testcontainers-scala-munit"      % tcScalaVersion % Test,
      "com.dimafeng"  %% "testcontainers-scala-postgresql" % tcScalaVersion % Test
    ),
    Test / fork := true,
    Test / envVars ++= testcontainersEnv()
  )

lazy val enrolment = project
  .in(file("enrolment"))
  .dependsOn(core, vision, crypto, store % "compile->compile;test->test")
  .settings(
    name := "ncii-enrolment",
    testDeps,
    Test / fork := true,
    Test / javaOptions += "--enable-native-access=ALL-UNNAMED",
    Test / envVars ++= testcontainersEnv(),
    // Setting `envVars` stops the forked test JVM inheriting the ambient environment, so
    // the asset locations have to be forwarded explicitly or every suite guarded by
    // `assume(Assets.available(...))` skips and reports itself as passing. `vision` and
    // `crypto` fork without `envVars` and so never hit this; `store` sets `envVars` but
    // needs no assets, which is why it went unnoticed. Absolute paths, because a forked
    // JVM's working directory is the subproject, not the build root.
    Test / envVars ++= Map(
      "NCII_DATA_DIR" -> sys.env.getOrElse(
        "NCII_DATA_DIR",
        ((ThisBuild / baseDirectory).value / "data").getAbsolutePath
      ),
      "NCII_MODELS_DIR" -> sys.env.getOrElse(
        "NCII_MODELS_DIR",
        ((ThisBuild / baseDirectory).value / "models").getAbsolutePath
      ),
      // Same reason: NativeLibrary falls back to a path relative to the working
      // directory, which under fork is `enrolment/`, not the build root.
      "NCII_CKKS_LIB" -> sys.env.getOrElse(
        "NCII_CKKS_LIB",
        ((ThisBuild / baseDirectory).value / "native" / "build" / "libncii_ckks.dylib").getAbsolutePath
      )
    )
  )

// `eval` is a built-in sbt command (`eval <scala expr>`), so it shadows the project
// selector: `eval/run` parses as the eval command applied to `/run` and fails with
// "not found: value /". Selecting the project explicitly avoids the ambiguity.
addCommandAlias("lfwReport", ";project eval;runMain ncii.eval.LfwMain;project root")
addCommandAlias("evalReport", ";project eval;runMain ncii.eval.Main;project root")
addCommandAlias("evalTests", ";project eval;test;project root")
addCommandAlias("cryptoTests", ";project crypto;test;project root")
addCommandAlias("ckksBench", ";project crypto;runMain ncii.crypto.BenchMain;project root")

lazy val root = project
  .in(file("."))
  .aggregate(core, vision, eval, crypto, store, enrolment)
  .settings(name := "ncii", publish / skip := true)
