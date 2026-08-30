package ncii.vision

import java.nio.file.{Files, Path, Paths}

/** Locations of models and datasets, all of which are gitignored.
  *
  * Override the roots with the `NCII_MODELS_DIR` and `NCII_DATA_DIR` environment
  * variables; otherwise they resolve relative to the working directory, which sbt
  * sets to the repository root.
  */
object Assets:

  val modelsDir: Path =
    Paths.get(sys.env.getOrElse("NCII_MODELS_DIR", "models")).toAbsolutePath

  val dataDir: Path =
    Paths.get(sys.env.getOrElse("NCII_DATA_DIR", "data")).toAbsolutePath

  val detectorModel: Path = modelsDir.resolve("face_detection_yunet_2023mar.onnx")
  val embedderModel: Path = modelsDir.resolve("w600k_r50.onnx")
  val lfwDir: Path        = dataDir.resolve("lfw")
  val lfwPairs: Path      = dataDir.resolve("pairs.txt")

  def available(p: Path): Boolean = Files.exists(p)

  /** Message shown when a test skips for want of an asset. */
  def missingMessage(p: Path): String =
    s"$p not found, run ./scripts/fetch-assets.sh"
