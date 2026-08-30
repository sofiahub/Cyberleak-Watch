package ncii.eval

import ncii.vision.{Assets, FacePipeline}

import java.nio.file.Paths
import java.time.Instant

/** Run full LFW verification protocol on 6000 pairs. */
object LfwMain:

  def main(args: Array[String]): Unit =
    val startTime = Instant.now()

    println("LFW Verification Protocol")
    println("=" * 60)

    // Check assets
    if !Assets.available(Assets.lfwPairs) then
      println(s"ERROR: ${Assets.missingMessage(Assets.lfwPairs)}")
      System.exit(1)

    if !Assets.available(Assets.lfwDir) then
      println(s"ERROR: ${Assets.missingMessage(Assets.lfwDir)}")
      System.exit(1)

    println(f"Using LFW data from: ${Assets.lfwDir}")
    println(f"Using pairs file: ${Assets.lfwPairs}")
    println()

    // Parse all pairs
    println("Parsing pairs...")
    val lines = scala.io.Source.fromFile(Assets.lfwPairs.toFile).getLines().toSeq
    val allPairs = LfwProtocol.parsePairs(lines, Assets.lfwDir)
    println(f"Loaded ${allPairs.size} pairs")
    println()

    // Open pipeline
    println("Opening face pipeline...")
    val pipeline = FacePipeline.open()

    try
      // Time the scoring
      val scoreStartTime = Instant.now()
      println("Scoring pairs...")
      val (scored, skipped) = LfwProtocol.score(pipeline, allPairs)
      val scoreEndTime = Instant.now()

      val scoringDurationMs = java.time.Duration.between(scoreStartTime, scoreEndTime).toMillis
      val scoringDurationSec = scoringDurationMs / 1000.0

      println()
      println(LfwProtocol.report(scored, skipped))

      // Timing
      println()
      println("Timing:")
      val totalPairs = scored.size + skipped
      println(f"  Scoring $totalPairs pairs took $scoringDurationSec%.1f seconds")
      if scored.size > 0 then
        val timePerPair = scoringDurationSec / totalPairs
        println(f"  Average time per pair: $timePerPair%.2f seconds")

      // End-to-end timing
      val endTime = Instant.now()
      val totalDurationMs = java.time.Duration.between(startTime, endTime).toMillis
      val totalDurationSec = totalDurationMs / 1000.0
      println(f"  Total time: $totalDurationSec%.1f seconds")

    finally pipeline.close()
