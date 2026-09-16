package avrogen.runtime

trait AvroInput:
  def readNull(): Unit = ()
  def readBoolean(): Boolean
  def readInt(): Int
  def readLong(): Long
  def readFloat(): Float
  def readDouble(): Double
  def readString(): String
  def readBytes(): Bytes
  def readFixed(size: Int): Bytes
  def readEnum(): Int
  def readIndex(): Int
  def readArrayStart(): Long
  def arrayNext(): Long
  def readMapStart(): Long
  def mapNext(): Long

  /** Generated record readers pair these hooks in try/finally for depth accounting. */
  def enterRecord(): Unit = ()
  def leaveRecord(): Unit = ()

trait AvroOutput:
  def writeNull(): Unit = ()
  def writeBoolean(value: Boolean): Unit
  def writeInt(value: Int): Unit
  def writeLong(value: Long): Unit
  def writeFloat(value: Float): Unit
  def writeDouble(value: Double): Unit
  def writeString(value: String): Unit
  def writeBytes(value: Bytes): Unit
  def writeFixed(value: Bytes): Unit
  def writeEnum(value: Int): Unit
  def writeIndex(value: Int): Unit
  def writeArrayStart(size: Int): Unit
  def writeArrayEnd(): Unit
  def writeMapStart(size: Int): Unit
  def writeMapEnd(): Unit
  def startItem(): Unit

/** A matching-schema codec. Writer/reader schema resolution is a separate concern. */
trait AvroCodec[A]:
  def schemaJson: String
  def read(in: AvroInput): A
  def write(value: A, out: AvroOutput): Unit

  final def encode(value: A): Array[Byte] =
    val out = new BinaryOutput()
    write(value, out)
    out.toByteArray

  /** Reads one complete datum; malformed, truncated and trailing data are rejected. */
  final def decode(bytes: Array[Byte]): A = decode(bytes, DecodeLimits.default)

  final def decode(bytes: Array[Byte], limits: DecodeLimits): A =
    val in = new BinaryInput(bytes, limits)
    val value = read(in)
    in.requireEnd()
    value

object AvroCodec:
  def apply[A](using codec: AvroCodec[A]): AvroCodec[A] = codec

/** All limits apply to a single input instance; collection items are cumulative. */
final case class DecodeLimits(
    maxInputBytes: Int = 64 * 1024 * 1024,
    maxStringBytes: Int = 16 * 1024 * 1024,
    maxBytesLength: Int = 64 * 1024 * 1024,
    maxCollectionItems: Long = 1000000L,
    maxNestingDepth: Int = 128
):
  require(maxInputBytes >= 0, "maxInputBytes must be non-negative")
  require(maxStringBytes >= 0, "maxStringBytes must be non-negative")
  require(maxBytesLength >= 0, "maxBytesLength must be non-negative")
  require(maxCollectionItems >= 0, "maxCollectionItems must be non-negative")
  require(maxNestingDepth >= 0, "maxNestingDepth must be non-negative")

object DecodeLimits:
  val default: DecodeLimits = DecodeLimits()

/** Invalid Avro binary data or a configured decoding resource limit violation. */
final class AvroDecodingException(message: String) extends IllegalArgumentException(message)
