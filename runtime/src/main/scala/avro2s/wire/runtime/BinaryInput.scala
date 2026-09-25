package avro2s.wire.runtime

import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.lang.invoke.{MethodHandles, VarHandle}
import java.nio.ByteOrder

/** Plain byte-array views permit unaligned access and use public JDK APIs. */
private[runtime] object LittleEndianNumbers:
  val ints: VarHandle = MethodHandles.byteArrayViewVarHandle(classOf[Array[Int]], ByteOrder.LITTLE_ENDIAN)
  val longs: VarHandle = MethodHandles.byteArrayViewVarHandle(classOf[Array[Long]], ByteOrder.LITTLE_ENDIAN)

/** Reads a caller-owned array without copying it. Do not mutate it during decoding.
  * Returned Bytes values own separate arrays. Instances are not thread-safe.
  */
final class BinaryInput(bytes: Array[Byte], limits: DecodeLimits = DecodeLimits.default) extends AvroInput:
  private var position = 0
  private var boundary = bytes.length
  private var nesting = 0L
  private var totalItems = 0L
  private final class Block(val isMap: Boolean, val parentBoundary: Int, var end: Int, val previous: Block)
  private var blocks: Block = null
  private var nextBlockEnd = -1
  private val promotedBytesLimit = (limits.maxStringBytes, limits.maxBytesLength) match
    case (Some(string), Some(bytes)) => Some(math.min(string, bytes))
    case (string, bytes) => string.orElse(bytes)
  if limits.maxInputBytes.exists(bytes.length > _) then fail("Input exceeds maxInputBytes")

  def remaining: Int = bytes.length - position

  def requireEnd(): Unit =
    if blocks != null then fail("Unfinished collection")
    if nesting != 0 then fail("Unfinished record")
    if remaining != 0 then fail(s"Trailing data: $remaining bytes")

  private def fail(message: String): Nothing =
    throw new AvroDecodingException(s"$message at byte $position")

  private def requireAvailable(count: Int): Unit =
    if count < 0 || count > boundary - position then fail("Truncated binary data or collection block")

  private def byte(): Int =
    requireAvailable(1)
    val result = bytes(position) & 0xff
    position += 1
    result

  override def readBoolean(): Boolean = byte() match
    case 0 => false
    case 1 => true
    case _ => fail("Boolean must be encoded as 0 or 1")

  override def readInt(): Int =
    val offset = position
    if offset >= boundary then fail("Truncated binary data or collection block")
    val first = bytes(offset).toInt
    if first >= 0 then
      position = offset + 1
      (first >>> 1) ^ -(first & 1)
    else if boundary - offset >= 5 then readIntMultiple(first, offset)
    else
      position = offset + 1
      readIntTail(first)

  /** The caller checks the complete maximum width, including sized-block bounds. */
  private def readIntMultiple(first: Int, offset: Int): Int =
    var bits = first & 0x7f
    var current = bytes(offset + 1).toInt
    bits |= (current & 0x7f) << 7
    var size = 2
    if current < 0 then
      current = bytes(offset + 2).toInt
      bits |= (current & 0x7f) << 14
      size = 3
      if current < 0 then
        current = bytes(offset + 3).toInt
        bits |= (current & 0x7f) << 21
        size = 4
        if current < 0 then
          current = bytes(offset + 4).toInt
          if (current & 0xf0) != 0 then
            position = offset + 5
            fail("Invalid int varint")
          bits |= current << 28
          size = 5
    position = offset + size
    (bits >>> 1) ^ -(bits & 1)

  /** Near an input/block boundary each remaining byte must be checked separately. */
  private def readIntTail(first: Int): Int =
    var bits = first & 0x7f
    var shift = 7
    while shift < 35 do
      val current = byte()
      if shift == 28 && (current & 0xf0) != 0 then fail("Invalid int varint")
      bits |= (current & 0x7f) << shift
      if (current & 0x80) == 0 then return (bits >>> 1) ^ -(bits & 1)
      shift += 7
    fail("Invalid int varint")

  override def readLong(): Long =
    val offset = position
    if offset >= boundary then fail("Truncated binary data or collection block")
    val first = bytes(offset).toInt
    if first >= 0 then
      position = offset + 1
      ((first >>> 1) ^ -(first & 1)).toLong
    else if boundary - offset >= 10 then readLongMultiple(first, offset)
    else
      position = offset + 1
      readLongTail(first)

  private def readLongMultiple(first: Int, offset: Int): Long =
    var bits = (first & 0x7f).toLong
    var current = bytes(offset + 1).toInt
    bits |= (current & 0x7f).toLong << 7
    var size = 2
    if current < 0 then
      current = bytes(offset + 2).toInt
      bits |= (current & 0x7f).toLong << 14
      size = 3
      if current < 0 then
        current = bytes(offset + 3).toInt
        bits |= (current & 0x7f).toLong << 21
        size = 4
        if current < 0 then
          current = bytes(offset + 4).toInt
          bits |= (current & 0x7f).toLong << 28
          size = 5
          if current < 0 then
            current = bytes(offset + 5).toInt
            bits |= (current & 0x7f).toLong << 35
            size = 6
            if current < 0 then
              current = bytes(offset + 6).toInt
              bits |= (current & 0x7f).toLong << 42
              size = 7
              if current < 0 then
                current = bytes(offset + 7).toInt
                bits |= (current & 0x7f).toLong << 49
                size = 8
                if current < 0 then
                  current = bytes(offset + 8).toInt
                  bits |= (current & 0x7f).toLong << 56
                  size = 9
                  if current < 0 then
                    current = bytes(offset + 9).toInt
                    if (current & 0xfe) != 0 then
                      position = offset + 10
                      fail("Invalid long varint")
                    bits |= current.toLong << 63
                    size = 10
    position = offset + size
    (bits >>> 1) ^ -(bits & 1L)

  private def readLongTail(first: Int): Long =
    var bits = (first & 0x7f).toLong
    var shift = 7
    while shift < 70 do
      val current = byte()
      if shift == 63 && (current & 0xfe) != 0 then fail("Invalid long varint")
      bits |= (current & 0x7f).toLong << shift
      if (current & 0x80) == 0 then return (bits >>> 1) ^ -(bits & 1L)
      shift += 7
    fail("Invalid long varint")

  override def readFloat(): Float =
    requireAvailable(4)
    val bits = ByteArrayAccess.getIntLE(bytes, position)
    position += 4
    java.lang.Float.intBitsToFloat(bits)

  override def readDouble(): Double =
    requireAvailable(8)
    val bits = ByteArrayAccess.getLongLE(bytes, position)
    position += 8
    java.lang.Double.longBitsToDouble(bits)

  private def length(maximum: Option[Int], kind: String): Int =
    val size = readLong()
    // Lengths are encoded as longs, but this input uses Int-indexed byte arrays.
    // Validate before narrowing even when no resource ceiling is configured.
    if size < 0 || size > Int.MaxValue then fail(s"Invalid $kind length $size")
    if maximum.exists(size > _.toLong) then fail(s"$kind length $size exceeds configured limit")
    requireAvailable(size.toInt)
    size.toInt

  override def readString(): String =
    val size = length(limits.maxStringBytes, "string")
    if size == 0 then return ""
    val result = new String(bytes, position, size, StandardCharsets.UTF_8)
    if StrictUtf8.mayHaveDecodingReplacement(result) then validateUtf8(position, size)
    position += size
    result

  override def readBytes(): Bytes = readFixed(length(limits.maxBytesLength, "bytes"))

  override def readStringAsBytes(): Bytes =
    val size = length(promotedBytesLimit, "promoted string/bytes")
    validateUtf8(position, size)
    val result = Bytes.unsafeWrap(Arrays.copyOfRange(bytes, position, position + size))
    position += size
    result

  override def readBytesAsString(): String =
    val size = length(promotedBytesLimit, "promoted bytes/string")
    if size == 0 then return ""
    val result = new String(bytes, position, size, StandardCharsets.UTF_8)
    if StrictUtf8.mayHaveDecodingReplacement(result) then validateUtf8(position, size)
    position += size
    result

  override def skipString(): Unit =
    val size = length(limits.maxStringBytes, "string")
    validateUtf8(position, size)
    position += size

  override def skipBytes(): Unit =
    val size = length(limits.maxBytesLength, "bytes")
    position += size

  override def skipFixed(size: Int): Unit =
    if size < 0 then fail(s"Invalid fixed size $size")
    if limits.maxBytesLength.exists(size > _) then fail("Fixed size exceeds maxBytesLength")
    requireAvailable(size)
    position += size

  override def readFixed(size: Int): Bytes =
    if size < 0 then fail(s"Invalid fixed size $size")
    if limits.maxBytesLength.exists(size > _) then fail("Fixed size exceeds maxBytesLength")
    requireAvailable(size)
    val result = Bytes.unsafeWrap(Arrays.copyOfRange(bytes, position, position + size))
    position += size
    result

  override def readEnum(): Int =
    val index = readInt()
    if index < 0 then fail("Negative enum index")
    index

  override def readIndex(): Int =
    val index = readLong()
    if index < 0 || index > Int.MaxValue then fail("Invalid union index")
    index.toInt

  override def enterRecord(): Unit = enterContainer()
  override def leaveRecord(): Unit =
    if nesting <= 0 then fail("Unbalanced record depth")
    nesting -= 1

  private def enterContainer(): Unit =
    if limits.maxNestingDepth.exists(nesting >= _.toLong) then fail("Nesting exceeds maxNestingDepth")
    if nesting == Long.MaxValue then fail("Nesting depth cannot be represented")
    nesting += 1

  override def readArrayStart(): Long = startBlock(isMap = false)
  override def readMapStart(): Long = startBlock(isMap = true)
  override def arrayNext(): Long = nextBlock(isMap = false)
  override def mapNext(): Long = nextBlock(isMap = true)

  private def readBlockHeader(): Long =
    val encodedCount = readLong()
    if encodedCount == Long.MinValue then fail("Invalid collection count")
    val count = if encodedCount < 0 then -encodedCount else encodedCount
    limits.maxCollectionItems.foreach { maximum =>
      if count > maximum - totalItems then fail("Collection items exceed maxCollectionItems")
      totalItems += count
    }
    nextBlockEnd =
      if encodedCount < 0 then
        val size = readLong()
        if size < 0 || size > boundary.toLong - position then fail("Invalid collection block byte size")
        position + size.toInt
      else -1
    count

  private def startBlock(isMap: Boolean): Long =
    val count = readBlockHeader()
    val end = nextBlockEnd
    if count != 0 then
      enterContainer()
      blocks = new Block(isMap, boundary, end, blocks)
      if end >= 0 then boundary = end
    count

  private def nextBlock(isMap: Boolean): Long =
    val block = blocks
    if block == null || block.isMap != isMap then fail("Unbalanced collection blocks")
    if block.end >= 0 && position != block.end then fail("Collection block byte size does not match contents")
    boundary = block.parentBoundary
    val count = readBlockHeader()
    val end = nextBlockEnd
    if count == 0 then
      blocks = block.previous
      nesting -= 1
    else
      block.end = end
      if end >= 0 then boundary = end
    count

  /** Returns true when all bytes are ASCII; validation is identical on either path. */
  private def validateUtf8(start: Int, size: Int): Boolean =
    val end = start + size
    var at = start
    var ascii = true
    def continuation(index: Int, minimum: Int = 0x80, maximum: Int = 0xbf): Unit =
      if index >= end then fail("Truncated UTF-8 sequence")
      val value = bytes(index) & 0xff
      if value < minimum || value > maximum then fail("Invalid UTF-8 sequence")
    while at < end do
      val first = bytes(at) & 0xff
      if first < 0x80 then at += 1
      else if first >= 0xc2 && first <= 0xdf then
        ascii = false
        continuation(at + 1)
        at += 2
      else if first >= 0xe0 && first <= 0xef then
        ascii = false
        if first == 0xe0 then continuation(at + 1, 0xa0)
        else if first == 0xed then continuation(at + 1, maximum = 0x9f)
        else continuation(at + 1)
        continuation(at + 2)
        at += 3
      else if first >= 0xf0 && first <= 0xf4 then
        ascii = false
        if first == 0xf0 then continuation(at + 1, 0x90)
        else if first == 0xf4 then continuation(at + 1, maximum = 0x8f)
        else continuation(at + 1)
        continuation(at + 2)
        continuation(at + 3)
        at += 4
      else fail("Invalid UTF-8 sequence")
    ascii
