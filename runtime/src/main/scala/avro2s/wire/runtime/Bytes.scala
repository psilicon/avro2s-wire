package avro2s.wire.runtime

import java.util.Arrays

/** An immutable, value-comparable sequence of bytes. Public array boundaries copy. */
final class Bytes private (private[wire] val unsafeArray: Array[Byte]):
  def size: Int = unsafeArray.length
  def toArray: Array[Byte] = unsafeArray.clone()

  override def equals(other: Any): Boolean = other match
    case that: Bytes => Arrays.equals(unsafeArray, that.unsafeArray)
    case _           => false

  override def hashCode(): Int = Arrays.hashCode(unsafeArray)
  override def toString: String = s"Bytes(${size} bytes)"

object Bytes:
  val empty: Bytes = new Bytes(Array.emptyByteArray)

  def fromArray(bytes: Array[Byte]): Bytes =
    if bytes.isEmpty then empty else new Bytes(bytes.clone())

  /** Internal ownership transfer: the caller must never subsequently mutate bytes. */
  private[wire] def unsafeWrap(bytes: Array[Byte]): Bytes =
    if bytes.isEmpty then empty else new Bytes(bytes)
