/** GENERATED CODE */

package avro2s.wire.benchmarks.suite.avro2s

import scala.annotation.switch

case class TextValue(var value: String) extends org.apache.avro.specific.SpecificRecordBase {
  def this() = this("")

  override def getSchema: org.apache.avro.Schema = TextValue.SCHEMA$

  override def get(field$: Int): AnyRef = {
    (field$: @switch) match {
      case 0 => value.asInstanceOf[AnyRef]
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }

  override def put(field$: Int, value: Any): Unit = {
    (field$: @switch) match {
      case 0 => this.value = {
        value.toString.asInstanceOf[String]
      }
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }
}

object TextValue {
  @scala.annotation.static val SCHEMA$: org.apache.avro.Schema = new _root_.org.apache.avro.Schema.Parser().parse("""{"type":"record","name":"TextValue","namespace":"avro2s.wire.benchmarks.suite.avro2s","fields":[{"name":"value","type":"string"}]}""")
}