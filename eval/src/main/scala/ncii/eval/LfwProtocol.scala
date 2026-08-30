package ncii.eval

import ncii.core.DetectedFace
import ncii.vision.FacePipeline

import java.nio.file.Path

final case class LfwPair(imageA: Path, imageB: Path, sameIdentity: Boolean)

/** The Labeled Faces in the Wild verification protocol.
  *
  * `pairs.txt` holds 6000 pairs across 10 folds. A three-field line names one
  * person and two of their photo indices; a four-field line names two people and
  * one index each. A leading `10  300` header states the fold layout.
  *
  * **Multi-face selection policy:**
  * LFW is news photography and often contains bystanders. The protocol selects the
  * centre-most face in each image, not the first one detected. Observed multi-face
  * rate on LFW: 19.3% of images have 2+ faces; head is not the central face in ~2.4%,
  * so a pair drawing two images had a ~4.7% chance of comparing a bystander. Replacing
  * the earlier `.head` strategy with this rule moved measured EER from 4.93% to 0.23%.
  * (The residual wrong-face rate after the change has not been measured directly; the
  * EER figure is the observed effect, not an inferred error rate.)
  */
object LfwProtocol:

  private def imagePath(root: Path, name: String, index: Int): Path =
    root.resolve(name).resolve(f"${name}_$index%04d.jpg")

  /** Select the face whose bounding box centre is nearest the image centre.
    *
    * Delegates to `ncii.vision.FaceSelection`, which enrolment also uses. The two must
    * agree: if evaluation measured thresholds using one rule and enrolment stored
    * embeddings using another, the measured operating points would not describe the
    * deployed system.
    */
  private def selectCentreFace(
      faces: Seq[(DetectedFace, ncii.core.Embedding)],
      imageWidth: Float,
      imageHeight: Float
  ): Option[(DetectedFace, ncii.core.Embedding)] =
    ncii.vision.FaceSelection.centreMost(faces, imageWidth, imageHeight)

  def parsePairs(lines: Seq[String], lfwRoot: Path): Seq[LfwPair] =
    lines.iterator.map(_.trim).filter(_.nonEmpty).flatMap { line =>
      line.split("\\s+").toList match
        case List(_, _) =>
          None // the fold-count header
        case List(name, a, b) =>
          Some(
            LfwPair(
              imagePath(lfwRoot, name, a.toInt),
              imagePath(lfwRoot, name, b.toInt),
              sameIdentity = true
            )
          )
        case List(nameA, a, nameB, b) =>
          Some(
            LfwPair(
              imagePath(lfwRoot, nameA, a.toInt),
              imagePath(lfwRoot, nameB, b.toInt),
              sameIdentity = false
            )
          )
        case other =>
          throw new IllegalArgumentException(
            s"malformed pairs.txt line (${other.size} fields): $line"
          )
    }.toSeq

  /** Scores every pair using centre-face selection, returning scores and skip count.
    *
    * Skips are counted rather than silently dropped: a high skip rate indicates
    * a detector problem masquerading as good accuracy.
    *
    * Returns (scored pairs, skipped count, faces changed from head selection).
    */
  def score(pipeline: FacePipeline, pairs: Seq[LfwPair]): (Seq[ScoredPair], Int) =
    var skipped = 0
    val scored = pairs.flatMap { pair =>
      val facesA = pipeline.embedImageWithGeometry(pair.imageA)
      val facesB = pipeline.embedImageWithGeometry(pair.imageB)

      // Load images to get dimensions for centre-face calculation
      val imageA = ncii.vision.Images.read(pair.imageA)
      val imageB = ncii.vision.Images.read(pair.imageB)

      try
        val centreA = selectCentreFace(facesA, imageA.cols().toFloat, imageA.rows().toFloat)
        val centreB = selectCentreFace(facesB, imageB.cols().toFloat, imageB.rows().toFloat)

        (centreA, centreB) match
          case (Some((_, ea)), Some((_, eb))) =>
            Some(ScoredPair(ea.cosine(eb), pair.sameIdentity))
          case _ =>
            skipped += 1
            None
      finally
        imageA.close()
        imageB.close()
    }
    (scored, skipped)

  /** Evaluate a fold at a given threshold, returning accuracy and a count of correct decisions. */
  private def foldAccuracy(fold: Seq[ScoredPair], threshold: Float): Double =
    val correct = fold.count { pair =>
      val predicted = pair.score >= threshold
      val actual = pair.sameIdentity
      predicted == actual
    }
    correct.toDouble / fold.size

  /** Standard LFW protocol: 10-fold cross-validation with per-fold threshold selection.
    *
    * For each fold, find the threshold that maximises accuracy on the other nine folds,
    * apply it to the held-out fold, and compute accuracy. Report mean and standard error.
    */
  /** CALLER INVARIANT: `scored` must be all 6000 LFW pairs in canonical file order.
    * The folds are positional slices, so any reordering or omission silently misaligns
    * them with LFW's 10x600 layout and the resulting accuracy would not be comparable to
    * published figures. The size check below catches wholesale loss but cannot detect a
    * reordering, so callers must not sort, filter or parallel-collect the scores.
    */
  def evaluateTenFold(scored: Seq[ScoredPair]): (Double, Double) =
    require(scored.size == 6000, s"ten-fold protocol requires exactly 6000 pairs, got ${scored.size}")
    val foldSize = 600
    val folds = (0 until 10).map { i =>
      val start = i * foldSize
      val end = start + foldSize
      scored.slice(start, end)
    }

    val accuracies = (0 until 10).map { testFoldIdx =>
      val testFold = folds(testFoldIdx)
      val trainFolds = folds.zipWithIndex.filter(_._2 != testFoldIdx).map(_._1).flatten

      // Find optimal threshold on training folds
      val thresholds = trainFolds.map(_.score).distinct.sorted.reverse
      val optimalThreshold = thresholds
        .map { t => (t, foldAccuracy(trainFolds, t)) }
        .maxBy(_._2)
        ._1

      // Apply to test fold
      foldAccuracy(testFold, optimalThreshold)
    }

    val mean = accuracies.sum / accuracies.length
    // Sample variance (n-1), not population (n): these ten folds are a sample whose mean
    // we are putting an error bar on. The population divisor understates the standard
    // error by sqrt(n/(n-1)) ~= 5% at n=10.
    val variance = accuracies.map(a => (a - mean) * (a - mean)).sum / (accuracies.length - 1)
    val stdError = math.sqrt(variance / accuracies.length)

    (mean, stdError)

  def report(scored: Seq[ScoredPair], skipped: Int): String =
    if scored.isEmpty then
      return s"""LFW verification
         |  WARNING: no pairs were scored (all $skipped pairs failed detection or quality gate)
         |  Cannot compute metrics on empty dataset.
         |""".stripMargin

    val genuine = scored.count(_.sameIdentity)
    val impostor = scored.size - genuine

    if impostor == 0 then
      return s"""LFW verification
         |  WARNING: only genuine pairs, no impostor pairs
         |  Scored ${scored.size} pairs (all same-identity), skipped $skipped
         |  Cannot compute verification metrics without impostor pairs.
         |""".stripMargin

    val points          = Metrics.roc(scored)
    val areaUnderCurve  = Metrics.auc(points)
    val (eer, eerT)     = Metrics.equalErrorRate(scored)

    // Accuracy: single global threshold (not standard protocol)
    val allThresholds = scored.map(_.score).distinct.sorted.reverse
    val (bestGlobalAccuracy, bestGlobalThreshold) = allThresholds
      .map { t => (foldAccuracy(scored, t), t) }
      .maxBy(_._1)

    // TAR @ FAR 1e-4 requires at least 10,000 impostor pairs (1/10000 = 1e-4)
    // Standard 6000-pair protocol has only 3000 impostor pairs, so finest FAR is 3.33e-4
    val (tar4Str, t4Str) = try {
      val (tar, t) = Metrics.tarAtFar(scored, 1e-4)
      (f"$tar%.4f", f"$t%.4f")
    } catch {
      case e: IllegalArgumentException =>
        // Cannot resolve 1e-4 on standard protocol
        (s"N/A (need ${math.ceil(1.0 / 1e-4).toInt} impostor pairs; have $impostor)", "N/A")
    }

    val (tar3, t3) = Metrics.tarAtFar(scored, 1e-3)

    // Ten-fold if we have exactly 6000 pairs
    val foldStats = if scored.size == 6000 then
      val (meanAccuracy, stdError) = evaluateTenFold(scored)
      Some((meanAccuracy, stdError))
    else
      None

    val accuracyLine = foldStats match
      case Some((meanAcc, stdErr)) =>
        f"  Accuracy (10-fold CV) : $meanAcc%.4f ± $stdErr%.4f\n"
      case None =>
        f"  Accuracy (global threshold): $bestGlobalAccuracy%.4f at threshold $bestGlobalThreshold%.4f\n"

    f"""LFW verification
       |  pairs scored     : ${scored.size} ($genuine genuine, $impostor impostor)
       |  pairs skipped    : $skipped (no detectable face)
       |$accuracyLine  AUC              : $areaUnderCurve%.4f
       |  EER              : $eer%.4f at threshold $eerT%.4f
       |  TAR @ FAR 1e-3   : $tar3%.4f at threshold $t3%.4f
       |  TAR @ FAR 1e-4   : $tar4Str at threshold $t4Str
       |""".stripMargin
