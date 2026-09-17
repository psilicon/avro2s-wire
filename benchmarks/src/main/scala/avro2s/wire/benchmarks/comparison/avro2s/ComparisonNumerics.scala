/** GENERATED CODE */

package avro2s.wire.benchmarks.comparison.avro2s

import scala.annotation.switch

case class ComparisonNumerics(var active: Boolean, var ratio: Float, var price: Double, var flags: List[Boolean], var floats: List[Float], var doubles: List[Double]) extends org.apache.avro.specific.SpecificRecordBase {
  def this() = this(false, 0, 0, List.empty, List.empty, List.empty)

  override def getSchema: org.apache.avro.Schema = ComparisonNumerics.SCHEMA$

  override def get(field$: Int): AnyRef = {
    (field$: @switch) match {
      case 0 => active.asInstanceOf[AnyRef]
      case 1 => ratio.asInstanceOf[AnyRef]
      case 2 => price.asInstanceOf[AnyRef]
      case 3 => flags match {
        case array =>
          scala.jdk.CollectionConverters.BufferHasAsJava({
            array.map { x =>
              x.asInstanceOf[AnyRef]
            }
          }.toBuffer).asJava
        }
      case 4 => floats match {
        case array =>
          scala.jdk.CollectionConverters.BufferHasAsJava({
            array.map { x =>
              x.asInstanceOf[AnyRef]
            }
          }.toBuffer).asJava
        }
      case 5 => doubles match {
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
      case 0 => this.active = {
        value.asInstanceOf[Boolean]
      }
      case 1 => this.ratio = {
        value.asInstanceOf[Float]
      }
      case 2 => this.price = {
        value.asInstanceOf[Double]
      }
      case 3 => this.flags = {
        val array = value.asInstanceOf[java.util.List[?]]
        scala.jdk.CollectionConverters.IteratorHasAsScala(array.iterator).asScala.map({ value =>
          value.asInstanceOf[Boolean]
        }).toList
      }
      case 4 => this.floats = {
        val array = value.asInstanceOf[java.util.List[?]]
        scala.jdk.CollectionConverters.IteratorHasAsScala(array.iterator).asScala.map({ value =>
          value.asInstanceOf[Float]
        }).toList
      }
      case 5 => this.doubles = {
        val array = value.asInstanceOf[java.util.List[?]]
        scala.jdk.CollectionConverters.IteratorHasAsScala(array.iterator).asScala.map({ value =>
          value.asInstanceOf[Double]
        }).toList
      }
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }
}

object ComparisonNumerics {
  val SCHEMA$: org.apache.avro.Schema = new _root_.org.apache.avro.Schema.Parser().parse("""{"type":"record","name":"ComparisonNumerics","namespace":"avro2s.wire.benchmarks.comparison.avro2s","fields":[{"name":"active","type":"boolean"},{"name":"ratio","type":"float"},{"name":"price","type":"double"},{"name":"flags","type":{"type":"array","items":"boolean"}},{"name":"floats","type":{"type":"array","items":"float"}},{"name":"doubles","type":{"type":"array","items":"double"}}]}""")
}