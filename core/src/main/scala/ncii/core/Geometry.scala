package ncii.core

final case class Point(x: Float, y: Float)

final case class BoundingBox(x: Float, y: Float, width: Float, height: Float):
  def right: Float  = x + width
  def bottom: Float = y + height
  def area: Float   = width * height

  /** Intersection over union, the standard overlap measure used for face tracking. */
  def intersectionOverUnion(other: BoundingBox): Float =
    val overlapWidth  = math.min(right, other.right) - math.max(x, other.x)
    val overlapHeight = math.min(bottom, other.bottom) - math.max(y, other.y)
    if overlapWidth <= 0 || overlapHeight <= 0 then 0.0f
    else
      val intersection = overlapWidth * overlapHeight
      intersection / (area + other.area - intersection)

/** The five facial landmarks ArcFace alignment expects.
  *
  * Named from the subject's perspective: the subject's right eye appears on the
  * left of the image, at the smaller x coordinate. The flattened order produced by
  * [[toArray]] must match the canonical template in `FaceAligner`.
  */
final case class Landmarks5(
    subjectRightEye: Point,
    subjectLeftEye: Point,
    nose: Point,
    subjectRightMouth: Point,
    subjectLeftMouth: Point
):
  def toArray: Array[Float] = Array(
    subjectRightEye.x,   subjectRightEye.y,
    subjectLeftEye.x,    subjectLeftEye.y,
    nose.x,              nose.y,
    subjectRightMouth.x, subjectRightMouth.y,
    subjectLeftMouth.x,  subjectLeftMouth.y
  )

final case class DetectedFace(box: BoundingBox, landmarks: Landmarks5, score: Float)
