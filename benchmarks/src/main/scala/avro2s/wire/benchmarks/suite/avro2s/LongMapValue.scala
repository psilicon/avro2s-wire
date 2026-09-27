/** GENERATED CODE */

package avro2s.wire.benchmarks.suite.avro2s

import scala.annotation.switch

case class LongMapValue(var value: Map[String, Long]) extends org.apache.avro.specific.SpecificRecordBase {
  def this() = this(Map.empty)

  override def getSchema: org.apache.avro.Schema = LongMapValue.SCHEMA$

  override def get(field$: Int): AnyRef = {
    (field$: @switch) match {
      case 0 => {
        val map: java.util.HashMap[String, Any] = new java.util.HashMap[String, Any]({ val size$ = value.size; if (size$ <= 12) 16 else _root_.scala.math.ceil(size$ / 0.75d).toInt })
        value.foreach { kvp =>
          val key = kvp._1
          val value = {
            kvp._2.asInstanceOf[AnyRef]
          }
          map.put(key, value)
        }
        map
      }.asInstanceOf[AnyRef]
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }

  override def put(field$: Int, value: Any): Unit = {
    (field$: @switch) match {
      case 0 => this.value = {
        val map = value.asInstanceOf[java.util.Map[?,?]]
        if (map.isEmpty) _root_.scala.collection.immutable.Map.empty[String, Long] else {
          val builder$ = Map.newBuilder[String, Long]
          val iterator$ = map.entrySet.iterator
          while (iterator$.hasNext) {
            val entry$ = iterator$.next
            val key = entry$.getKey.toString
            val value = entry$.getValue
            builder$ += ((key, {
              value.asInstanceOf[Long]
            }))
          }
          builder$.result()
        }
      }
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }
}

object LongMapValue {
  @scala.annotation.static val SCHEMA$: org.apache.avro.Schema = new _root_.org.apache.avro.Schema.Parser().parse("""{"type":"record","name":"LongMapValue","namespace":"avro2s.wire.benchmarks.suite.avro2s","fields":[{"name":"value","type":{"type":"map","values":"long"}}]}""")
}