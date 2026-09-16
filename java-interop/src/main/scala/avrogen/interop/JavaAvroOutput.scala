package avrogen.interop

import avrogen.runtime.{AvroOutput, Bytes}
import org.apache.avro.io.Encoder

/**
 * Runs a generated codec on Java Avro's binary encoder for the same schema.
 *
 * The caller owns the encoder and must flush it after writing. Bytes are
 * passed directly to the encoder without making a defensive copy; their
 * backing arrays are never mutated by this adapter.
 */
final class JavaAvroOutput(encoder: Encoder) extends AvroOutput:
  override def writeNull(): Unit = encoder.writeNull()
  override def writeBoolean(value: Boolean): Unit = encoder.writeBoolean(value)
  override def writeInt(value: Int): Unit = encoder.writeInt(value)
  override def writeLong(value: Long): Unit = encoder.writeLong(value)
  override def writeFloat(value: Float): Unit = encoder.writeFloat(value)
  override def writeDouble(value: Double): Unit = encoder.writeDouble(value)
  override def writeString(value: String): Unit = encoder.writeString(value)
  override def writeBytes(value: Bytes): Unit = encoder.writeBytes(value.unsafeArray)
  override def writeFixed(value: Bytes): Unit = encoder.writeFixed(value.unsafeArray)
  override def writeEnum(value: Int): Unit = encoder.writeEnum(value)
  override def writeIndex(value: Int): Unit = encoder.writeIndex(value)

  override def writeArrayStart(size: Int): Unit =
    require(size >= 0, "Array size must be non-negative")
    encoder.writeArrayStart()
    encoder.setItemCount(size.toLong)

  override def writeArrayEnd(): Unit = encoder.writeArrayEnd()

  override def writeMapStart(size: Int): Unit =
    require(size >= 0, "Map size must be non-negative")
    encoder.writeMapStart()
    encoder.setItemCount(size.toLong)

  override def writeMapEnd(): Unit = encoder.writeMapEnd()
  override def startItem(): Unit = encoder.startItem()
