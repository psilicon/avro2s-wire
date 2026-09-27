/** GENERATED CODE */

package avro2s.wire.benchmarks.suite.avro2s

import scala.annotation.switch

case class BooleanValue(var value: Boolean) extends org.apache.avro.specific.SpecificRecordBase {
  def this() = this(false)

  override def getSchema: org.apache.avro.Schema = BooleanValue.SCHEMA$

  override def get(field$: Int): AnyRef = {
    (field$: @switch) match {
      case 0 => value.asInstanceOf[AnyRef]
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }

  override def put(field$: Int, value: Any): Unit = {
    (field$: @switch) match {
      case 0 => this.value = {
        value.asInstanceOf[Boolean]
      }
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }
}

object BooleanValue {
  @scala.annotation.static val SCHEMA$: org.apache.avro.Schema = new _root_.org.apache.avro.Schema.Parser().parse("""{"type":"record","name":"BooleanValue","namespace":"avro2s.wire.benchmarks.suite.avro2s","fields":[{"name":"value","type":"boolean"}]}""")
}