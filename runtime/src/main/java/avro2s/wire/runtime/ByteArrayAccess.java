package avro2s.wire.runtime;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/** Public JDK byte-array views with genuinely primitive invocation descriptors.
 *
 * A Scala cast from VarHandle.get's Object result can box each loaded value.
 * These Java casts make the signature-polymorphic invocations return int/long
 * directly. Plain get supports unaligned offsets; callers ensure sufficient bytes.
 */
final class ByteArrayAccess {
  private static final VarHandle INTS =
      MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
  private static final VarHandle LONGS =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

  private ByteArrayAccess() {}

  static int getIntLE(byte[] bytes, int offset) {
    return (int) INTS.get(bytes, offset);
  }

  static long getLongLE(byte[] bytes, int offset) {
    return (long) LONGS.get(bytes, offset);
  }
}
