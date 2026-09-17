/** GENERATED CODE */

package avro2s.wire.benchmarks.comparison.avro2s

import scala.annotation.switch

case class ComparisonNested(var id: Long, var choices: List[Option[Int | String | avro2s.wire.benchmarks.comparison.avro2s.ComparisonLeaf]], var children: List[avro2s.wire.benchmarks.comparison.avro2s.ComparisonNested]) extends org.apache.avro.specific.SpecificRecordBase {
  def this() = this(0, List.empty, List.empty)

  override def getSchema: org.apache.avro.Schema = ComparisonNested.SCHEMA$

  override def get(field$: Int): AnyRef = {
    (field$: @switch) match {
      case 0 => id.asInstanceOf[AnyRef]
      case 1 => choices match {
        case array =>
          scala.jdk.CollectionConverters.BufferHasAsJava({
            array.map {
              case Some(x: Int) => x.asInstanceOf[AnyRef]
              case Some(x: String) => x.asInstanceOf[AnyRef]
              case Some(x: avro2s.wire.benchmarks.comparison.avro2s.ComparisonLeaf) => x.asInstanceOf[AnyRef]
              case None => null.asInstanceOf[AnyRef]
            }
          }.toBuffer).asJava
        }
      case 2 => children match {
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
      case 1 => this.choices = {
        val array = value.asInstanceOf[java.util.List[?]]
        scala.jdk.CollectionConverters.IteratorHasAsScala(array.iterator).asScala.map({ value =>
          value match {
            case null => None
            case x: Int => Option(x)
            case x: org.apache.avro.util.Utf8 => Option(x.toString)
            case x: avro2s.wire.benchmarks.comparison.avro2s.ComparisonLeaf => Option(x.asInstanceOf[Int | String | avro2s.wire.benchmarks.comparison.avro2s.ComparisonLeaf])
            case _ => throw new org.apache.avro.AvroRuntimeException("Unexpected type: " + value.getClass.getName)
          }
        }).toList
      }
      case 2 => this.children = {
        val array = value.asInstanceOf[java.util.List[?]]
        scala.jdk.CollectionConverters.IteratorHasAsScala(array.iterator).asScala.map({ value =>
          value.asInstanceOf[avro2s.wire.benchmarks.comparison.avro2s.ComparisonNested]
        }).toList
      }
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }
}

object ComparisonNested {
  val SCHEMA$: org.apache.avro.Schema = new _root_.org.apache.avro.Schema.Parser().parse("""{"type":"record","name":"ComparisonNested","namespace":"avro2s.wire.benchmarks.comparison.avro2s","fields":[{"name":"id","type":"long"},{"name":"choices","type":{"type":"array","items":["null","int","string",{"type":"record","name":"ComparisonLeaf","fields":[{"name":"value","type":"long"}]}]}},{"name":"children","type":{"type":"array","items":"ComparisonNested"}}]}""")
}