/** GENERATED CODE */

package avro2s.wire.benchmarks.avro2s

import scala.annotation.switch

case class Trade(var id: Long, var symbol: String, var price: Double, var quantities: List[Int]) extends org.apache.avro.specific.SpecificRecordBase {
  def this() = this(0, "", 0, List.empty)

  override def getSchema: org.apache.avro.Schema = Trade.SCHEMA$

  override def get(field$: Int): AnyRef = {
    (field$: @switch) match {
      case 0 => id.asInstanceOf[AnyRef]
      case 1 => symbol.asInstanceOf[AnyRef]
      case 2 => price.asInstanceOf[AnyRef]
      case 3 => quantities match {
        case array =>
          scala.jdk.CollectionConverters.BufferHasAsJava({
            array.map { x =>
              x.asInstanceOf[AnyRef]
            }
          }.toBuffer).asJava
        }
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }

  override def put(field$: Int, value: Any): Unit = {
    (field$: @switch) match {
      case 0 => this.id = {
        value.asInstanceOf[Long]
      }
      case 1 => this.symbol = {
        value.toString.asInstanceOf[String]
      }
      case 2 => this.price = {
        value.asInstanceOf[Double]
      }
      case 3 => this.quantities = {
        val array = value.asInstanceOf[java.util.List[?]]
        scala.jdk.CollectionConverters.IteratorHasAsScala(array.iterator).asScala.map({ value =>
          value.asInstanceOf[Int]
        }).toList
      }
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }
}

object Trade {
  val SCHEMA$: org.apache.avro.Schema = new _root_.org.apache.avro.Schema.Parser().parse("""{"type":"record","name":"Trade","namespace":"avro2s.wire.benchmarks.avro2s","fields":[{"name":"id","type":"long"},{"name":"symbol","type":"string"},{"name":"price","type":"double"},{"name":"quantities","type":{"type":"array","items":"int"}}]}""")
}