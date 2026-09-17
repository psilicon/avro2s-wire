/** GENERATED CODE */

package avro2s.wire.benchmarks.comparison.avro2s

import scala.annotation.switch

case class ComparisonEnumFixed(var kind: avro2s.wire.benchmarks.comparison.avro2s.ComparisonKind, var fingerprint: avro2s.wire.benchmarks.comparison.avro2s.ComparisonFingerprint, var history: List[avro2s.wire.benchmarks.comparison.avro2s.ComparisonKind], var blocks: List[avro2s.wire.benchmarks.comparison.avro2s.ComparisonFingerprint]) extends org.apache.avro.specific.SpecificRecordBase {
  def this() = this(null, new avro2s.wire.benchmarks.comparison.avro2s.ComparisonFingerprint(), List.empty, List.empty)

  override def getSchema: org.apache.avro.Schema = ComparisonEnumFixed.SCHEMA$

  override def get(field$: Int): AnyRef = {
    (field$: @switch) match {
      case 0 => kind.asInstanceOf[AnyRef]
      case 1 => fingerprint.asInstanceOf[AnyRef]
      case 2 => history match {
        case array =>
          scala.jdk.CollectionConverters.BufferHasAsJava({
            array.map { x =>
              x.asInstanceOf[AnyRef]
            }
          }.toBuffer).asJava
        }
      case 3 => blocks match {
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
      case 0 => this.kind = {
        value.asInstanceOf[avro2s.wire.benchmarks.comparison.avro2s.ComparisonKind]
      }
      case 1 => this.fingerprint = {
        value.asInstanceOf[avro2s.wire.benchmarks.comparison.avro2s.ComparisonFingerprint]
      }
      case 2 => this.history = {
        val array = value.asInstanceOf[java.util.List[?]]
        scala.jdk.CollectionConverters.IteratorHasAsScala(array.iterator).asScala.map({ value =>
          value.asInstanceOf[avro2s.wire.benchmarks.comparison.avro2s.ComparisonKind]
        }).toList
      }
      case 3 => this.blocks = {
        val array = value.asInstanceOf[java.util.List[?]]
        scala.jdk.CollectionConverters.IteratorHasAsScala(array.iterator).asScala.map({ value =>
          value.asInstanceOf[avro2s.wire.benchmarks.comparison.avro2s.ComparisonFingerprint]
        }).toList
      }
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }
}

object ComparisonEnumFixed {
  val SCHEMA$: org.apache.avro.Schema = new _root_.org.apache.avro.Schema.Parser().parse("""{"type":"record","name":"ComparisonEnumFixed","namespace":"avro2s.wire.benchmarks.comparison.avro2s","fields":[{"name":"kind","type":{"type":"enum","name":"ComparisonKind","symbols":["CREATED","UPDATED","DELETED"]}},{"name":"fingerprint","type":{"type":"fixed","name":"ComparisonFingerprint","size":16}},{"name":"history","type":{"type":"array","items":"ComparisonKind"}},{"name":"blocks","type":{"type":"array","items":"ComparisonFingerprint"}}]}""")
}