package ncii.crypto

/** The CKKS parameter set, fixed by the design.
  *
  * These are not tunables. Ciphertext layout, the rotation schedule and the noise
  * budget all depend on them, and a stored gallery encrypted under one set cannot be
  * scored under another. See the design spec, section 7.
  */
object CkksParams:
  /** Ring degree 2^14 = 16384. */
  val LogN: Int = 14
  val RingDegree: Int = 1 << LogN

  /** CKKS packs N/2 complex slots. */
  val Slots: Int = RingDegree / 2

  /** Scale 2^40, enough precision that decrypted scores land ~1e-5 from plaintext
    * cosine, orders of magnitude below any threshold margin.
    */
  val LogScale: Int = 40

  /** One 512-d embedding per block. */
  val BlockSize: Int = 512
  val BlocksPerCiphertext: Int = Slots / BlockSize

  /** Rotate-and-sum halves the stride each round: log2(512) = 9. */
  val RotationsPerBlock: Int = Integer.numberOfTrailingZeros(BlockSize)
