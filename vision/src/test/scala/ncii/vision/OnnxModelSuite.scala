package ncii.vision

class OnnxModelSuite extends munit.FunSuite:

  // munit's 30-second default is not sized for this project's integration suites. They
  // load a 166 MB ONNX model, generate CKKS key sets at ~47 MB of Galois keys each, decode
  // video, or start a Postgres container, legitimately slow work that competes with
  // whatever else the machine is running. Three separate suites timed out at 31-197s while
  // asserting nothing wrong, so the limit is set to the work rather than raised one failure
  // at a time.
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  // The batch dimension is -1: buffalo_l exports w600k_r50 with a dynamic batch,
  // so callers must supply a concrete batch size at inference time.
  test("ArcFace model exposes one input of shape Nx3x112x112 and a 512-d output") {
    assume(
      Assets.available(Assets.embedderModel),
      Assets.missingMessage(Assets.embedderModel)
    )
    val model = OnnxModel.open(Assets.embedderModel)
    try
      assertEquals(model.inputNames.size, 1)
      assertEquals(model.inputShape.toSeq, Seq(-1L, 3L, 112L, 112L))
      assertEquals(model.outputShape.last, 512L)
    finally model.close()
  }

  test("running the ArcFace model on zeros returns 512 finite floats") {
    assume(
      Assets.available(Assets.embedderModel),
      Assets.missingMessage(Assets.embedderModel)
    )
    val model = OnnxModel.open(Assets.embedderModel)
    try
      val input  = new Array[Float](3 * 112 * 112)
      val output = model.run(model.inputNames.head, input, Array(1L, 3L, 112L, 112L))
      assertEquals(output.length, 512)
      assert(output.forall(f => !f.isNaN && !f.isInfinite), "output must be finite")
    finally model.close()
  }

  test("opening a missing model fails loudly") {
    intercept[IllegalArgumentException](
      OnnxModel.open(Assets.modelsDir.resolve("does-not-exist.onnx"))
    )
  }
