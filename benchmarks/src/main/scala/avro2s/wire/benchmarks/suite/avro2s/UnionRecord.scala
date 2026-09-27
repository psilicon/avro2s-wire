/** GENERATED CODE */

package avro2s.wire.benchmarks.suite.avro2s

import scala.annotation.switch

case class UnionRecord(var f0: Long, var f1: Long, var f2: Long, var f3: Long) extends org.apache.avro.specific.SpecificRecordBase {
  def this() = this(0, 0, 0, 0)

  override def getSchema: org.apache.avro.Schema = UnionRecord.SCHEMA$

  override def get(field$: Int): AnyRef = {
    (field$: @switch) match {
      case 0 => f0.asInstanceOf[AnyRef]
      case 1 => f1.asInstanceOf[AnyRef]
      case 2 => f2.asInstanceOf[AnyRef]
      case 3 => f3.asInstanceOf[AnyRef]
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }

  override def put(field$: Int, value: Any): Unit = {
    (field$: @switch) match {
      case 0 => this.f0 = {
        value.asInstanceOf[Long]
      }
      case 1 => this.f1 = {
        value.asInstanceOf[Long]
      }
      case 2 => this.f2 = {
        value.asInstanceOf[Long]
      }
      case 3 => this.f3 = {
        value.asInstanceOf[Long]
      }
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }
}

object UnionRecord {
  @scala.annotation.static val SCHEMA$: org.apache.avro.Schema = new _root_.org.apache.avro.Schema.Parser().parse("""{"type":"record","name":"UnionRecord","namespace":"avro2s.wire.benchmarks.suite.avro2s","fields":[{"name":"f0","type":"long"},{"name":"f1","type":"long"},{"name":"f2","type":"long"},{"name":"f3","type":"long"}]}""")
}