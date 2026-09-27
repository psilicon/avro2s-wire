/** GENERATED CODE */

package avro2s.wire.benchmarks.suite.avro2s

import scala.annotation.switch

case class OptionalIntValue(var value: Option[Int]) extends org.apache.avro.specific.SpecificRecordBase {
  def this() = this(None)

  override def getSchema: org.apache.avro.Schema = OptionalIntValue.SCHEMA$

  override def get(field$: Int): AnyRef = {
    (field$: @switch) match {
      case 0 => value match {
        case Some(x: Int) => x.asInstanceOf[AnyRef]
        case None => null.asInstanceOf[AnyRef]
      }
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }

  override def put(field$: Int, value: Any): Unit = {
    (field$: @switch) match {
      case 0 => this.value = {
        value match {
          case null => None
          case x: Int => Option(x)
          case _ => throw new org.apache.avro.AvroRuntimeException("Unexpected type: " + value.getClass.getName)
        }
      }
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }
}

object OptionalIntValue {
  @scala.annotation.static val SCHEMA$: org.apache.avro.Schema = new _root_.org.apache.avro.Schema.Parser().parse("""{"type":"record","name":"OptionalIntValue","namespace":"avro2s.wire.benchmarks.suite.avro2s","fields":[{"name":"value","type":["null","int"]}]}""")
}