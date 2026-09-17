package avro2s.wire.runtime

import java.util.Arrays

/** Growable binary output. Reuse with reset(); instances are not thread-safe. */
final class BinaryOutput(initialCapacity: Int = 256) extends AvroOutput:
  require(initialCapacity >= 0, "initialCapacity must be non-negative")
  private var buffer = new Array[Byte](initialCapacity)
  private var position = 0

  def size: Int = position
  def reset(): Unit = position = 0
  def toByteArray: Array[Byte] = Arrays.copyOf(buffer, position)

  private def reserve(count: Int): Unit =
    val required = position.toLong + count.toLong
    require(required <= Int.MaxValue, "Encoded datum exceeds maximum array size")
    if required > buffer.length then
      val capacity = math.max(required, math.max(32L, buffer.length.toLong * 2L))
      buffer = Arrays.copyOf(buffer, math.min(capacity, Int.MaxValue.toLong).toInt)

  private def put(value: Int): Unit =
    buffer(position) = value.toByte
    position += 1

  override def writeBoolean(value: Boolean): Unit =
    reserve(1)
    put(if value then 1 else 0)

  override def writeInt(value: Int): Unit =
    if buffer.length - position < 5 then reserve(5)
    val bytes = buffer
    var offset = position
    var bits = (value << 1) ^ (value >> 31)
    while (bits & ~0x7f) != 0 do
      bytes(offset) = ((bits & 0x7f) | 0x80).toByte
      offset += 1
      bits = bits >>> 7
    bytes(offset) = bits.toByte
    position = offset + 1

  override def writeLong(value: Long): Unit =
    if buffer.length - position < 10 then reserve(10)
    val bytes = buffer
    var offset = position
    var bits = (value << 1) ^ (value >> 63)
    while (bits & ~0x7fL) != 0 do
      bytes(offset) = ((bits & 0x7fL) | 0x80L).toByte
      offset += 1
      bits = bits >>> 7
    bytes(offset) = bits.toByte
    position = offset + 1

  override def writeFloat(value: Float): Unit =
    reserve(4)
    val bits = java.lang.Float.floatToRawIntBits(value)
    var shift = 0
    while shift < 32 do
      put(bits >>> shift)
      shift += 8

  override def writeDouble(value: Double): Unit =
    reserve(8)
    val bits = java.lang.Double.doubleToRawLongBits(value)
    var shift = 0
    while shift < 64 do
      put((bits >>> shift).toInt)
      shift += 8

  /** Emits UTF-8 directly, rejecting unpaired UTF-16 surrogates. */
  override def writeString(value: String): Unit =
    var index = 0
    while index < value.length && value.charAt(index) < 0x80 do index += 1
    var encodedLength = index.toLong
    while index < value.length do
      val ch = value.charAt(index).toInt
      if ch < 0x80 then encodedLength += 1
      else if ch < 0x800 then encodedLength += 2
      else if ch >= 0xd800 && ch <= 0xdbff then
        require(index + 1 < value.length && Character.isLowSurrogate(value.charAt(index + 1)),
          s"Unpaired UTF-16 surrogate at index $index")
        encodedLength += 4
        index += 1
      else
        require(ch < 0xdc00 || ch > 0xdfff, s"Unpaired UTF-16 surrogate at index $index")
        encodedLength += 3
      index += 1
    require(encodedLength <= Int.MaxValue, "UTF-8 string exceeds maximum array size")
    writeLong(encodedLength)
    reserve(encodedLength.toInt)
    if encodedLength == value.length.toLong then
      // ASCII has already been validated. Copy without per-byte position updates.
      val destination = buffer
      val start = position
      index = 0
      while index < value.length do
        destination(start + index) = value.charAt(index).toByte
        index += 1
      position = start + value.length
      return
    index = 0
    while index < value.length do
      val ch = value.charAt(index).toInt
      if ch < 0x80 then put(ch)
      else if ch < 0x800 then
        put(0xc0 | (ch >>> 6))
        put(0x80 | (ch & 0x3f))
      else if ch >= 0xd800 && ch <= 0xdbff then
        val codePoint = Character.toCodePoint(value.charAt(index), value.charAt(index + 1))
        put(0xf0 | (codePoint >>> 18))
        put(0x80 | ((codePoint >>> 12) & 0x3f))
        put(0x80 | ((codePoint >>> 6) & 0x3f))
        put(0x80 | (codePoint & 0x3f))
        index += 1
      else
        put(0xe0 | (ch >>> 12))
        put(0x80 | ((ch >>> 6) & 0x3f))
        put(0x80 | (ch & 0x3f))
      index += 1

  override def writeBytes(value: Bytes): Unit =
    writeLong(value.size.toLong)
    writeFixed(value)

  override def writeFixed(value: Bytes): Unit =
    reserve(value.size)
    System.arraycopy(value.unsafeArray, 0, buffer, position, value.size)
    position += value.size

  override def writeEnum(value: Int): Unit =
    require(value >= 0, "Enum index must be non-negative")
    writeInt(value)

  override def writeIndex(value: Int): Unit =
    require(value >= 0, "Union index must be non-negative")
    writeLong(value.toLong)

  override def writeArrayStart(size: Int): Unit = writeCollectionStart(size)
  override def writeMapStart(size: Int): Unit = writeCollectionStart(size)
  override def writeArrayEnd(): Unit = writeLong(0L)
  override def writeMapEnd(): Unit = writeLong(0L)
  override def startItem(): Unit = ()

  private def writeCollectionStart(size: Int): Unit =
    require(size >= 0, "Collection size must be non-negative")
    if size > 0 then writeLong(size.toLong)
