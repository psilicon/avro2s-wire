/** GENERATED CODE */

package avro2s.wire.benchmarks.comparison.avro2s

import scala.annotation.switch

case class ComparisonBytes(var value: Array[Byte]) extends org.apache.avro.specific.SpecificRecordBase {
  def this() = this(Array[Byte]())

  override def getSchema: org.apache.avro.Schema = ComparisonBytes.SCHEMA$

  override def get(field$: Int): AnyRef = {
    (field$: @switch) match {
      case 0 => java.nio.ByteBuffer.wrap(value).asInstanceOf[AnyRef]
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }

  override def put(field$: Int, value: Any): Unit = {
    (field$: @switch) match {
      case 0 => this.value = {
        val buffer = value.asInstanceOf[java.nio.ByteBuffer]
        val array = Array.ofDim[Byte](buffer.remaining()); buffer.get(array); array
      }
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }
}

object ComparisonBytes {
  val SCHEMA$: org.apache.avro.Schema = new _root_.org.apache.avro.Schema.Parser().parse("""{"type":"record","name":"ComparisonBytes","namespace":"avro2s.wire.benchmarks.comparison.avro2s","fields":[{"name":"value","type":"bytes"}]}""")
}