// Generated test/setup probes; genuine baseline records remain unmodified.
package avro2s.wire.benchmarks.comparison

import org.apache.avro.io.{Encoder, ResolvingDecoder}
import org.apache.avro.specific.SpecificRecordBase

trait DispatchCounts:
  var encodeCalls = 0
  var decodeCalls = 0

object ComparisonCustomProbes:
  def create(name: String): SpecificRecordBase & DispatchCounts = name match
    case "ComparisonBytes" => new CountingComparisonBytes
    case "ComparisonCollections" => new CountingComparisonCollections
    case "ComparisonEnumFixed" => new CountingComparisonEnumFixed
    case "ComparisonInts" => new CountingComparisonInts
    case "ComparisonLongs" => new CountingComparisonLongs
    case "ComparisonNumerics" => new CountingComparisonNumerics
    case "ComparisonText" => new CountingComparisonText
    case other => throw new IllegalArgumentException(s"No generated custom coder for $other")

private final class CountingComparisonBytes extends javaavro.ComparisonBytes with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

private final class CountingComparisonCollections extends javaavro.ComparisonCollections with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

private final class CountingComparisonEnumFixed extends javaavro.ComparisonEnumFixed with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

private final class CountingComparisonInts extends javaavro.ComparisonInts with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

private final class CountingComparisonLongs extends javaavro.ComparisonLongs with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

private final class CountingComparisonNumerics extends javaavro.ComparisonNumerics with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

private final class CountingComparisonText extends javaavro.ComparisonText with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

