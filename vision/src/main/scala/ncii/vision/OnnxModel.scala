package ncii.vision

import ai.onnxruntime.{OnnxTensor, OrtEnvironment, OrtSession, TensorInfo}

import java.nio.FloatBuffer
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** A loaded ONNX model with a single float input and a single float output.
  *
  * Both of this project's models fit that shape, so the wrapper stays small.
  * Not thread-safe: give each thread its own instance.
  */
final class OnnxModel private (
    env: OrtEnvironment,
    session: OrtSession
) extends AutoCloseable:

  def inputNames: Set[String] = session.getInputNames.asScala.toSet

  def inputShape: Array[Long] =
    session.getInputInfo.values.asScala.head.getInfo
      .asInstanceOf[TensorInfo]
      .getShape

  def outputShape: Array[Long] =
    session.getOutputInfo.values.asScala.head.getInfo
      .asInstanceOf[TensorInfo]
      .getShape

  /** Runs inference and returns the first output flattened to a float array. */
  def run(inputName: String, data: Array[Float], shape: Array[Long]): Array[Float] =
    val expected = shape.product.toInt
    require(
      data.length == expected,
      s"input has ${data.length} values but shape ${shape.mkString("x")} needs $expected"
    )
    val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
    try
      val result = session.run(Map(inputName -> tensor).asJava)
      try
        result.get(0).getValue match
          case flat: Array[Float]          => flat
          case batched: Array[Array[Float]] => batched(0)
          case other =>
            throw new IllegalStateException(
              s"unexpected ONNX output type: ${other.getClass.getName}"
            )
      finally result.close()
    finally tensor.close()

  def close(): Unit = session.close()

object OnnxModel:

  def open(path: Path): OnnxModel =
    require(Files.exists(path), s"model not found: $path")
    val env  = OrtEnvironment.getEnvironment
    val opts = new OrtSession.SessionOptions()
    new OnnxModel(env, env.createSession(path.toString, opts))
