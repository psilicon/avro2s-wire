/** GENERATED CODE */

package avro2s.wire.benchmarks.comparison.avro2s

case class ComparisonFingerprint() extends org.apache.avro.specific.SpecificFixed {
  override def getSchema: org.apache.avro.Schema = ComparisonFingerprint.SCHEMA$
  override def readExternal(in: java.io.ObjectInput): Unit = {
    avro2s.wire.benchmarks.comparison.avro2s.ComparisonFingerprint.READER$.read(this, org.apache.avro.specific.SpecificData.getDecoder(in))
    ()
  }
  override def writeExternal(out: java.io.ObjectOutput): Unit = {
    avro2s.wire.benchmarks.comparison.avro2s.ComparisonFingerprint.WRITER$.write(this, org.apache.avro.specific.SpecificData.getEncoder(out))
  }
}

object ComparisonFingerprint {
  val SCHEMA$ = new _root_.org.apache.avro.Schema.Parser().parse("""{"type":"fixed","name":"ComparisonFingerprint","namespace":"avro2s.wire.benchmarks.comparison.avro2s","size":16}""")
  val READER$ = new org.apache.avro.specific.SpecificDatumReader[ComparisonFingerprint](ComparisonFingerprint.SCHEMA$, ComparisonFingerprint.SCHEMA$, new org.apache.avro.specific.SpecificData())
  val WRITER$ = new org.apache.avro.specific.SpecificDatumWriter[ComparisonFingerprint](ComparisonFingerprint.SCHEMA$, new org.apache.avro.specific.SpecificData())
  def apply(data: Array[Byte]): ComparisonFingerprint = {
    val fixed = new avro2s.wire.benchmarks.comparison.avro2s.ComparisonFingerprint()
    fixed.bytes(data)
    fixed
  }
}