/** GENERATED CODE */

package avro2s.wire.benchmarks.suite.avro2s.internal {
  enum EightSymbols(private val symbol$: _root_.java.lang.String) {
    case S0 extends _root_.avro2s.wire.benchmarks.suite.avro2s.internal.EightSymbols("S0")
    case S1 extends _root_.avro2s.wire.benchmarks.suite.avro2s.internal.EightSymbols("S1")
    case S2 extends _root_.avro2s.wire.benchmarks.suite.avro2s.internal.EightSymbols("S2")
    case S3 extends _root_.avro2s.wire.benchmarks.suite.avro2s.internal.EightSymbols("S3")
    case S4 extends _root_.avro2s.wire.benchmarks.suite.avro2s.internal.EightSymbols("S4")
    case S5 extends _root_.avro2s.wire.benchmarks.suite.avro2s.internal.EightSymbols("S5")
    case S6 extends _root_.avro2s.wire.benchmarks.suite.avro2s.internal.EightSymbols("S6")
    case S7 extends _root_.avro2s.wire.benchmarks.suite.avro2s.internal.EightSymbols("S7")

    override def toString: _root_.java.lang.String = symbol$
  }

  object EightSymbols {
    val SCHEMA$: _root_.org.apache.avro.Schema = new _root_.org.apache.avro.Schema.Parser().parse("""{"type":"enum","name":"EightSymbols","namespace":"avro2s.wire.benchmarks.suite.avro2s","symbols":["S0","S1","S2","S3","S4","S5","S6","S7"]}""")

    private val avroSymbol$0: _root_.org.apache.avro.generic.GenericData.EnumSymbol = new _root_.org.apache.avro.generic.GenericData.EnumSymbol(SCHEMA$, "S0")
    private val avroSymbol$1: _root_.org.apache.avro.generic.GenericData.EnumSymbol = new _root_.org.apache.avro.generic.GenericData.EnumSymbol(SCHEMA$, "S1")
    private val avroSymbol$2: _root_.org.apache.avro.generic.GenericData.EnumSymbol = new _root_.org.apache.avro.generic.GenericData.EnumSymbol(SCHEMA$, "S2")
    private val avroSymbol$3: _root_.org.apache.avro.generic.GenericData.EnumSymbol = new _root_.org.apache.avro.generic.GenericData.EnumSymbol(SCHEMA$, "S3")
    private val avroSymbol$4: _root_.org.apache.avro.generic.GenericData.EnumSymbol = new _root_.org.apache.avro.generic.GenericData.EnumSymbol(SCHEMA$, "S4")
    private val avroSymbol$5: _root_.org.apache.avro.generic.GenericData.EnumSymbol = new _root_.org.apache.avro.generic.GenericData.EnumSymbol(SCHEMA$, "S5")
    private val avroSymbol$6: _root_.org.apache.avro.generic.GenericData.EnumSymbol = new _root_.org.apache.avro.generic.GenericData.EnumSymbol(SCHEMA$, "S6")
    private val avroSymbol$7: _root_.org.apache.avro.generic.GenericData.EnumSymbol = new _root_.org.apache.avro.generic.GenericData.EnumSymbol(SCHEMA$, "S7")

    def toAvroSymbol$(value: _root_.avro2s.wire.benchmarks.suite.avro2s.internal.EightSymbols): _root_.org.apache.avro.generic.GenericData.EnumSymbol = {
      if (value == null) null
      else value.toString match {
        case "S0" => avroSymbol$0
        case "S1" => avroSymbol$1
        case "S2" => avroSymbol$2
        case "S3" => avroSymbol$3
        case "S4" => avroSymbol$4
        case "S5" => avroSymbol$5
        case "S6" => avroSymbol$6
        case "S7" => avroSymbol$7
        case other => throw new _root_.org.apache.avro.AvroRuntimeException("No enum symbol " + other + " in avro2s.wire.benchmarks.suite.avro2s.EightSymbols")
      }
    }

    def fromAvroSymbol(value: _root_.java.lang.String): _root_.avro2s.wire.benchmarks.suite.avro2s.internal.EightSymbols = value match {
      case "S0" => _root_.avro2s.wire.benchmarks.suite.avro2s.internal.EightSymbols.S0
      case "S1" => _root_.avro2s.wire.benchmarks.suite.avro2s.internal.EightSymbols.S1
      case "S2" => _root_.avro2s.wire.benchmarks.suite.avro2s.internal.EightSymbols.S2
      case "S3" => _root_.avro2s.wire.benchmarks.suite.avro2s.internal.EightSymbols.S3
      case "S4" => _root_.avro2s.wire.benchmarks.suite.avro2s.internal.EightSymbols.S4
      case "S5" => _root_.avro2s.wire.benchmarks.suite.avro2s.internal.EightSymbols.S5
      case "S6" => _root_.avro2s.wire.benchmarks.suite.avro2s.internal.EightSymbols.S6
      case "S7" => _root_.avro2s.wire.benchmarks.suite.avro2s.internal.EightSymbols.S7
      case other => throw new _root_.org.apache.avro.AvroRuntimeException("No enum symbol " + other + " in avro2s.wire.benchmarks.suite.avro2s.EightSymbols")
    }
  }
}

package avro2s.wire.benchmarks.suite.avro2s {
  type EightSymbols = _root_.avro2s.wire.benchmarks.suite.avro2s.internal.EightSymbols
  val EightSymbols: _root_.avro2s.wire.benchmarks.suite.avro2s.internal.EightSymbols.type = _root_.avro2s.wire.benchmarks.suite.avro2s.internal.EightSymbols
}