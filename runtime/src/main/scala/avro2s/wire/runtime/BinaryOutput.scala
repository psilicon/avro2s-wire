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
    if position == buffer.length then reserve(1)
    put(if value then 1 else 0)

  override def writeInt(value: Int): Unit =
    val bits = (value << 1) ^ (value >> 31)
    if (bits & ~0x7f) == 0 then
      if position == buffer.length then reserve(1)
      buffer(position) = bits.toByte
      position += 1
    else writeIntMultiple(bits)

  private def writeIntMultiple(encoded: Int): Unit =
    if buffer.length - position < 5 then reserve(5)
    val bytes = buffer
    val offset = position
    bytes(offset) = (encoded | 0x80).toByte
    var bits = encoded >>> 7
    if (bits & ~0x7f) == 0 then
      bytes(offset + 1) = bits.toByte
      position = offset + 2
    else
      bytes(offset + 1) = (bits | 0x80).toByte
      bits = bits >>> 7
      if (bits & ~0x7f) == 0 then
        bytes(offset + 2) = bits.toByte
        position = offset + 3
      else
        bytes(offset + 2) = (bits | 0x80).toByte
        bits = bits >>> 7
        if (bits & ~0x7f) == 0 then
          bytes(offset + 3) = bits.toByte
          position = offset + 4
        else
          bytes(offset + 3) = (bits | 0x80).toByte
          bytes(offset + 4) = (bits >>> 7).toByte
          position = offset + 5

  override def writeLong(value: Long): Unit =
    val bits = (value << 1) ^ (value >> 63)
    if (bits & ~0x7fL) == 0 then
      if position == buffer.length then reserve(1)
      buffer(position) = bits.toByte
      position += 1
    else writeLongMultiple(bits)

  private def writeLongMultiple(encoded: Long): Unit =
    if buffer.length - position < 10 then reserve(10)
    val bytes = buffer
    val offset = position
    bytes(offset) = (encoded.toInt | 0x80).toByte
    var bits = encoded >>> 7
    if (bits & ~0x7fL) == 0 then
      bytes(offset + 1) = bits.toByte
      position = offset + 2
    else
      bytes(offset + 1) = (bits.toInt | 0x80).toByte
      bits = bits >>> 7
      if (bits & ~0x7fL) == 0 then
        bytes(offset + 2) = bits.toByte
        position = offset + 3
      else
        bytes(offset + 2) = (bits.toInt | 0x80).toByte
        bits = bits >>> 7
        if (bits & ~0x7fL) == 0 then
          bytes(offset + 3) = bits.toByte
          position = offset + 4
        else
          bytes(offset + 3) = (bits.toInt | 0x80).toByte
          bits = bits >>> 7
          if (bits & ~0x7fL) == 0 then
            bytes(offset + 4) = bits.toByte
            position = offset + 5
          else
            bytes(offset + 4) = (bits.toInt | 0x80).toByte
            bits = bits >>> 7
            if (bits & ~0x7fL) == 0 then
              bytes(offset + 5) = bits.toByte
              position = offset + 6
            else
              bytes(offset + 5) = (bits.toInt | 0x80).toByte
              bits = bits >>> 7
              if (bits & ~0x7fL) == 0 then
                bytes(offset + 6) = bits.toByte
                position = offset + 7
              else
                bytes(offset + 6) = (bits.toInt | 0x80).toByte
                bits = bits >>> 7
                if (bits & ~0x7fL) == 0 then
                  bytes(offset + 7) = bits.toByte
                  position = offset + 8
                else
                  bytes(offset + 7) = (bits.toInt | 0x80).toByte
                  bits = bits >>> 7
                  if (bits & ~0x7fL) == 0 then
                    bytes(offset + 8) = bits.toByte
                    position = offset + 9
                  else
                    bytes(offset + 8) = (bits.toInt | 0x80).toByte
                    bytes(offset + 9) = (bits >>> 7).toByte
                    position = offset + 10

  /** Grow at a bulk loop's current cursor, keeping earlier bytes visible to reserve. */
  private def growBulk(offset: Int, count: Int): Array[Byte] =
    position = offset
    reserve(count)
    buffer

  override def writeIntArray(values: Vector[Int]): Unit =
    writeArrayStart(values.size)
    var bytes = buffer
    var offset = position
    val iterator = values.iterator
    while iterator.hasNext do
      val value = iterator.next()
      val encoded = (value << 1) ^ (value >> 31)
      if (encoded & ~0x7f) == 0 then
        if offset == bytes.length then bytes = growBulk(offset, 1)
        bytes(offset) = encoded.toByte
        offset += 1
      else
        if bytes.length - offset < 5 then
          // This slow capacity check reserves only this value's actual width,
          // never values.size * 5 (nor five bytes when only two are needed).
          val width = (38 - java.lang.Integer.numberOfLeadingZeros(encoded)) / 7
          if bytes.length - offset < width then bytes = growBulk(offset, width)
        bytes(offset) = (encoded | 0x80).toByte
        var bits = encoded >>> 7
        if (bits & ~0x7f) == 0 then
          bytes(offset + 1) = bits.toByte
          offset += 2
        else
          bytes(offset + 1) = (bits | 0x80).toByte
          bits = bits >>> 7
          if (bits & ~0x7f) == 0 then
            bytes(offset + 2) = bits.toByte
            offset += 3
          else
            bytes(offset + 2) = (bits | 0x80).toByte
            bits = bits >>> 7
            if (bits & ~0x7f) == 0 then
              bytes(offset + 3) = bits.toByte
              offset += 4
            else
              bytes(offset + 3) = (bits | 0x80).toByte
              bytes(offset + 4) = (bits >>> 7).toByte
              offset += 5
    position = offset
    writeArrayEnd()

  override def writeLongArray(values: Vector[Long]): Unit =
    writeArrayStart(values.size)
    var bytes = buffer
    var offset = position
    val iterator = values.iterator
    while iterator.hasNext do
      val value = iterator.next()
      val encoded = (value << 1) ^ (value >> 63)
      if (encoded & ~0x7fL) == 0 then
        if offset == bytes.length then bytes = growBulk(offset, 1)
        bytes(offset) = encoded.toByte
        offset += 1
      else
        if bytes.length - offset < 10 then
          val width = (70 - java.lang.Long.numberOfLeadingZeros(encoded)) / 7
          if bytes.length - offset < width then bytes = growBulk(offset, width)
        bytes(offset) = (encoded.toInt | 0x80).toByte
        var bits = encoded >>> 7
        if (bits & ~0x7fL) == 0 then
          bytes(offset + 1) = bits.toByte
          offset += 2
        else
          bytes(offset + 1) = (bits.toInt | 0x80).toByte
          bits = bits >>> 7
          if (bits & ~0x7fL) == 0 then
            bytes(offset + 2) = bits.toByte
            offset += 3
          else
            bytes(offset + 2) = (bits.toInt | 0x80).toByte
            bits = bits >>> 7
            if (bits & ~0x7fL) == 0 then
              bytes(offset + 3) = bits.toByte
              offset += 4
            else
              bytes(offset + 3) = (bits.toInt | 0x80).toByte
              bits = bits >>> 7
              if (bits & ~0x7fL) == 0 then
                bytes(offset + 4) = bits.toByte
                offset += 5
              else
                bytes(offset + 4) = (bits.toInt | 0x80).toByte
                bits = bits >>> 7
                if (bits & ~0x7fL) == 0 then
                  bytes(offset + 5) = bits.toByte
                  offset += 6
                else
                  bytes(offset + 5) = (bits.toInt | 0x80).toByte
                  bits = bits >>> 7
                  if (bits & ~0x7fL) == 0 then
                    bytes(offset + 6) = bits.toByte
                    offset += 7
                  else
                    bytes(offset + 6) = (bits.toInt | 0x80).toByte
                    bits = bits >>> 7
                    if (bits & ~0x7fL) == 0 then
                      bytes(offset + 7) = bits.toByte
                      offset += 8
                    else
                      bytes(offset + 7) = (bits.toInt | 0x80).toByte
                      bits = bits >>> 7
                      if (bits & ~0x7fL) == 0 then
                        bytes(offset + 8) = bits.toByte
                        offset += 9
                      else
                        bytes(offset + 8) = (bits.toInt | 0x80).toByte
                        bytes(offset + 9) = (bits >>> 7).toByte
                        offset += 10
    position = offset
    writeArrayEnd()

  override def writeFloat(value: Float): Unit =
    if buffer.length - position < 4 then reserve(4)
    val bits = java.lang.Float.floatToRawIntBits(value)
    LittleEndianNumbers.ints.set(buffer, position, bits)
    position += 4

  override def writeDouble(value: Double): Unit =
    if buffer.length - position < 8 then reserve(8)
    val bits = java.lang.Double.doubleToRawLongBits(value)
    LittleEndianNumbers.longs.set(buffer, position, bits)
    position += 8

  /** Rejects unpaired UTF-16 before changing output. Tiny strings avoid a temporary array. */
  override def writeString(value: String): Unit =
    if value.isEmpty then
      writeLong(0L)
    else if value.length <= 16 then writeShortString(value)
    else writeLongString(value)

  private def writeLongString(value: String): Unit =
    val bytes = StrictUtf8.encode(value)
    val headerSize = (39 - java.lang.Integer.numberOfLeadingZeros(bytes.length)) / 7
    require(bytes.length <= Int.MaxValue - headerSize, "Encoded datum exceeds maximum array size")
    reserve(bytes.length + headerSize)
    writeLong(bytes.length.toLong)
    System.arraycopy(bytes, 0, buffer, position, bytes.length)
    position += bytes.length

  /** At most 16 UTF-16 units produce at most 48 bytes, hence a one-byte length prefix. */
  private def writeShortString(value: String): Unit =
    var encodedLength = 0
    var index = 0
    while index < value.length do
      val char = value.charAt(index)
      if char < 0x80 then encodedLength += 1
      else if char < 0x800 then encodedLength += 2
      else if Character.isHighSurrogate(char) then
        require(index + 1 < value.length && Character.isLowSurrogate(value.charAt(index + 1)),
          s"Unpaired UTF-16 surrogate at index $index")
        encodedLength += 4
        index += 1
      else
        require(!Character.isLowSurrogate(char), s"Unpaired UTF-16 surrogate at index $index")
        encodedLength += 3
      index += 1

    reserve(encodedLength + 1)
    val destination = buffer
    var offset = position
    destination(offset) = (encodedLength << 1).toByte
    offset += 1
    index = 0
    while index < value.length do
      val char = value.charAt(index).toInt
      if char < 0x80 then
        destination(offset) = char.toByte
        offset += 1
      else if char < 0x800 then
        destination(offset) = (0xc0 | (char >>> 6)).toByte
        destination(offset + 1) = (0x80 | (char & 0x3f)).toByte
        offset += 2
      else if char >= 0xd800 && char <= 0xdbff then
        val codePoint = Character.toCodePoint(value.charAt(index), value.charAt(index + 1))
        destination(offset) = (0xf0 | (codePoint >>> 18)).toByte
        destination(offset + 1) = (0x80 | ((codePoint >>> 12) & 0x3f)).toByte
        destination(offset + 2) = (0x80 | ((codePoint >>> 6) & 0x3f)).toByte
        destination(offset + 3) = (0x80 | (codePoint & 0x3f)).toByte
        offset += 4
        index += 1
      else
        destination(offset) = (0xe0 | (char >>> 12)).toByte
        destination(offset + 1) = (0x80 | ((char >>> 6) & 0x3f)).toByte
        destination(offset + 2) = (0x80 | (char & 0x3f)).toByte
        offset += 3
      index += 1
    position = offset

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
