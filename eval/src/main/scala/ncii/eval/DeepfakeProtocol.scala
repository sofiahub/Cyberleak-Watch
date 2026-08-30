package ncii.eval

import ncii.core.Embedding
import ncii.vision.{FacePipeline, FaceTracker, VideoSampler}

import java.nio.file.{Files, Path}
import scala.jdk.StreamConverters.*

final case class IdentityCase(
    name: String,
    galleryImages: Seq[Path],
    manipulatedVideos: Seq[Path]
)

final case class DeepfakeResult(
    identity: String,
    video: Path,
    ownIdentityScore: Float,
    bestOtherIdentityScore: Float,
    bestOtherIdentity: String,
    rank1Correct: Boolean,
    /** 1-based position of the true identity in the ranking. Recorded because the true
      * identity is usually displaced by exactly one competitor, the target whose footage
      * was manipulated, so rank-1 alone understates what the system knows. See
      * docs/evaluation/target-leakage.md.
      */
    ownRank: Int,
    /** How many identities were ranked, so a rank is interpretable. */
    candidateCount: Int,
    matched: Boolean
)

/** How gallery scores are compared when ranking identities.
  *
  * Raw cosines are not comparable across galleries: some identities score high against
  * everything ("hubs"), which costs rank-1 without affecting whether a video clears the
  * detection threshold. Normalising re-expresses each score relative to a distribution.
  *
  * The threshold decision always uses the RAW own-identity cosine regardless of mode, so
  * changing the mode cannot move the match rate. See
  * docs/evaluation/improvement-experiment.md (E1).
  */
enum ScoringMode:
  /** Rank on the raw cosine. The baseline. */
  case Raw

  /** Per probe: z-score each of its gallery scores against that probe's own distribution.
    * Targets probes that score diffusely across every gallery.
    */
  case ProbeZ

  /** Per gallery: z-score each gallery's scores against that gallery's distribution over
    * all probes. Targets hub identities that score high against everything.
    */
  case GalleryZ

object ScoringMode:
  def parse(s: String): ScoringMode = s.trim.toLowerCase match
    case "raw"                  => Raw
    case "probe-z" | "probez"   => ProbeZ
    case "gallery-z" | "galleryz" => GalleryZ
    case other =>
      throw new IllegalArgumentException(
        s"unknown scoring mode '$other'; expected raw, probe-z or gallery-z"
      )

/** One probe video's raw cosine against every enrolled gallery. */
final case class ProbeScores(
    identity: String,
    video: Path,
    scores: Seq[(String, Float)]
)

/** Measures whether manipulated video still matches the source identity's real photos.
  *
  * This is the experiment the project's premise rests on. ArcFace was trained to
  * match real faces to real faces; whether identity survives a face swap is an
  * open empirical question, and the answer varies by generator.
  */
object DeepfakeProtocol:

  private def listFiles(dir: Path, extensions: Set[String]): Seq[Path] =
    if !Files.isDirectory(dir) then Seq.empty
    else
      val stream = Files.list(dir)
      try
        stream
          .toScala(LazyList)
          .filter(p => extensions.exists(e => p.toString.toLowerCase.endsWith(e)))
          .sortBy(_.toString)
          .toSeq
      finally stream.close()

  /** Finds identity cases under `root`. An identity needs both a gallery and at
    * least one manipulated video; incomplete directories are skipped.
    */
  def discover(root: Path): Seq[IdentityCase] =
    require(Files.isDirectory(root), s"deepfake corpus not found: $root")
    val stream = Files.list(root)
    try
      stream
        .toScala(LazyList)
        .filter(Files.isDirectory(_))
        .map { dir =>
          IdentityCase(
            name              = dir.getFileName.toString,
            galleryImages     = listFiles(dir.resolve("gallery"), Set(".jpg", ".jpeg", ".png")),
            manipulatedVideos = listFiles(dir.resolve("fake"), Set(".mp4", ".avi", ".mov"))
          )
        }
        .filter(c => c.galleryImages.nonEmpty && c.manipulatedVideos.nonEmpty)
        .sortBy(_.name)
        .toSeq
    finally stream.close()

  /** Extract track embeddings from a video by sampling, detecting, embedding, and tracking faces.
    *
    * This is the expensive part: it decodes the video, samples frames at 0.5s intervals,
    * detects faces, computes embeddings, and builds tracks. Compute this once per video,
    * then score the tracks against multiple galleries.
    */
  def trackEmbeddings(pipeline: FacePipeline, video: Path): Seq[Embedding] =
    var frames = List.empty[(Double, Seq[(ncii.core.DetectedFace, Embedding)])]
    VideoSampler.sample(video, intervalSeconds = 0.5) { frame =>
      val detections = pipeline.detectAndEmbed(frame.image)
      if detections.nonEmpty then frames = (frame.timestampSeconds, detections) :: frames
    }

    val tracks = FaceTracker.track(frames.reverse)
    tracks.map(_.meanEmbedding)

  /** Score track embeddings against a gallery.
    *
    * Pure arithmetic: returns the maximum cosine similarity between any track embedding
    * and any gallery embedding. This is the cheap part and can be called multiple times
    * with different galleries.
    */
  def scoreTracks(tracks: Seq[Embedding], gallery: Seq[Embedding]): Option[Float] =
    require(gallery.nonEmpty, "cannot score tracks against an empty gallery")
    if tracks.isEmpty then None
    else
      val scores = tracks.map { track =>
        gallery.map(track.cosine).max
      }
      scores.maxOption

  /** Best similarity between any track in `video` and any gallery vector.
    *
    * Deprecated: use trackEmbeddings and scoreTracks instead to avoid reprocessing the video.
    * This method is kept for backward compatibility but internally uses the decomposed functions.
    */
  def scoreVideo(
      pipeline: FacePipeline,
      gallery: Seq[Embedding],
      video: Path
  ): Option[Float] =
    scoreTracks(trackEmbeddings(pipeline, video), gallery)

  /** Phase 1: every probe's raw cosine against every gallery.
    *
    * Separated from ranking because GalleryZ needs statistics across all probes, which
    * cannot be computed while streaming one video at a time.
    */
  def collectScores(
      pipeline: FacePipeline,
      cases: Seq[IdentityCase]
  ): Seq[ProbeScores] =
    // Gallery images must be real photographs of each identity, never frames lifted
    // from manipulated videos. Scoring against frames extracted from fakes would measure
    // whether the system can match a video to itself, not whether identity survives a swap.
    val allGalleries = cases.map { identityCase =>
      identityCase.name -> identityCase.galleryImages.flatMap(pipeline.embedImage)
    }

    val skipped = allGalleries.collect { case (name, gallery) if gallery.isEmpty => name }.toSet
    skipped.foreach(name => println(s"warning: no usable gallery faces for $name, skipping"))

    val usable = allGalleries.filterNot((name, _) => skipped.contains(name))

    cases.filterNot(c => skipped.contains(c.name)).flatMap { identityCase =>
      identityCase.manipulatedVideos.map { video =>
        // Expensive: decode, sample, detect, embed, track. Once per video, never per gallery.
        val trackEmbeds = trackEmbeddings(pipeline, video)
        val scores = usable.map { case (galleryIdentity, gallery) =>
          galleryIdentity -> scoreTracks(trackEmbeds, gallery).getOrElse(Float.NegativeInfinity)
        }
        ProbeScores(identityCase.name, video, scores)
      }
    }

  private def mean(xs: Seq[Float]): Double =
    if xs.isEmpty then 0.0 else xs.map(_.toDouble).sum / xs.size

  private def stdDev(xs: Seq[Float]): Double =
    if xs.size < 2 then 0.0
    else
      val m = mean(xs)
      math.sqrt(xs.map(x => (x - m) * (x - m)).sum / (xs.size - 1))

  /** Phase 2: rank identities under `mode` and derive results.
    *
    * `matched` is always decided on the RAW own-identity cosine, so the mode can change
    * ranking without touching the detection guardrail. The reported own/other scores stay
    * raw too, so the margin remains comparable across modes; only rank-1 varies.
    */
  def rank(
      probes: Seq[ProbeScores],
      threshold: Float,
      mode: ScoringMode
  ): Seq[DeepfakeResult] =
    // For GalleryZ, each gallery's distribution is taken over all probes.
    val galleryStats: Map[String, (Double, Double)] =
      if mode != ScoringMode.GalleryZ then Map.empty
      else
        probes
          .flatMap(_.scores)
          .groupBy(_._1)
          .view
          .mapValues { pairs =>
            val vs = pairs.map(_._2).filter(_.isFinite)
            (mean(vs), stdDev(vs))
          }
          .toMap

    probes.map { probe =>
      val raw = probe.scores

      // A zero standard deviation means the distribution carries no information, so
      // normalisation is skipped rather than dividing by zero.
      val ranked: Seq[(String, Double)] = mode match
        case ScoringMode.Raw =>
          raw.map((id, s) => id -> s.toDouble)

        case ScoringMode.ProbeZ =>
          val vs = raw.map(_._2).filter(_.isFinite)
          val m  = mean(vs)
          val sd = stdDev(vs)
          if sd == 0.0 then raw.map((id, s) => id -> s.toDouble)
          else raw.map((id, s) => id -> (s - m) / sd)

        case ScoringMode.GalleryZ =>
          raw.map { (id, s) =>
            galleryStats.get(id) match
              case Some((m, sd)) if sd != 0.0 => id -> (s - m) / sd
              case _                          => id -> s.toDouble
          }

      val ordered      = ranked.sortBy(-_._2).map(_._1)
      val bestIdentity = ordered.head
      val ownPosition  = ordered.indexOf(probe.identity) + 1  // 0 if absent -> reported as 0

      val ownScore = raw.find(_._1 == probe.identity).map(_._2).getOrElse(Float.NegativeInfinity)
      val others   = raw.filter(_._1 != probe.identity)
      val bestOtherScore = others.map(_._2).maxOption.getOrElse(Float.NegativeInfinity)
      val bestOtherIdentity = others.sortBy(-_._2).headOption.map(_._1).getOrElse("N/A")

      DeepfakeResult(
        identity               = probe.identity,
        video                  = probe.video,
        ownIdentityScore       = ownScore,
        bestOtherIdentityScore = bestOtherScore,
        bestOtherIdentity      = bestOtherIdentity,
        rank1Correct           = bestIdentity == probe.identity,
        ownRank                = ownPosition,
        candidateCount         = ordered.size,
        matched                = ownScore >= threshold
      )
    }

  def run(
      pipeline: FacePipeline,
      cases: Seq[IdentityCase],
      threshold: Float,
      mode: ScoringMode = ScoringMode.Raw
  ): Seq[DeepfakeResult] =
    rank(collectScores(pipeline, cases), threshold, mode)

  def report(results: Seq[DeepfakeResult], threshold: Float, mode: ScoringMode = ScoringMode.Raw): String =
    require(results.nonEmpty, "cannot report on no results")

    val matchRate = results.count(_.matched).toDouble / results.size
    val rank1Rate = results.count(_.rank1Correct).toDouble / results.size

    val perIdentity = results
      .groupBy(_.identity)
      .toSeq
      .sortBy(_._1)
      .map { case (identity, rows) =>
        val rate = rows.count(_.matched).toDouble / rows.size
        val rank1 = rows.count(_.rank1Correct).toDouble / rows.size
        val meanOwn = rows.map(_.ownIdentityScore).sum / rows.size
        val otherScores = rows.map(_.bestOtherIdentityScore).filter(_.isFinite)
        val meanOtherStr = if otherScores.isEmpty then
          "N/A (single identity)"
        else
          f"${otherScores.sum / otherScores.size}%.4f"
        f"    $identity%-24s ${rows.size}%4d videos  match $rate%.4f  rank-1 $rank1%.4f  own $meanOwn%.4f  other $meanOtherStr"
      }
      .mkString("\n")

    f"""Deepfake match-rate experiment
       |  scoring mode             : $mode
       |  threshold (LFW FAR 1e-3) : $threshold%.4f
       |  videos scored            : ${results.size}
       |  own-identity match rate  : $matchRate%.4f (score >= threshold)
       |  rank-1 identification    : $rank1Rate%.4f (own identity ranks highest)
       |  per identity:
       |$perIdentity
       |""".stripMargin

  /** Error analysis over a scored dev set.
    *
    * The per-identity summary says which identities fail but not why. These diagnostics
    * test mechanisms, so that later interventions are aimed rather than guessed.
    *
    * The headline question is target leakage. Every Celeb-DF fake is id{A}_id{B}_{NNNN}:
    * A's footage carrying B's face. When a fake of B is ranked as somebody else, is that
    * somebody disproportionately A? A swap replaces the face but not the head shape,
    * jawline, hairline or neck, so residual signal from the target is a plausible cause of
    * the compressed margin, and one that no amount of gallery work would fix, because it
    * originates in the generator rather than in our enrolment data.
    */
  def diagnostics(results: Seq[DeepfakeResult]): String =
    require(results.nonEmpty, "cannot diagnose no results")

    /** The target identity, the first id in id{A}_id{B}_{NNNN}.mp4, whose video was
      * manipulated. See docs/evaluation/deepfake-datasets.md for how the direction of this
      * convention was established.
      */
    def targetOf(video: Path): Option[String] =
      val base = video.getFileName.toString.stripSuffix(".mp4")
      base.split("_").toList match
        case a :: _ :: _ :: Nil => Some(a)
        case _                  => None

    val withTarget = results.flatMap(r => targetOf(r.video).map(t => (r, t)))
    val misranked  = withTarget.filterNot(_._1.rank1Correct)

    val confusedWithTarget = misranked.count { case (r, t) => r.bestOtherIdentity == t }
    val targetShare =
      if misranked.isEmpty then 0.0 else confusedWithTarget.toDouble / misranked.size

    // What share would we expect if the winning identity were picked at random from the
    // enrolled set? One in (identities - 1). Anything far above that is structure.
    val identityCount = results.map(_.identity).distinct.size
    val chanceShare   = if identityCount > 1 then 1.0 / (identityCount - 1) else 0.0

    // Near miss versus decisive loss: how far behind was the true identity?
    val deficits = misranked.map { case (r, _) => r.bestOtherIdentityScore - r.ownIdentityScore }
    val nearMisses = deficits.count(_ < 0.02)
    val medianDeficit =
      if deficits.isEmpty then 0.0
      else
        val sorted = deficits.sorted
        sorted(sorted.size / 2).toDouble

    // Among correctly ranked videos, how much headroom was there?
    val correctMargins =
      withTarget.filter(_._1.rank1Correct).map { case (r, _) =>
        r.ownIdentityScore - r.bestOtherIdentityScore
      }
    val medianCorrectMargin =
      if correctMargins.isEmpty then 0.0
      else
        val sorted = correctMargins.sorted
        sorted(sorted.size / 2).toDouble

    f"""Error analysis
       |  videos                        : ${results.size}
       |  misranked                     : ${misranked.size}
       |  ...of which top match was the TARGET identity : $confusedWithTarget%d ($targetShare%.4f)
       |  chance share if confusion were random         : $chanceShare%.4f
       |  near misses (deficit < 0.02)  : $nearMisses of ${misranked.size}
       |  median deficit when wrong     : $medianDeficit%.4f
       |  median margin when right      : $medianCorrectMargin%.4f
       |""".stripMargin

  /** Persist raw probe scores so ranking-only work never repeats the video processing.
    *
    * Decoding, detecting, embedding and tracking 500 videos costs about 25 minutes and is
    * independent of how the scores are later ranked. Anything that only changes ranking or
    * analysis, the scoring modes, the diagnostics, can be answered from this file in
    * seconds. Interventions that change how scores are PRODUCED (gallery construction,
    * aggregation, track filtering) must re-score and must not use a stale cache.
    *
    * Format: one row per probe, tab separated,
    *   identity <TAB> videoPath <TAB> galleryId=score <TAB> galleryId=score ...
    */
  def writeScores(probes: Seq[ProbeScores], out: Path): Unit =
    val lines = probes.map { p =>
      val cells = p.scores.map((id, s) => s"$id=$s").mkString("\t")
      s"${p.identity}\t${p.video}\t$cells"
    }
    Files.write(out, (lines :+ "").mkString("\n").getBytes("UTF-8"))

  def readScores(in: Path): Seq[ProbeScores] =
    val src = scala.io.Source.fromFile(in.toFile)
    try
      src
        .getLines()
        .filter(_.trim.nonEmpty)
        .map { line =>
          val parts = line.split("\t").toSeq
          require(parts.size >= 3, s"malformed probe row: $line")
          val scores = parts.drop(2).map { cell =>
            val i = cell.lastIndexOf('=')
            require(i > 0, s"malformed score cell: $cell")
            cell.substring(0, i) -> cell.substring(i + 1).toFloat
          }
          ProbeScores(parts(0), java.nio.file.Paths.get(parts(1)), scores)
        }
        .toSeq
    finally src.close()

  /** The target identity encoded in a Celeb-DF synthesis filename.
    *
    * `id{A}_id{B}_{NNNN}.mp4` is A's footage carrying B's face, so A is the target. The
    * direction was established structurally, not assumed; see
    * docs/evaluation/deepfake-datasets.md.
    */
  def targetOf(video: Path): Option[String] =
    val base = video.getFileName.toString.stripSuffix(".mp4")
    base.split("_").toList match
      case a :: _ :: _ :: Nil => Some(a)
      case _                  => None

  /** Target leakage: how strongly a swap carries the identity of the person whose footage
    * was manipulated, rather than the person whose face was pasted in.
    *
    * Measured at 88% of misrankings on the Celeb-DF dev split against a 1.9% chance rate,
    * which makes it the dominant failure mode rather than one of several. Reported as a
    * first-class metric so it is visible in every run rather than recoverable only by
    * separate analysis.
    */
  def leakageReport(results: Seq[DeepfakeResult]): String =
    require(results.nonEmpty, "cannot report leakage on no results")

    val withTarget = results.flatMap(r => targetOf(r.video).map(t => (r, t)))
    if withTarget.isEmpty then
      return "Target leakage\n  no videos with a parseable target identity\n"

    // Did the identity whose footage was used outrank the identity whose face was pasted?
    val targetOutranks = withTarget.count { case (r, t) =>
      r.bestOtherIdentity == t && r.bestOtherIdentityScore > r.ownIdentityScore
    }
    val outrankShare = targetOutranks.toDouble / withTarget.size

    val misranked = withTarget.filterNot(_._1.rank1Correct)
    val targetWon = misranked.count { case (r, t) => r.bestOtherIdentity == t }
    val targetShareOfErrors =
      if misranked.isEmpty then 0.0 else targetWon.toDouble / misranked.size

    val identityCount = results.map(_.identity).distinct.size
    val chance = if identityCount > 1 then 1.0 / (identityCount - 1) else 0.0

    // Blend signature: how close is the runner-up to the winner? Genuine footage of one
    // person should match that person and nobody else. A probe that scores highly on two
    // identities at once is showing the fingerprint of a swap rather than a single face.
    val gaps = withTarget.map { case (r, _) =>
      math.abs(r.ownIdentityScore - r.bestOtherIdentityScore)
    }
    val medianGap =
      if gaps.isEmpty then 0.0
      else
        val s = gaps.sorted
        s(s.size / 2).toDouble
    val tightBlends = gaps.count(_ < 0.05)

    f"""Target leakage
       |  videos with a known target    : ${withTarget.size}
       |  target outranks the face donor: $targetOutranks%d ($outrankShare%.4f)
       |  share of ERRORS won by target : $targetShareOfErrors%.4f (chance $chance%.4f)
       |  median |own - other| gap      : $medianGap%.4f
       |  probes within 0.05 of two ids : $tightBlends%d of ${withTarget.size}
       |""".stripMargin

  /** Cumulative match characteristic: how often the true identity appears in the top k.
    *
    * Rank-1 alone understates what the system knows here. Target leakage displaces the
    * true identity by exactly one competitor in most failures, so if the victim is
    * consistently second then a shortlist recovers most of the apparent loss.
    *
    * This is an operationally honest metric for this design rather than a flattering one:
    * alerts already go to a human reviewer and are never auto-reported, so presenting a
    * short candidate list is a real deployment mode. It would not be honest for a system
    * that acted on rank-1 automatically.
    */
  def cumulativeMatch(results: Seq[DeepfakeResult], ks: Seq[Int] = Seq(1, 2, 3, 5, 10)): String =
    require(results.nonEmpty, "cannot report on no results")

    val ranked = results.filter(_.ownRank > 0)
    val n      = ranked.size
    val rows = ks.map { k =>
      val hits = ranked.count(_.ownRank <= k)
      val rate = hits.toDouble / n
      f"    top-$k%-3d : $rate%.4f  ($hits%d of $n%d)"
    }.mkString("\n")

    val positions   = ranked.map(_.ownRank).sorted
    val medianRank  = if positions.isEmpty then 0 else positions(positions.size / 2)
    val meanRank    = if positions.isEmpty then 0.0 else positions.sum.toDouble / positions.size
    val candidates  = ranked.map(_.candidateCount).maxOption.getOrElse(0)

    // Of the videos the system gets wrong at rank 1, how many are recovered by looking one
    // place further down? This is the quantity target leakage predicts should be large.
    val missedAt1 = ranked.filter(_.ownRank > 1)
    val recoveredAt2 =
      if missedAt1.isEmpty then 0.0
      else missedAt1.count(_.ownRank == 2).toDouble / missedAt1.size

    f"""Cumulative match characteristic
       |  candidates per probe        : $candidates
       |$rows
       |  median rank of true identity: $medianRank
       |  mean rank of true identity  : $meanRank%.2f
       |  of rank-1 misses, share sitting at rank 2 : $recoveredAt2%.4f
       |""".stripMargin
