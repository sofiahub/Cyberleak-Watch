package ncii.core

/** Chooses the subset that spans the most variation.
  *
  * Greedy farthest-point selection: start from the embedding furthest from the set's
  * centroid, then repeatedly add whichever remaining embedding is least similar to the
  * ones already chosen. That yields a gallery covering different poses and expressions
  * rather than five versions of the same headshot.
  *
  * **Index-based selection.** The implementation selects by index rather than object
  * identity. If the input contains the same Embedding instance multiple times, each
  * occurrence is considered separately: they will not all disappear in a single step.
  * This matters when enrolment photos yield the same embedding multiple times (e.g.
  * duplicate uploads, or photos so similar they compress to identical vectors).
  *
  * Deterministic by construction, ties break on index, so the same upload produces the
  * same gallery every time, which matters when an enrolment has to be reproduced.
  */
object GallerySelection:

  def select(embeddings: Seq[Embedding], count: Int): Seq[Embedding] =
    if count < 1 then
      throw new IllegalArgumentException(s"count must be at least 1, got $count")

    if embeddings.isEmpty then
      throw new IllegalArgumentException("cannot select from an empty set")

    if embeddings.sizeIs <= count then embeddings
    else
      val centroid = Embedding.mean(embeddings)
      val firstIdx = embeddings.indices.minBy(i => embeddings(i).cosine(centroid))

      @annotation.tailrec
      def go(chosen: Vector[Int], remaining: Vector[Int]): Vector[Int] =
        if chosen.sizeIs >= count || remaining.isEmpty then chosen
        else
          // The next pick is the one whose closest already-chosen neighbour is furthest
          // away. That is the standard farthest-point rule. Ties fall to the lowest index, which
          // is what makes the result reproducible.
          val nextIdx = remaining.minBy(i => chosen.map(c => embeddings(c).cosine(embeddings(i))).max)
          go(chosen :+ nextIdx, remaining.filterNot(_ == nextIdx))

      go(Vector(firstIdx), embeddings.indices.toVector.filterNot(_ == firstIdx))
        .map(embeddings)
