package ncii.vision

import ncii.core.Landmarks5
import org.bytedeco.javacpp.indexer.{DoubleIndexer, FloatIndexer}
import org.bytedeco.opencv.global.opencv_core.{CV_32FC2, CV_64F, SVDecomp}
import org.bytedeco.opencv.global.opencv_imgproc.warpAffine
import org.bytedeco.opencv.opencv_core.{Mat, Size}

/** Warps a detected face onto ArcFace's canonical 112x112 frame.
  *
  * The template is InsightFace's standard five-point reference. ArcFace was trained
  * on faces warped to exactly these coordinates, so it is a fixed constant of the
  * model, not a tunable.
  *
  * Alignment uses Umeyama's least-squares similarity transform (Umeyama, 1991),
  * which matches InsightFace's approach and guarantees deterministic, reproducible
  * alignment with no randomisation or outlier rejection.
  */
object FaceAligner:

  val OutputSize: Int = 112

  /** x,y pairs in template order: subject's right eye, left eye, nose, right mouth, left mouth. */
  val CanonicalLandmarks: Array[Float] = Array(
    38.2946f, 51.6963f,
    73.5318f, 51.5014f,
    56.0252f, 71.7366f,
    41.5493f, 92.3655f,
    70.7299f, 92.2041f
  )

  private def pointsMat(xy: Array[Float]): Mat =
    val m   = new Mat(5, 1, CV_32FC2)
    val idx = m.createIndexer[FloatIndexer]()
    try
      var i = 0
      while i < 5 do
        idx.put(i.toLong, 0L, 0L, xy(i * 2))
        idx.put(i.toLong, 0L, 1L, xy(i * 2 + 1))
        i += 1
    finally idx.close()
    m

  /** Extract 5 (x, y) points from a CV_32FC2 Mat. */
  private def extractPoints(m: Mat): Array[Array[Double]] =
    val idx = m.createIndexer[FloatIndexer]()
    try
      (0 until 5).map { i =>
        val x = idx.get(i.toLong, 0L, 0L).toDouble
        val y = idx.get(i.toLong, 0L, 1L).toDouble
        Array(x, y)
      }.toArray
    finally idx.close()

  /** Umeyama's least-squares similarity transform (rotation, uniform scale, translation).
    *
    * Computes the closed-form optimal similarity transform that maps src to dst.
    * The transform is deterministic and reproduces InsightFace's alignment exactly.
    */
  private def umeyamaTransform(src: Array[Array[Double]], dst: Array[Array[Double]]): Mat =
    val n = 5

    // Step 1: Compute centroids
    val mu_src = Array(
      src.map(_(0)).sum / n,
      src.map(_(1)).sum / n
    )
    val mu_dst = Array(
      dst.map(_(0)).sum / n,
      dst.map(_(1)).sum / n
    )

    // Step 2: Centre both sets
    val src_centred = src.map(p => Array(p(0) - mu_src(0), p(1) - mu_src(1)))
    val dst_centred = dst.map(p => Array(p(0) - mu_dst(0), p(1) - mu_dst(1)))

    // Step 3: Compute covariance Sigma = (1/n) * dst_centred^T * src_centred (2x2 matrix)
    val sigma = Array(
      Array(
        (0 until n).map(i => dst_centred(i)(0) * src_centred(i)(0)).sum / n,
        (0 until n).map(i => dst_centred(i)(0) * src_centred(i)(1)).sum / n
      ),
      Array(
        (0 until n).map(i => dst_centred(i)(1) * src_centred(i)(0)).sum / n,
        (0 until n).map(i => dst_centred(i)(1) * src_centred(i)(1)).sum / n
      )
    )

    // Step 4: SVD of Sigma
    val sigmaMat = new Mat(2, 2, CV_64F)
    val sigmaIdx = sigmaMat.createIndexer[DoubleIndexer]()
    try
      sigmaIdx.put(0L, 0L, sigma(0)(0))
      sigmaIdx.put(0L, 1L, sigma(0)(1))
      sigmaIdx.put(1L, 0L, sigma(1)(0))
      sigmaIdx.put(1L, 1L, sigma(1)(1))
    finally sigmaIdx.close()

    val U = new Mat()
    val w = new Mat()
    val Vt = new Mat()
    try
      SVDecomp(sigmaMat, w, U, Vt)

      // Extract U and V^T
      val U_idx = U.createIndexer[DoubleIndexer]()
      val U_data = try
        Array(
          Array(U_idx.get(0L, 0L), U_idx.get(0L, 1L)),
          Array(U_idx.get(1L, 0L), U_idx.get(1L, 1L))
        )
      finally U_idx.close()

      val Vt_idx = Vt.createIndexer[DoubleIndexer]()
      val Vt_data = try
        Array(
          Array(Vt_idx.get(0L, 0L), Vt_idx.get(0L, 1L)),
          Array(Vt_idx.get(1L, 0L), Vt_idx.get(1L, 1L))
        )
      finally Vt_idx.close()

      val w_idx = w.createIndexer[DoubleIndexer]()
      val w_vals = try
        Array(w_idx.get(0L, 0L), w_idx.get(1L, 0L))
      finally w_idx.close()

      // Step 5: Reflection guard: S = I; if det(U)*det(V^T) < 0, set S[1,1] = -1
      val detU = U_data(0)(0) * U_data(1)(1) - U_data(0)(1) * U_data(1)(0)
      val detVt = Vt_data(0)(0) * Vt_data(1)(1) - Vt_data(0)(1) * Vt_data(1)(0)
      val S = if detU * detVt < 0 then Array(Array(1.0, 0.0), Array(0.0, -1.0))
      else Array(Array(1.0, 0.0), Array(0.0, 1.0))

      // Step 6: Rotation R = U * S * V^T
      // U * S
      val US = Array(
        Array(U_data(0)(0) * S(0)(0) + U_data(0)(1) * S(1)(0),
              U_data(0)(0) * S(0)(1) + U_data(0)(1) * S(1)(1)),
        Array(U_data(1)(0) * S(0)(0) + U_data(1)(1) * S(1)(0),
              U_data(1)(0) * S(0)(1) + U_data(1)(1) * S(1)(1))
      )
      // (U * S) * V^T
      val R = Array(
        Array(US(0)(0) * Vt_data(0)(0) + US(0)(1) * Vt_data(1)(0),
              US(0)(0) * Vt_data(0)(1) + US(0)(1) * Vt_data(1)(1)),
        Array(US(1)(0) * Vt_data(0)(0) + US(1)(1) * Vt_data(1)(0),
              US(1)(0) * Vt_data(0)(1) + US(1)(1) * Vt_data(1)(1))
      )

      // Step 7: Scale c = trace(D * S) / var_src
      val trace_DS = w_vals(0) * S(0)(0) + w_vals(1) * S(1)(1)
      val var_src = src_centred.map(p => p(0) * p(0) + p(1) * p(1)).sum / n
      val c = trace_DS / var_src

      // Step 8: Translation t = mu_dst - c * R * mu_src
      val R_mu_src = Array(
        R(0)(0) * mu_src(0) + R(0)(1) * mu_src(1),
        R(1)(0) * mu_src(0) + R(1)(1) * mu_src(1)
      )
      val t = Array(
        mu_dst(0) - c * R_mu_src(0),
        mu_dst(1) - c * R_mu_src(1)
      )

      // Step 9: Build 2x3 affine matrix [c*R | t]
      val affine = new Mat(2, 3, CV_64F)
      val affineIdx = affine.createIndexer[DoubleIndexer]()
      try
        affineIdx.put(0L, 0L, c * R(0)(0))
        affineIdx.put(0L, 1L, c * R(0)(1))
        affineIdx.put(0L, 2L, t(0))
        affineIdx.put(1L, 0L, c * R(1)(0))
        affineIdx.put(1L, 1L, c * R(1)(1))
        affineIdx.put(1L, 2L, t(1))
      finally affineIdx.close()
      affine
    finally
      sigmaMat.close()
      U.close()
      w.close()
      Vt.close()

  /** Returns a new 112x112 BGR Mat. The caller owns it and must close it. */
  def align(image: Mat, landmarks: Landmarks5): Mat =
    require(!image.empty(), "cannot align an empty image")
    val srcMat = pointsMat(landmarks.toArray)
    val dstMat = pointsMat(CanonicalLandmarks)
    try
      val src = extractPoints(srcMat)
      val dst = extractPoints(dstMat)
      val transform = umeyamaTransform(src, dst)
      try
        require(!transform.empty(), "could not estimate an alignment transform")
        val out = new Mat()
        warpAffine(image, out, transform, new Size(OutputSize, OutputSize))
        out
      finally transform.close()
    finally
      srcMat.close()
      dstMat.close()
