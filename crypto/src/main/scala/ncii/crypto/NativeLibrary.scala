package ncii.crypto

import java.lang.foreign.*
import java.lang.invoke.MethodHandle
import java.nio.file.{Files, Path, Paths}

/** Raised when the native bridge reports a non-zero status. */
final class NativeException(message: String) extends RuntimeException(message)

/** Loads the Lattigo CKKS bridge and exposes its C ABI through Java FFM.
  *
  * The library is a build output, not a committed artifact, run
  * `./scripts/build-lattigo.sh` first. Its location can be overridden with
  * `NCII_CKKS_LIB` so tests can point at a build directory.
  */
object NativeLibrary:

  /** Status codes mirrored from bridge.go. Kept in sync by hand; the version string
    * check in the test suite is what catches a mismatched library.
    */
  val StatusOK: Int = 0

  private lazy val libraryPath: Path =
    sys.env.get("NCII_CKKS_LIB").map(Paths.get(_)).getOrElse {
      Paths.get("native/build/libncii_ckks.dylib").toAbsolutePath
    }

  private lazy val arena: Arena = Arena.ofShared()

  private lazy val lookup: SymbolLookup =
    if !Files.exists(libraryPath) then
      throw new NativeException(
        s"CKKS library not found at $libraryPath, run ./scripts/build-lattigo.sh"
      )
    else SymbolLookup.libraryLookup(libraryPath, arena)

  private val linker: Linker = Linker.nativeLinker()

  private[crypto] def handleFor(name: String, descriptor: FunctionDescriptor): MethodHandle =
    val symbol = lookup
      .find(name)
      .orElseThrow(() => new NativeException(s"symbol '$name' not found in $libraryPath"))
    linker.downcallHandle(symbol, descriptor)

  private lazy val versionHandle =
    handleFor(
      "ckks_version",
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
    )

  private lazy val lastErrorHandle =
    handleFor(
      "ckks_last_error",
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
    )

  private lazy val freeHandleHandle =
    handleFor(
      "ckks_free_handle",
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)
    )

  private lazy val liveHandleCountHandle =
    handleFor(
      "ckks_handle_count",
      FunctionDescriptor.of(ValueLayout.JAVA_INT)
    )

  /** Calls a two-phase string function: ask for the size, then fetch the bytes.
    *
    * Uses `invoke` rather than `invokeExact`: the latter requires exact static type matching
    * at the call site, which Scala cannot express for a MethodHandle returned from a generic
    * method. `invoke` performs type coercion instead of rejecting; this is safe here because
    * these are initialisation/error/cleanup calls (not the hot scoring path), and because
    * the argument and return types at each call site are verified by inspection against the
    * FunctionDescriptor, and the runtime will no longer catch a mismatch loudly.
    */
  private def readString(handle: MethodHandle): String =
    val needed = handle.invoke(MemorySegment.NULL, 0).asInstanceOf[Int]
    if needed <= 0 then ""
    else
      val local = Arena.ofConfined()
      try
        val buffer = local.allocate(needed.toLong)
        val written = handle.invoke(buffer, needed).asInstanceOf[Int]
        new String(buffer.asSlice(0, written.toLong).toArray(ValueLayout.JAVA_BYTE), "UTF-8")
      finally local.close()

  def version: String = readString(versionHandle)

  def lastError: String = readString(lastErrorHandle)

  /** Throws if the native side reported a failure, attaching its message. */
  private[crypto] def check(status: Int, operation: String): Unit =
    if status != StatusOK then
      throw new NativeException(s"$operation failed (status $status): $lastError")

  def freeHandle(handle: Long): Unit =
    val status = freeHandleHandle.invoke(handle).asInstanceOf[Int]
    check(status, s"freeHandle($handle)")

  def liveHandleCount: Int =
    liveHandleCountHandle.invoke().asInstanceOf[Int]
