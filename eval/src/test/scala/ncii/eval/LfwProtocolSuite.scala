package ncii.eval

import ncii.vision.{Assets, FacePipeline}

import java.nio.file.Paths

class LfwProtocolSuite extends munit.FunSuite:

  // The stratified sample test opens a FacePipeline, which loads the 166 MB ArcFace
  // ONNX model, and then embeds roughly a hundred faces across fifty LFW pairs. That
  // is legitimately slow work, and munit's 30-second default was never sized for it:
  // the test passed alone at ~25s and timed out at 31.6s once the full suite ran.
  // The limit is set to the work, not to whatever makes a failure go away.
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  private val root = Paths.get("data/lfw")

  test("a three-field line is a genuine pair") {
    val pairs = LfwProtocol.parsePairs(Seq("Abel_Pacheco\t1\t4"), root)
    assertEquals(pairs.size, 1)
    assertEquals(pairs.head.sameIdentity, true)
    assertEquals(
      pairs.head.imageA,
      root.resolve("Abel_Pacheco").resolve("Abel_Pacheco_0001.jpg")
    )
    assertEquals(
      pairs.head.imageB,
      root.resolve("Abel_Pacheco").resolve("Abel_Pacheco_0004.jpg")
    )
  }

  test("a four-field line is an impostor pair") {
    val pairs = LfwProtocol.parsePairs(Seq("Abdel_Nasser\t1\tAbel_Pacheco\t2"), root)
    assertEquals(pairs.size, 1)
    assertEquals(pairs.head.sameIdentity, false)
    assertEquals(
      pairs.head.imageA,
      root.resolve("Abdel_Nasser").resolve("Abdel_Nasser_0001.jpg")
    )
    assertEquals(
      pairs.head.imageB,
      root.resolve("Abel_Pacheco").resolve("Abel_Pacheco_0002.jpg")
    )
  }

  test("the fold-count header line is ignored") {
    val pairs = LfwProtocol.parsePairs(Seq("10\t300", "Abel_Pacheco\t1\t4"), root)
    assertEquals(pairs.size, 1)
  }

  test("blank lines are ignored") {
    val pairs = LfwProtocol.parsePairs(Seq("", "  ", "Abel_Pacheco\t1\t4"), root)
    assertEquals(pairs.size, 1)
  }

  test("a malformed line fails loudly rather than being skipped") {
    intercept[IllegalArgumentException](
      LfwProtocol.parsePairs(Seq("Abel_Pacheco\t1\t4\tExtra\t9\t9"), root)
    )
  }

  test("genuine pairs score above impostor pairs on a stratified LFW sample") {
    assume(Assets.available(Assets.lfwPairs), Assets.missingMessage(Assets.lfwPairs))
    assume(Assets.available(Assets.lfwDir), Assets.missingMessage(Assets.lfwDir))

    // Each LFW fold is 300 matched pairs followed by 300 mismatched ones, so a plain
    // `.take(n)` prefix is entirely genuine and cannot distinguish anything. Sample both
    // classes explicitly.
    val lines = scala.io.Source.fromFile(Assets.lfwPairs.toFile).getLines().toSeq
    val all   = LfwProtocol.parsePairs(lines, Assets.lfwDir)
    val sample = all.take(25) ++ all.slice(300, 325)

    assert(sample.exists(_.sameIdentity), "sample has no genuine pairs")
    assert(sample.exists(!_.sameIdentity), "sample has no impostor pairs")

    val pipeline = FacePipeline.open()
    try
      val (scored, skipped) = LfwProtocol.score(pipeline, sample)
      assert(
        skipped * 4 <= sample.size,
        s"$skipped of ${sample.size} pairs were skipped. The pipeline is failing on ordinary LFW faces"
      )

      val genuine  = scored.filter(_.sameIdentity).map(_.score)
      val impostor = scored.filterNot(_.sameIdentity).map(_.score)
      assert(genuine.nonEmpty && impostor.nonEmpty, "both classes must survive scoring")

      // ArcFace separates LFW cleanly, so this is a wide margin, not a tuned one.
      val auc = Metrics.auc(Metrics.roc(scored))
      assert(auc > 0.95, s"AUC $auc on a clean LFW sample indicates a pipeline defect")
      assert(
        genuine.sum / genuine.size - impostor.sum / impostor.size > 0.3,
        s"mean genuine ${genuine.sum / genuine.size} vs mean impostor ${impostor.sum / impostor.size}"
      )
    finally pipeline.close()
  }
