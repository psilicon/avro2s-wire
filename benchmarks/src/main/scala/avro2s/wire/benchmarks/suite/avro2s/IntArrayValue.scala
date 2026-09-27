/** GENERATED CODE */

package avro2s.wire.benchmarks.suite.avro2s

import scala.annotation.switch

case class IntArrayValue(var value: List[Int]) extends org.apache.avro.specific.SpecificRecordBase {
  def this() = this(List.empty)

  override def getSchema: org.apache.avro.Schema = IntArrayValue.SCHEMA$

  override def get(field$: Int): AnyRef = {
    (field$: @switch) match {
      case 0 => value match {
        case array =>
          {
            def toJavaArray$(input$: List[AnyRef]): java.util.ArrayList[AnyRef] = {
              var remaining$ = input$
              val result$ = if (input$.isEmpty) new java.util.ArrayList[AnyRef]() else new java.util.ArrayList[AnyRef](input$.size)
              while (remaining$.nonEmpty) {
                result$.add(remaining$.head)
                remaining$ = remaining$.tail
              }
              result$
            }
            toJavaArray$(array.asInstanceOf[List[AnyRef]])
          }
        }
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }

  override def put(field$: Int, value: Any): Unit = {
    (field$: @switch) match {
      case 0 => this.value = {
        val array = value.asInstanceOf[java.util.List[?]]
        {
          val builder$ = List.newBuilder[Int]
          val iterator$ = array.iterator
          while (iterator$.hasNext) {
            val value = iterator$.next
            builder$ += {
              value.asInstanceOf[Int]
            }
          }
          builder$.result()
        }
      }
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }
}

object IntArrayValue {
  @scala.annotation.static val SCHEMA$: org.apache.avro.Schema = new _root_.org.apache.avro.Schema.Parser().parse("""{"type":"record","name":"IntArrayValue","namespace":"avro2s.wire.benchmarks.suite.avro2s","fields":[{"name":"value","type":{"type":"array","items":"int"}}]}""")
}