package ncii.eval

import ncii.vision.{Assets, FacePipeline}

/** Evaluation runner.
  *
  *   sbt "eval/run lfw"
  *   sbt "eval/run deepfake"
  *   sbt "eval/run all"
  */
object Main:

  def main(args: Array[String]): Unit =
    val command = args.headOption.getOrElse("all")
    command match
      case "lfw"      => runLfw()
      case "deepfake" => runDeepfake(lfwThreshold())
      case "all"      => runDeepfake(runLfw())
      case other =>
        System.err.println(s"unknown command: $other (expected lfw, deepfake or all)")
        sys.exit(2)

  /** Runs LFW and returns the FAR=1e-3 operating threshold.
    *
    * LFW has 3000 impostor pairs, so the finest resolvable FAR is 1/3000 ≈ 3.33e-4.
    * FAR=1e-3 is achievable and remains conservative for identity verification.
    */
  private def runLfw(): Float =
    require(
      Assets.available(Assets.lfwPairs) && Assets.available(Assets.lfwDir),
      Assets.missingMessage(Assets.lfwPairs)
    )
    val lines = scala.io.Source.fromFile(Assets.lfwPairs.toFile).getLines().toSeq
    val pairs = LfwProtocol.parsePairs(lines, Assets.lfwDir)

    val pipeline = FacePipeline.open()
    try
      val (scored, skipped) = LfwProtocol.score(pipeline, pairs)
      println(LfwProtocol.report(scored, skipped))
      Metrics.tarAtFar(scored, 1e-3)._2
    finally pipeline.close()

  private def lfwThreshold(): Float =
    println("No threshold supplied; deriving one from LFW first.")
    runLfw()

  private def runDeepfake(threshold: Float): Unit =
    val root = Assets.dataDir.resolve("deepfake")
    if !java.nio.file.Files.isDirectory(root) then
      println(
        s"No deepfake corpus at $root, see docs/evaluation/deepfake-datasets.md. Skipping."
      )
    else
      val pipeline = FacePipeline.open()
      try
        val cases = DeepfakeProtocol.discover(root)

        // Ablation switch; see docs/evaluation/improvement-experiment.md.
        // NCII_SCORING=all reports every mode from ONE scoring pass. Decoding, detecting
        // and embedding the videos dominates the runtime and is mode-independent, so
        // running the modes as separate invocations would repeat ~30 minutes of work per
        // mode to no purpose, and would risk the modes seeing different data if anything
        // about the corpus changed between runs.
        val requested = sys.env.getOrElse("NCII_SCORING", "raw")
        val modes =
          if requested.trim.equalsIgnoreCase("all") then
            Seq(ScoringMode.Raw, ScoringMode.ProbeZ, ScoringMode.GalleryZ)
          else Seq(ScoringMode.parse(requested))

        // A cached score file answers ranking-only questions (scoring modes, diagnostics)
        // without repeating ~25 minutes of video processing. It is NOT valid for
        // interventions that change how scores are produced, gallery construction,
        // aggregation, track filtering, which must re-score.
        val cachePath = Assets.dataDir.resolve("probe-scores.tsv")
        val reuse = sys.env.get("NCII_REUSE_SCORES").exists(_ == "1")

        val probes =
          if reuse && java.nio.file.Files.exists(cachePath) then
            println(s"Reusing cached probe scores from $cachePath")
            DeepfakeProtocol.readScores(cachePath)
          else
            val p = DeepfakeProtocol.collectScores(pipeline, cases)
            if p.nonEmpty then
              DeepfakeProtocol.writeScores(p, cachePath)
              println(s"Wrote probe scores to $cachePath")
            p
        if probes.isEmpty then println("No scorable videos found in the deepfake corpus.")
        else
          modes.foreach { m =>
            println(DeepfakeProtocol.report(DeepfakeProtocol.rank(probes, threshold, m), threshold, m))
          }

        // Error analysis on the baseline ranking. Diagnostic only, it reports why
        // videos fail rather than changing any decision, so it does not constitute an
        // intervention under docs/evaluation/improvement-experiment.md.
        val baseline = DeepfakeProtocol.rank(probes, threshold, ScoringMode.Raw)
        println(DeepfakeProtocol.diagnostics(baseline))
        println(DeepfakeProtocol.leakageReport(baseline))
        println(DeepfakeProtocol.cumulativeMatch(baseline))

        val mode = modes.head
        val results = DeepfakeProtocol.rank(probes, threshold, mode)
        if results.isEmpty then ()
      finally pipeline.close()
