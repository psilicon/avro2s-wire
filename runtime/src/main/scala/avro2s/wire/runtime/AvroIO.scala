package avro2s.wire.runtime

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
  /** Skip APIs allow schema resolution to discard fields without constructing their values. */
  def skipString(): Unit = { readString(); () }
  def skipBytes(): Unit = { readBytes(); () }
  def skipFixed(size: Int): Unit = { readFixed(size); () }
  /** Schema promotions. Native inputs enforce both source and destination byte limits. */
  def readStringAsBytes(): Bytes =
    Bytes.unsafeWrap(readString().getBytes(java.nio.charset.StandardCharsets.UTF_8))
  def readBytesAsString(): String =
    val decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
      .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    try decoder.decode(java.nio.ByteBuffer.wrap(readBytes().unsafeArray)).toString
    catch case _: java.nio.charset.CharacterCodingException =>
      throw new AvroDecodingException("Invalid UTF-8 in bytes-to-string promotion")
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

  /** Whole-array hooks. Defaults preserve every encoder block/item callback;
    * native outputs may override them without changing generated model types.
    */
  def writeIntArray(values: Vector[Int]): Unit =
    writeArrayStart(values.size)
    val iterator = values.iterator
    while iterator.hasNext do
      startItem()
      writeInt(iterator.next())
    writeArrayEnd()

  def writeLongArray(values: Vector[Long]): Unit =
    writeArrayStart(values.size)
    val iterator = values.iterator
    while iterator.hasNext do
      startItem()
      writeLong(iterator.next())
    writeArrayEnd()

/** The generated model's decimal representation, also used by schema resolution. */
enum DecimalRepresentation:
  case Scala, Java

/** Value traversal used by a codec and by its schema-resolving readers. */
enum CodecExecution:
  case Direct, StackSafe

/** A matching-schema codec. Writer/reader schema resolution is a separate concern. */
trait AvroCodec[A]:
  def execution: CodecExecution = CodecExecution.Direct
  def schemaJson: String
  def decimalRepresentation: DecimalRepresentation = DecimalRepresentation.Scala
  /** Avro logical names decoded as their underlying storage types by this model. */
  def rawLogicalTypes: Set[String] = Set.empty
  def read(in: AvroInput): A
  def write(value: A, out: AvroOutput): Unit

  /** Construction hook for optional schema resolution. Matching-schema reads do not use it.
    * Records receive reader-ordered fields; enums receive their ordinal; fixed types receive
    * their decoded underlying value. Generated implementations construct the final model.
    */
  def construct(values: Array[Any]): A =
    throw new UnsupportedOperationException("This codec does not provide schema-resolution construction")

  /** Lazily looks up a reachable named codec, so recursive models do not initialise recursively. */
  def namedCodec(fullName: String): AvroCodec[?] =
    throw new IllegalArgumentException(s"No generated codec for named schema: $fullName")

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

/** Optional resource ceilings for native decoding. None imposes no policy ceiling;
  * Some(0) permits no consumption of that resource. These are not Avro format limits.
  * Native readers validate buffer bounds and malformed encodings independently.
  * All limits apply to a single input instance; collection items are cumulative.
  */
final case class DecodeLimits(
    maxInputBytes: Option[Int] = None,
    maxStringBytes: Option[Int] = None,
    maxBytesLength: Option[Int] = None,
    maxCollectionItems: Option[Long] = None,
    maxNestingDepth: Option[Int] = None
):
  require(maxInputBytes.forall(_ >= 0), "maxInputBytes must be non-negative")
  require(maxStringBytes.forall(_ >= 0), "maxStringBytes must be non-negative")
  require(maxBytesLength.forall(_ >= 0), "maxBytesLength must be non-negative")
  require(maxCollectionItems.forall(_ >= 0), "maxCollectionItems must be non-negative")
  require(maxNestingDepth.forall(_ >= 0), "maxNestingDepth must be non-negative")

object DecodeLimits:
  val default: DecodeLimits = DecodeLimits()

/** Invalid Avro binary data or a configured decoding resource limit violation. */
final class AvroDecodingException(message: String) extends IllegalArgumentException(message)
