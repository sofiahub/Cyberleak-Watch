package ncii.crypto

import java.lang.foreign.{Arena, FunctionDescriptor, MemorySegment, ValueLayout}

/** A CKKS evaluation context: parameters plus an encoder, living on the native side.
  *
  * Holds a native handle, so it must be closed. Not thread-safe.
  */
final class CkksContext private (val handle: Long) extends AutoCloseable:

  private var closed = false

  def slotCount: Int =
    val local = Arena.ofConfined()
    try
      val out = local.allocate(ValueLayout.JAVA_INT)
      val status = CkksContext.slotCountHandle.invoke(handle, out).asInstanceOf[Int]
      NativeLibrary.check(status, "slotCount")
      out.get(ValueLayout.JAVA_INT, 0)
    finally local.close()

  def close(): Unit =
    if !closed then
      closed = true
      NativeLibrary.freeHandle(handle)

object CkksContext:

  private[crypto] lazy val newContextHandle =
    NativeLibrary.handleFor(
      "ckks_new_context",
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS
      )
    )

  private[crypto] lazy val slotCountHandle =
    NativeLibrary.handleFor(
      "ckks_slot_count",
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
    )

  def create(): CkksContext =
    val local = Arena.ofConfined()
    try
      val out = local.allocate(ValueLayout.JAVA_LONG)
      val status = newContextHandle
        .invoke(CkksParams.LogN, CkksParams.LogScale, out)
        .asInstanceOf[Int]
      NativeLibrary.check(status, "createContext")
      new CkksContext(out.get(ValueLayout.JAVA_LONG, 0))
    finally local.close()
