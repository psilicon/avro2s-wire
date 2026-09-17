/** GENERATED CODE */

package avro2s.wire.benchmarks.comparison.avro2s

import scala.annotation.switch

case class ComparisonInts(var values: List[Int]) extends org.apache.avro.specific.SpecificRecordBase {
  def this() = this(List.empty)

  override def getSchema: org.apache.avro.Schema = ComparisonInts.SCHEMA$

  override def get(field$: Int): AnyRef = {
    (field$: @switch) match {
      case 0 => values match {
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
      case 0 => this.values = {
        val array = value.asInstanceOf[java.util.List[?]]
        scala.jdk.CollectionConverters.IteratorHasAsScala(array.iterator).asScala.map({ value =>
          value.asInstanceOf[Int]
        }).toList
      }
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }
}

object ComparisonInts {
  val SCHEMA$: org.apache.avro.Schema = new _root_.org.apache.avro.Schema.Parser().parse("""{"type":"record","name":"ComparisonInts","namespace":"avro2s.wire.benchmarks.comparison.avro2s","fields":[{"name":"values","type":{"type":"array","items":"int"}}]}""")
}