/** GENERATED CODE */

package avro2s.wire.benchmarks.comparison.avro2s

import scala.annotation.switch

case class ComparisonLogical(var day: java.time.LocalDate, var timeMs: java.time.LocalTime, var timeUs: java.time.LocalTime, var timestampMs: java.time.Instant, var timestampUs: java.time.Instant, var localMs: java.time.LocalDateTime, var localUs: java.time.LocalDateTime, var uuid: java.util.UUID) extends org.apache.avro.specific.SpecificRecordBase {
  def this() = this(java.time.LocalDate.ofEpochDay(0), java.time.LocalTime.ofNanoOfDay(0), java.time.LocalTime.ofNanoOfDay(0), java.time.Instant.ofEpochMilli(0), java.time.Instant.ofEpochSecond(0, 0), java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(0), java.time.ZoneId.of("UTC")), java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(0, 0), java.time.ZoneId.of("UTC")), java.util.UUID.fromString("00000000-0000-0000-0000-000000000000"))

  override def getSchema: org.apache.avro.Schema = ComparisonLogical.SCHEMA$

  override def getSpecificData(): org.apache.avro.specific.SpecificData = ComparisonLogical.MODEL$

  override def get(field$: Int): AnyRef = {
    (field$: @switch) match {
      case 0 => day.asInstanceOf[AnyRef]
      case 1 => timeMs.asInstanceOf[AnyRef]
      case 2 => timeUs.asInstanceOf[AnyRef]
      case 3 => timestampMs.asInstanceOf[AnyRef]
      case 4 => timestampUs.asInstanceOf[AnyRef]
      case 5 => localMs.asInstanceOf[AnyRef]
      case 6 => localUs.asInstanceOf[AnyRef]
      case 7 => uuid.asInstanceOf[AnyRef]
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }

  override def put(field$: Int, value: Any): Unit = {
    (field$: @switch) match {
      case 0 => this.day = {
        value.asInstanceOf[java.time.LocalDate]
      }
      case 1 => this.timeMs = {
        value.asInstanceOf[java.time.LocalTime]
      }
      case 2 => this.timeUs = {
        value.asInstanceOf[java.time.LocalTime]
      }
      case 3 => this.timestampMs = {
        value.asInstanceOf[java.time.Instant]
      }
      case 4 => this.timestampUs = {
        value.asInstanceOf[java.time.Instant]
      }
      case 5 => this.localMs = {
        value.asInstanceOf[java.time.LocalDateTime]
      }
      case 6 => this.localUs = {
        value.asInstanceOf[java.time.LocalDateTime]
      }
      case 7 => this.uuid = {
        value.asInstanceOf[java.util.UUID]
      }
      case _ => throw new org.apache.avro.AvroRuntimeException("Bad index")
    }
  }

  override def getConversion(field: Int): org.apache.avro.Conversion[?] = {
    (field: @switch) match {
      case 0 => ComparisonLogical.$DateConversion
      case 1 => ComparisonLogical.$TimeMillisConversion
      case 2 => ComparisonLogical.$TimeMicrosConversion
      case 3 => ComparisonLogical.$TimestampMillisConversion
      case 4 => ComparisonLogical.$TimestampMicrosConversion
      case 5 => ComparisonLogical.$LocalTimestampMillisConversion
      case 6 => ComparisonLogical.$LocalTimestampMicrosConversion
      case 7 => ComparisonLogical.$UUIDConversion
      case _ => null
    }
  }
}

object ComparisonLogical {
  val SCHEMA$: org.apache.avro.Schema = new _root_.org.apache.avro.Schema.Parser().parse("""{"type":"record","name":"ComparisonLogical","namespace":"avro2s.wire.benchmarks.comparison.avro2s","fields":[{"name":"day","type":{"type":"int","logicalType":"date"}},{"name":"timeMs","type":{"type":"int","logicalType":"time-millis"}},{"name":"timeUs","type":{"type":"long","logicalType":"time-micros"}},{"name":"timestampMs","type":{"type":"long","logicalType":"timestamp-millis"}},{"name":"timestampUs","type":{"type":"long","logicalType":"timestamp-micros"}},{"name":"localMs","type":{"type":"long","logicalType":"local-timestamp-millis"}},{"name":"localUs","type":{"type":"long","logicalType":"local-timestamp-micros"}},{"name":"uuid","type":{"type":"string","logicalType":"uuid"}}]}""")
  val $DateConversion: org.apache.avro.Conversion[?] = new org.apache.avro.data.TimeConversions.DateConversion()
  val $TimeMillisConversion: org.apache.avro.Conversion[?] = new org.apache.avro.data.TimeConversions.TimeMillisConversion()
  val $TimeMicrosConversion: org.apache.avro.Conversion[?] = new org.apache.avro.data.TimeConversions.TimeMicrosConversion()
  val $TimestampMillisConversion: org.apache.avro.Conversion[?] = new org.apache.avro.data.TimeConversions.TimestampMillisConversion()
  val $TimestampMicrosConversion: org.apache.avro.Conversion[?] = new org.apache.avro.data.TimeConversions.TimestampMicrosConversion()
  val $LocalTimestampMillisConversion: org.apache.avro.Conversion[?] = new org.apache.avro.data.TimeConversions.LocalTimestampMillisConversion()
  val $LocalTimestampMicrosConversion: org.apache.avro.Conversion[?] = new org.apache.avro.data.TimeConversions.LocalTimestampMicrosConversion()
  val $UUIDConversion: org.apache.avro.Conversion[?] = new org.apache.avro.Conversions.UUIDConversion()
  val MODEL$: org.apache.avro.specific.SpecificData = {
    val model = new org.apache.avro.specific.SpecificData()
    model.addLogicalTypeConversion($DateConversion)
    model.addLogicalTypeConversion($TimeMillisConversion)
    model.addLogicalTypeConversion($TimeMicrosConversion)
    model.addLogicalTypeConversion($TimestampMillisConversion)
    model.addLogicalTypeConversion($TimestampMicrosConversion)
    model.addLogicalTypeConversion($LocalTimestampMillisConversion)
    model.addLogicalTypeConversion($LocalTimestampMicrosConversion)
    model.addLogicalTypeConversion($UUIDConversion)
    model
  }
}