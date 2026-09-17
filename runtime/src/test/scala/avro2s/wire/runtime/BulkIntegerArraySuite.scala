package avro2s.wire.runtime

final class BulkIntegerArraySuite extends munit.FunSuite:
  private def wire(value: BigInt): Vector[Byte] =
    var bits = if value >= 0 then value * 2 else -value * 2 - 1
    val result = Vector.newBuilder[Byte]
    while bits >= 128 do
      result += ((bits % 128).toInt + 128).toByte
      bits /= 128
    result += bits.toByte
    result.result()

  private def arrayWire(values: Vector[BigInt]): Vector[Byte] =
    (if values.isEmpty then Vector.empty else wire(BigInt(values.size))) ++ values.flatMap(wire) ++ Vector[Byte](0)

  private val intValues = ((0 until 32).flatMap { bit =>
    val edge = BigInt(1) << bit
    Vector(edge - 1, edge, -edge, -edge - 1).filter(_.isValidInt).map(_.toInt)
  } ++ Vector(Int.MinValue, Int.MaxValue)).toVector.distinct

  private val longValues = ((0 until 64).flatMap { bit =>
    val edge = BigInt(1) << bit
    Vector(edge - 1, edge, -edge, -edge - 1).filter(_.isValidLong).map(_.toLong)
  } ++ Vector(Long.MinValue, Long.MaxValue)).toVector.distinct

  test("bulk integer arrays match independent wire arithmetic across widths, slices and buffer growth") {
    val integers = Vector(Vector.empty[Int], Vector(0), intValues, intValues.reverse,
      Vector.tabulate(1100)(i => intValues(i % intValues.size)).slice(17, 1060))
    val longs = Vector(Vector.empty[Long], Vector(0L), longValues, longValues.reverse,
      Vector.tabulate(1100)(i => longValues(i % longValues.size)).slice(17, 1060))
    for capacity <- Vector(0, 1, 4, 5, 9, 10, 31, 32, 33, 256) do
      val out = new BinaryOutput(capacity)
      for prefixSize <- Vector(0, 1, 7) do
        val prefix = Vector.fill[Byte](prefixSize)(0x55)
        integers.foreach { values =>
          out.reset()
          out.writeFixed(Bytes.fromArray(prefix.toArray))
          out.writeIntArray(values)
          out.writeLong(1234567L)
          assertEquals(out.toByteArray.toVector, prefix ++ arrayWire(values.map(BigInt(_))) ++ wire(BigInt(1234567L)))
        }
        longs.foreach { values =>
          out.reset()
          out.writeFixed(Bytes.fromArray(prefix.toArray))
          out.writeLongArray(values)
          out.writeInt(-98765)
          assertEquals(out.toByteArray.toVector, prefix ++ arrayWire(values.map(BigInt(_))) ++ wire(BigInt(-98765)))
        }
  }

  test("bulk arrays publish complete output and returned arrays retain defensive ownership") {
    val out = new BinaryOutput(1)
    val values = Vector.tabulate(1025)(i => if i % 2 == 0 then Long.MinValue + i else Long.MaxValue - i)
    out.writeLongArray(values)
    val first = out.toByteArray
    val original = first.clone()
    assertEquals(out.size, original.length)
    first(0) = (first(0) ^ 0xff).toByte
    assertEquals(out.toByteArray.toVector, original.toVector)
    out.writeIntArray(Vector(Int.MinValue, Int.MaxValue))
    assertEquals(original.toVector, arrayWire(values.map(BigInt(_))))
    val in = new BinaryInput(out.toByteArray)
    assertEquals(in.readArrayStart(), values.size.toLong)
    values.foreach(expected => assertEquals(in.readLong(), expected))
    assertEquals(in.arrayNext(), 0L)
    assertEquals(in.readArrayStart(), 2L)
    assertEquals(in.readInt(), Int.MinValue)
    assertEquals(in.readInt(), Int.MaxValue)
    assertEquals(in.arrayNext(), 0L)
    in.requireEnd()
    out.reset()
    out.writeIntArray(Vector.empty)
    out.writeLongArray(Vector.empty)
    assertEquals(out.toByteArray.toVector, Vector[Byte](0, 0))
  }

  test("generic bulk defaults preserve array and per-item callback order") {
    final class RecordingOutput extends AvroOutput:
      val events = scala.collection.mutable.ArrayBuffer.empty[String]
      def writeInt(value: Int): Unit = events += s"int:$value"
      def writeLong(value: Long): Unit = events += s"long:$value"
      def writeArrayStart(size: Int): Unit = events += s"start:$size"
      def startItem(): Unit = events += "item"
      def writeArrayEnd(): Unit = events += "end"
      private def unexpected(): Nothing = throw new AssertionError("Unexpected primitive callback")
      def writeBoolean(value: Boolean): Unit = unexpected()
      def writeFloat(value: Float): Unit = unexpected()
      def writeDouble(value: Double): Unit = unexpected()
      def writeString(value: String): Unit = unexpected()
      def writeBytes(value: Bytes): Unit = unexpected()
      def writeFixed(value: Bytes): Unit = unexpected()
      def writeEnum(value: Int): Unit = unexpected()
      def writeIndex(value: Int): Unit = unexpected()
      def writeMapStart(size: Int): Unit = unexpected()
      def writeMapEnd(): Unit = unexpected()
    val out = new RecordingOutput()
    out.writeIntArray(Vector(-1, 300))
    out.writeLongArray(Vector(Long.MinValue, Long.MaxValue))
    out.writeIntArray(Vector.empty)
    out.writeLongArray(Vector.empty)
    assertEquals(out.events.toVector, Vector("start:2", "item", "int:-1", "item", "int:300", "end",
      "start:2", "item", s"long:${Long.MinValue}", "item", s"long:${Long.MaxValue}", "end",
      "start:0", "end", "start:0", "end"))
  }
