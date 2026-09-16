package avrogen.interop

import avrogen.runtime.{AvroInput, Bytes}
import org.apache.avro.io.Decoder

/**
 * Runs a generated codec on Java Avro's binary decoder for the same schema.
 *
 * This adapter does not resolve writer and reader schemas or apply native
 * decoder limits.
 */
final class JavaAvroInput(decoder: Decoder) extends AvroInput:
  override def readNull(): Unit = decoder.readNull()
  override def readBoolean(): Boolean = decoder.readBoolean()
  override def readInt(): Int = decoder.readInt()
  override def readLong(): Long = decoder.readLong()
  override def readFloat(): Float = decoder.readFloat()
  override def readDouble(): Double = decoder.readDouble()
  override def readString(): String = decoder.readString()

  /** Copies only the returned buffer's remaining bytes into owned storage. */
  override def readBytes(): Bytes =
    val buffer = decoder.readBytes(null).duplicate()
    val bytes = new Array[Byte](buffer.remaining())
    buffer.get(bytes)
    Bytes.unsafeWrap(bytes)

  override def readFixed(size: Int): Bytes =
    require(size >= 0, "Fixed size must be non-negative")
    val bytes = new Array[Byte](size)
    decoder.readFixed(bytes)
    Bytes.unsafeWrap(bytes)

  override def readEnum(): Int = decoder.readEnum()
  override def readIndex(): Int = decoder.readIndex()
  override def readArrayStart(): Long = decoder.readArrayStart()
  override def arrayNext(): Long = decoder.arrayNext()
  override def readMapStart(): Long = decoder.readMapStart()
  override def mapNext(): Long = decoder.mapNext()
