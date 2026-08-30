package ncii.vision

import org.bytedeco.javacv.{FFmpegFrameGrabber, OpenCVFrameConverter}
import org.bytedeco.opencv.opencv_core.Mat

import java.nio.file.{Files, Path}

/** One decoded frame with its position in the video. The `Mat` is only valid inside
  * the consuming callback; the sampler closes it immediately afterwards.
  */
final case class SampledFrame(timestampSeconds: Double, image: Mat)

/** Samples frames at a fixed wall-clock interval.
  *
  * Frames stream to a callback rather than accumulating in a list, because each
  * `Mat` holds native memory outside the JVM heap. A ten-minute video at 0.5s is
  * 1200 frames, which is enough uncollected native memory to bring down the process.
  */
object VideoSampler:

  def sample(path: Path, intervalSeconds: Double = 0.5)(
      consume: SampledFrame => Unit
  ): Unit =
    require(Files.exists(path), s"video not found: $path")
    require(intervalSeconds > 0, s"interval must be positive, got $intervalSeconds")

    val grabber   = new FFmpegFrameGrabber(path.toFile)
    val converter = new OpenCVFrameConverter.ToMat()
    grabber.start()
    try
      val durationSeconds = grabber.getLengthInTime.toDouble / 1_000_000.0
      var t               = 0.0
      while t < durationSeconds do
        grabber.setTimestamp((t * 1_000_000).toLong)
        val frame = grabber.grabImage()
        if frame != null then
          val mat = converter.convert(frame)
          if mat != null && !mat.empty() then
            // convert() reuses one buffer per converter, so clone before handing it out.
            val owned = mat.clone()
            try consume(SampledFrame(t, owned))
            finally owned.close()
        t += intervalSeconds
    finally
      grabber.stop()
      grabber.release()
      converter.close()
