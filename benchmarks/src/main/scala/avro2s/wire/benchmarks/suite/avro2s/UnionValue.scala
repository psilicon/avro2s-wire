/** GENERATED CODE */

package avro2s.wire.benchmarks.suite.avro2s

import scala.annotation.switch

case class UnionValue(var value: Int | String | avro2s.wire.benchmarks.suite.avro2s.UnionRecord) extends org.apache.avro.specific.SpecificRecordBase {
  def this() = this(0)

  override def getSchema: org.apache.avro.Schema = UnionValue.SCHEMA$

  override def get(field$: Int): AnyRef = {
    (field$: @switch) match {
      case 0 => value match {
        case x: Int => x.asInstanceOf[AnyRef]
        case x: String => x.asInstanceOf[AnyRef]
        case x: avro2s.wire.benchmarks.suite.avro2s.UnionRecord => x.asInstanceOf[AnyRef]
      }
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }

  override def put(field$: Int, value: Any): Unit = {
    (field$: @switch) match {
      case 0 => this.value = {
        value match {
          case x: Int => x
          case x: java.lang.CharSequence => x.toString
          case x: avro2s.wire.benchmarks.suite.avro2s.UnionRecord => x.asInstanceOf[Int | String | avro2s.wire.benchmarks.suite.avro2s.UnionRecord]
          case _ => throw new org.apache.avro.AvroRuntimeException("Unexpected type: " + value.getClass.getName)
        }
      }
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }
}

object UnionValue {
  @scala.annotation.static val SCHEMA$: org.apache.avro.Schema = new _root_.org.apache.avro.Schema.Parser().parse("""{"type":"record","name":"UnionValue","namespace":"avro2s.wire.benchmarks.suite.avro2s","fields":[{"name":"value","type":["int","string",{"type":"record","name":"UnionRecord","fields":[{"name":"f0","type":"long"},{"name":"f1","type":"long"},{"name":"f2","type":"long"},{"name":"f3","type":"long"}]}]}]}""")
}