package ncii.vision

import ncii.core.DetectedFace

/** Chooses which detected face is the subject when an image contains several.
  *
  * Taking the detector's first face is wrong, and expensively so: during the LFW
  * evaluation it produced an equal error rate of 4.93% against a published 0.2%,
  * because 19.3% of LFW images contain more than one face and the first one detected
  * is frequently a bystander. Selecting the centre-most face fixed it.
  *
  * This lives in `vision` rather than in one protocol because every caller that turns
  * a photograph into an embedding faces the same choice (evaluation and enrolment
  * alike), and the two must agree, or enrolment stores embeddings that the evaluated
  * thresholds do not describe.
  */
object FaceSelection:

  /** The face whose bounding-box centre is nearest the image centre.
    *
    * Nearest by squared distance. The square root is monotonic, so it would not change
    * the ordering and is not worth computing.
    */
  def centreMost[A](
      faces: Seq[(DetectedFace, A)],
      imageWidth: Float,
      imageHeight: Float
  ): Option[(DetectedFace, A)] =
    if faces.isEmpty then None
    else
      val centreX = imageWidth / 2
      val centreY = imageHeight / 2
      Some(faces.minBy { case (face, _) =>
        val dx = (face.box.x + face.box.width / 2) - centreX
        val dy = (face.box.y + face.box.height / 2) - centreY
        dx * dx + dy * dy
      })
