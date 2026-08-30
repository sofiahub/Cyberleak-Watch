package ncii.vision

import ncii.core.{BoundingBox, DetectedFace, Embedding}

final case class TrackObservation(
    timestampSeconds: Double,
    box: BoundingBox,
    embedding: Embedding
)

/** One person's continuous appearance in a video. */
final case class FaceTrack(id: Int, observations: Seq[TrackObservation]):

  /** The single vector representing this appearance. Averaging across frames
    * suppresses per-frame noise, and means one clip costs one encrypted score
    * rather than one per frame.
    */
  def meanEmbedding: Embedding = Embedding.mean(observations.map(_.embedding))

  def durationSeconds: Double =
    if observations.isEmpty then 0.0
    else observations.map(_.timestampSeconds).max - observations.map(_.timestampSeconds).min

/** Greedy frame-to-frame association by box overlap, with embedding similarity as
  * the tie-break.
  *
  * Greedy matching is enough here: sampling at 0.5s means faces move a lot between
  * observations, so the sophistication of a Kalman filter or the Hungarian algorithm
  * buys little, and every extra parameter is one more thing to justify in the writeup.
  */
object FaceTracker:

  val MinIou: Float          = 0.2f
  val MinSimilarity: Float   = 0.4f
  val MaxGapSeconds: Double  = 1.5

  def track(
      frames: Seq[(Double, Seq[(DetectedFace, Embedding)])]
  ): Seq[FaceTrack] =
    var open      = List.empty[(Int, TrackObservation, List[TrackObservation])]
    var closed    = List.empty[FaceTrack]
    var nextId    = 0

    frames.sortBy(_._1).foreach { case (timestamp, detections) =>
      // Retire tracks that have not been seen recently.
      val (expired, live) =
        open.partition((_, last, _) => timestamp - last.timestampSeconds > MaxGapSeconds)
      closed = expired.map((id, _, obs) => FaceTrack(id, obs.reverse)) ::: closed
      open = live

      var unmatched = open
      var updated   = List.empty[(Int, TrackObservation, List[TrackObservation])]

      detections.foreach { case (face, embedding) =>
        val observation = TrackObservation(timestamp, face.box, embedding)
        val best = unmatched
          .map { entry =>
            val (_, last, _) = entry
            val iou          = last.box.intersectionOverUnion(face.box)
            val similarity   = last.embedding.cosine(embedding)
            (entry, iou, similarity)
          }
          .filter((_, iou, similarity) => iou >= MinIou && similarity >= MinSimilarity)
          .maxByOption((_, iou, similarity) => iou + similarity)

        best match
          case Some((entry @ (id, _, history), _, _)) =>
            unmatched = unmatched.filterNot(_ == entry)
            updated = (id, observation, observation :: history) :: updated
          case None =>
            updated = (nextId, observation, List(observation)) :: updated
            nextId += 1
      }

      open = updated ::: unmatched
    }

    val remaining = open.map((id, _, obs) => FaceTrack(id, obs.reverse))
    (closed ::: remaining).sortBy(_.id)
