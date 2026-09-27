/** GENERATED CODE */

package avro2s.wire.benchmarks.suite.avro2s

import scala.annotation.switch

case class EnumValue(var value: avro2s.wire.benchmarks.suite.avro2s.EightSymbols) extends org.apache.avro.specific.SpecificRecordBase {
  def this() = this(null)

  override def getSchema: org.apache.avro.Schema = EnumValue.SCHEMA$

  override def get(field$: Int): AnyRef = {
    (field$: @switch) match {
      case 0 => _root_.avro2s.wire.benchmarks.suite.avro2s.EightSymbols.toAvroSymbol$(value).asInstanceOf[AnyRef]
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }

  override def put(field$: Int, value: Any): Unit = {
    (field$: @switch) match {
      case 0 => this.value = {
        value match { case x: _root_.avro2s.wire.benchmarks.suite.avro2s.EightSymbols => x; case x => _root_.avro2s.wire.benchmarks.suite.avro2s.EightSymbols.fromAvroSymbol(x.toString) }
      }
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }
}

object EnumValue {
  @scala.annotation.static val SCHEMA$: org.apache.avro.Schema = new _root_.org.apache.avro.Schema.Parser().parse("""{"type":"record","name":"EnumValue","namespace":"avro2s.wire.benchmarks.suite.avro2s","fields":[{"name":"value","type":{"type":"enum","name":"EightSymbols","symbols":["S0","S1","S2","S3","S4","S5","S6","S7"]}}]}""")
}