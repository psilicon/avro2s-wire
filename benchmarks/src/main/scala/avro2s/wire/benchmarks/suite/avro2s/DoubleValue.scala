/** GENERATED CODE */

package avro2s.wire.benchmarks.suite.avro2s

import scala.annotation.switch

case class DoubleValue(var value: Double) extends org.apache.avro.specific.SpecificRecordBase {
  def this() = this(0)

  override def getSchema: org.apache.avro.Schema = DoubleValue.SCHEMA$

  override def get(field$: Int): AnyRef = {
    (field$: @switch) match {
      case 0 => value.asInstanceOf[AnyRef]
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }

  override def put(field$: Int, value: Any): Unit = {
    (field$: @switch) match {
      case 0 => this.value = {
        value.asInstanceOf[Double]
      }
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }
}

object DoubleValue {
  @scala.annotation.static val SCHEMA$: org.apache.avro.Schema = new _root_.org.apache.avro.Schema.Parser().parse("""{"type":"record","name":"DoubleValue","namespace":"avro2s.wire.benchmarks.suite.avro2s","fields":[{"name":"value","type":"double"}]}""")
}