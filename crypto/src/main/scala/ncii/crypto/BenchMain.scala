package ncii.crypto

import ncii.core.Embedding
import java.util.concurrent.TimeUnit

object BenchMain:

  private def unit(seed: Int): Embedding =
    val rng = new scala.util.Random(seed)
    Embedding.normalised(Array.fill(CkksParams.BlockSize)(rng.nextGaussian().toFloat))

  /** Measures elapsed time in nanoseconds */
  private inline def timeNs[T](f: => T): (T, Long) =
    val start = System.nanoTime()
    val result = f
    val elapsed = System.nanoTime() - start
    (result, elapsed)

  private case class Measurement(
    name: String,
    count: Int,
    totalNs: Long
  ):
    def avgNs: Double = totalNs.toDouble / count
    def avgMs: Double = avgNs / 1_000_000
    def avgUs: Double = avgNs / 1_000
    def perSecond: Double = 1_000_000_000.0 / avgNs

    override def toString: String =
      f"$name: $count iterations, avg ${avgUs}%.2f us (${perSecond}%.1f/s)"

  def main(args: Array[String]): Unit =
    println("=" * 80)
    println("CKKS Throughput Benchmark")
    println("=" * 80)
    println()

    val ctx = CkksContext.create()
    println(s"Context created: ${ctx.slotCount} slots")
    println(s"Block size: ${CkksParams.BlockSize}")
    println(s"Blocks per ciphertext: ${CkksParams.BlocksPerCiphertext}")
    println(s"LogN: ${CkksParams.LogN}, LogScale: ${CkksParams.LogScale}")
    println()

    try
      // Warmup
      println("Warmup (1 iteration)...")
      val warmupCtx = CkksContext.create()
      val warmupKeys = KeySet.generate(warmupCtx)
      val warmupEmbedding = unit(999)
      val warmupCt = EncryptedGallery.encrypt(warmupCtx, warmupKeys, Seq(warmupEmbedding))
      val warmupScores = Scorer.score(warmupCtx, warmupKeys, warmupCt, unit(1000))
      warmupScores.close()
      warmupCt.close()
      warmupKeys.close()
      warmupCtx.close()
      println()

      // Benchmark: key generation
      val keygenIters = 10
      println(s"Key generation (${keygenIters} iterations)...")
      val keygenMeasurements = scala.collection.mutable.Buffer[Long]()
      (1 to keygenIters).foreach { i =>
        val localCtx = CkksContext.create()
        try
          val (keys, elapsed) = timeNs { KeySet.generate(localCtx) }
          keygenMeasurements += elapsed
          keys.close()
        finally localCtx.close()
      }
      val keygenResult = Measurement("key generation", keygenIters, keygenMeasurements.sum)
      println(keygenResult)
      println()

      // Benchmark: gallery encryption (single embedding)
      val encryptIters = 10
      println(s"Gallery encryption single (${encryptIters} iterations)...")
      val encryptMeasurements = scala.collection.mutable.Buffer[Long]()
      val encryptKeys = KeySet.generate(ctx)
      try
        (1 to encryptIters).foreach { i =>
          val embedding = unit(i)
          val (ct, elapsed) = timeNs { EncryptedGallery.encrypt(ctx, encryptKeys, Seq(embedding)) }
          encryptMeasurements += elapsed
          ct.close()
        }
      finally encryptKeys.close()
      val encryptResult = Measurement("gallery encryption (1 vector)", encryptIters, encryptMeasurements.sum)
      println(encryptResult)
      println()

      // Benchmark: single score (one gallery ciphertext × one query)
      val singleScoreIters = 100
      println(s"Single score (${singleScoreIters} iterations)...")
      val singleScoreMeasurements = scala.collection.mutable.Buffer[Long]()
      val singleScoreKeys = KeySet.generate(ctx)
      val singleScoreCt = EncryptedGallery.encrypt(ctx, singleScoreKeys, Seq(unit(1)))
      try
        (1 to singleScoreIters).foreach { i =>
          val query = unit(i + 1000)
          val (scores, elapsed) = timeNs { Scorer.score(ctx, singleScoreKeys, singleScoreCt, query) }
          singleScoreMeasurements += elapsed
          scores.close()
        }
      finally
        singleScoreCt.close()
        singleScoreKeys.close()
      val singleScoreResult = Measurement("single score", singleScoreIters, singleScoreMeasurements.sum)
      println(singleScoreResult)
      println()

      // Benchmark: batched score (one gallery vector × sixteen queries)
      val batchScoreIters = 100
      println(s"Batched score (${batchScoreIters} iterations)...")
      val batchScoreMeasurements = scala.collection.mutable.Buffer[Long]()
      val batchScoreKeys = KeySet.generate(ctx)
      val batchScoreEnrolled = unit(2000)
      val batchScoreCt = EncryptedGallery.encryptReplicated(ctx, batchScoreKeys, batchScoreEnrolled)
      try
        (1 to batchScoreIters).foreach { i =>
          val queries = (0 until CkksParams.BlocksPerCiphertext).map { j =>
            unit(3000 + i * CkksParams.BlocksPerCiphertext + j)
          }.toSeq
          val (scores, elapsed) = timeNs { Scorer.scoreBatch(ctx, batchScoreKeys, batchScoreCt, queries) }
          batchScoreMeasurements += elapsed
          scores.close()
        }
      finally
        batchScoreCt.close()
        batchScoreKeys.close()
      val batchScoreResult = Measurement("batched score (16 queries)", batchScoreIters, batchScoreMeasurements.sum)
      println(batchScoreResult)
      println()

      // Per-face cost for batched scoring
      val perFaceBatchNs = batchScoreResult.avgNs / CkksParams.BlocksPerCiphertext
      val perFaceBatchUs = perFaceBatchNs / 1_000
      val perFaceBatchPerSecond = 1_000_000_000.0 / perFaceBatchNs
      println(f"Per-face cost (batched): ${perFaceBatchUs}%.2f us (${perFaceBatchPerSecond}%.1f faces/s per core)")
      println()

      // Benchmark: decryption
      val decryptIters = 100
      println(s"Decryption (${decryptIters} iterations)...")
      val decryptMeasurements = scala.collection.mutable.Buffer[Long]()
      val decryptKeys = KeySet.generate(ctx)
      val decryptCt = EncryptedGallery.encrypt(ctx, decryptKeys, Seq(unit(10)))
      try
        (1 to decryptIters).foreach { i =>
          val scores = Scorer.score(ctx, decryptKeys, decryptCt, unit(i + 5000))
          try
            val (_, elapsed) = timeNs {
              Scorer.decryptScores(ctx, decryptKeys, scores, 1)
            }
            decryptMeasurements += elapsed
          finally scores.close()
        }
      finally
        decryptCt.close()
        decryptKeys.close()
      val decryptResult = Measurement("decryption", decryptIters, decryptMeasurements.sum)
      println(decryptResult)
      println()

      // Derive ceiling: faces per second per core in both modes
      println("=" * 80)
      println("Derived Metrics")
      println("=" * 80)
      println()

      val singleScorePerSecond = singleScoreResult.perSecond
      println(f"Single-score mode: ${singleScorePerSecond}%.1f scores/s per core")
      println(f"  (${singleScoreResult.avgUs}%.2f us per score)")
      println()

      println(f"Batched-score mode: ${perFaceBatchPerSecond}%.1f faces/s per core")
      println(f"  (${perFaceBatchUs}%.2f us per face)")
      println()

      // Wall-clock cost of scoring one crawled face against N enrolled users
      val cost1k = (1000.0 / singleScorePerSecond)
      val cost10k = (10000.0 / singleScorePerSecond)
      println(f"Single-query cost (throughput ceiling):")
      println(f"  1,000 enrolled users: ${cost1k}%.1f seconds")
      println(f"  10,000 enrolled users: ${cost10k}%.1f seconds")
      println()

      // Batching packs BlocksPerCiphertext queries into ONE multiply against ONE user's
      // gallery. It does NOT amortise across users: each user's gallery is encrypted under
      // that user's own public key, so there is no ciphertext that spans users. Scoring one
      // face against N users therefore costs N multiplies in either mode, and what batching
      // buys is that each of those multiplies carries sixteen faces instead of one.
      //
      // The cost below is therefore per multiply, not per face divided by the batch size.
      // An earlier version divided by BlocksPerCiphertext here and reported 0.3 s where the
      // true figure is 64 s.
      val batchSetCost1k = 1000.0 * (batchScoreResult.avgNs / 1e9)
      val batchSetCost10k = 10000.0 * (batchScoreResult.avgNs / 1e9)
      println(f"Batched-query cost (${CkksParams.BlocksPerCiphertext} faces per multiply):")
      println(f"  1,000 enrolled users: ${batchSetCost1k}%.1f seconds for ${CkksParams.BlocksPerCiphertext} faces" +
        f" (${batchSetCost1k / CkksParams.BlocksPerCiphertext}%.1f s per face)")
      println(f"  10,000 enrolled users: ${batchSetCost10k}%.1f seconds for ${CkksParams.BlocksPerCiphertext} faces" +
        f" (${batchSetCost10k / CkksParams.BlocksPerCiphertext}%.1f s per face)")
      println(f"  NOTE: batching raises aggregate throughput; it does not reduce the latency")
      println(f"        of one face against a large enrolment, which stays at the figure above.")
      println()

      // Summary
      println("=" * 80)
      println("Summary")
      println("=" * 80)
      println()
      println(f"Single score: ${singleScoreResult.avgUs}%.2f us")
      println(f"Batched score (per face): ${perFaceBatchUs}%.2f us")
      println(f"Single-query throughput: ${singleScorePerSecond}%.1f scores/s per core")
      println(f"Batched-query throughput: ${perFaceBatchPerSecond}%.1f faces/s per core")
      println(f"Wall-clock (1,000 users): ${cost1k}%.1f s (single-query)")
      println(f"Wall-clock (10,000 users): ${cost10k}%.1f s (single-query)")
      println()

    finally ctx.close()

    println("Benchmark complete.")
