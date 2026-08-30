package ncii.vision

import java.nio.file.{Files, Path}
import scala.jdk.StreamConverters.*

class FaceEmbedderSuite extends munit.FunSuite:

  // munit's 30-second default is not sized for this project's integration suites. They
  // load a 166 MB ONNX model, generate CKKS key sets at ~47 MB of Galois keys each, decode
  // video, or start a Postgres container, legitimately slow work that competes with
  // whatever else the machine is running. Three separate suites timed out at 31-197s while
  // asserting nothing wrong, so the limit is set to the work rather than raised one failure
  // at a time.
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  /** An LFW identity directory holding at least two photographs. */
  private def identityWithTwoImages: Option[(Path, Path)] =
    if !Assets.available(Assets.lfwDir) then None
    else
      Files
        .list(Assets.lfwDir)
        .toScala(LazyList)
        .filter(Files.isDirectory(_))
        .map(dir => Files.list(dir).toScala(LazyList).filter(_.toString.endsWith(".jpg")).toList)
        .collectFirst { case a :: b :: _ => (a, b) }

  private def differentIdentityImage(exclude: Path): Option[Path] =
    if !Assets.available(Assets.lfwDir) then None
    else
      Files
        .list(Assets.lfwDir)
        .toScala(LazyList)
        .filter(Files.isDirectory(_))
        .filterNot(_.getFileName == exclude.getParent.getFileName)
        .flatMap(dir => Files.list(dir).toScala(LazyList).filter(_.toString.endsWith(".jpg")))
        .headOption

  test("embeddings are 512-dimensional and unit length") {
    val pair = identityWithTwoImages
    assume(pair.isDefined, Assets.missingMessage(Assets.lfwDir))

    val pipeline = FacePipeline.open()
    try
      val embeddings = pipeline.embedImage(pair.get._1)
      assertEquals(embeddings.size, 1, "should extract one face per image")
      assertEquals(embeddings.head.dimension, 512, "embedding should be 512-dimensional")
      assert(Math.abs(embeddings.head.cosine(embeddings.head) - 1.0f) < 1e-4f, "embedding should be unit length")
    finally pipeline.close()
  }

  test("embedding the same image twice is deterministic") {
    val pair = identityWithTwoImages
    assume(pair.isDefined, Assets.missingMessage(Assets.lfwDir))

    val pipeline = FacePipeline.open()
    try
      val a = pipeline.embedImage(pair.get._1).head
      val b = pipeline.embedImage(pair.get._1).head
      assert(Math.abs(a.cosine(b) - 1.0f) < 1e-5f, "same image should produce identical embeddings")
    finally pipeline.close()
  }

  test("two photos of one person score far above two photos of different people") {
    val pair = identityWithTwoImages
    assume(pair.isDefined, Assets.missingMessage(Assets.lfwDir))
    val (imageA, imageB) = pair.get
    val other = differentIdentityImage(imageA)
    assume(other.isDefined, Assets.missingMessage(Assets.lfwDir))

    val pipeline = FacePipeline.open()
    try
      val a       = pipeline.embedImage(imageA).head
      val b       = pipeline.embedImage(imageB).head
      val stranger = pipeline.embedImage(other.get).head

      val same      = a.cosine(b)
      val different = a.cosine(stranger)

      // Deliberately loose bounds: this asserts the pipeline is wired up correctly,
      // not that accuracy is good. Task 10 measures accuracy properly.
      assert(same > 0.3f, s"same-person similarity too low: $same")
      assert(different < 0.3f, s"different-person similarity too high: $different")
      assert(same > different, s"same ($same) should exceed different ($different)")
    finally pipeline.close()
  }
