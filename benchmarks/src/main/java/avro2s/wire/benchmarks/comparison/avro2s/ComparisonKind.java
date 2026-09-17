/** GENERATED CODE */

package avro2s.wire.benchmarks.comparison.avro2s;

public enum ComparisonKind implements org.apache.avro.generic.GenericEnumSymbol<ComparisonKind> {
  CREATED, UPDATED, DELETED;

  public static final org.apache.avro.Schema SCHEMA$ = new org.apache.avro.Schema.Parser().parse("{\"type\":\"enum\",\"name\":\"ComparisonKind\",\"namespace\":\"avro2s.wire.benchmarks.comparison.avro2s\",\"symbols\":[\"CREATED\",\"UPDATED\",\"DELETED\"]}");
  public static org.apache.avro.Schema getClassSchema() { return SCHEMA$; }

  @Override
  public org.apache.avro.Schema getSchema() { return SCHEMA$; }
}