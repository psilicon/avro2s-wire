package avro2s.wire.fixtures

/** A native round trip; this module's main classpath contains no Apache Avro. */
object Demo:
  def main(args: Array[String]): Unit =
    val trade = Trade(123L, "ABC", 42.5, Vector(10, 20))
    val bytes = Trade.codec.encode(trade)
    val restored = Trade.codec.decode(bytes)
    require(restored == trade)
    println(s"$restored\nNative Avro round trip: ${bytes.length} bytes")
