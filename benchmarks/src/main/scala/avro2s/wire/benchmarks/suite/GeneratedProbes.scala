// Generated setup/test probes; genuine baseline records remain unmodified.
package avro2s.wire.benchmarks.suite

import org.apache.avro.io.{Encoder, ResolvingDecoder}
import org.apache.avro.specific.SpecificRecordBase

trait DispatchCounts:
  var encodeCalls = 0
  var decodeCalls = 0

object GeneratedProbes:
  def create(model: String): SpecificRecordBase & DispatchCounts = model match
    case "BooleanValue" => new CountingBooleanValue
    case "BytesValue" => new CountingBytesValue
    case "Chain16" => new CountingChain16
    case "Chain4" => new CountingChain4
    case "DefaultValue" => new CountingDefaultValue
    case "DoubleValue" => new CountingDoubleValue
    case "EnumValue" => new CountingEnumValue
    case "FixedValue16" => new CountingFixedValue16
    case "Flat16" => new CountingFlat16
    case "Flat4" => new CountingFlat4
    case "FloatValue" => new CountingFloatValue
    case "IntArrayValue" => new CountingIntArrayValue
    case "IntValue" => new CountingIntValue
    case "LongMapValue" => new CountingLongMapValue
    case "LongValue" => new CountingLongValue
    case "OptionalIntValue" => new CountingOptionalIntValue
    case "ProjectionValue" => new CountingProjectionValue
    case "PromotionValue" => new CountingPromotionValue
    case "TextValue" => new CountingTextValue
    case other => throw new IllegalArgumentException(s"No generated custom coder for $other")

private final class CountingBooleanValue extends javaavro.BooleanValue with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

private final class CountingBytesValue extends javaavro.BytesValue with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

private final class CountingChain16 extends javaavro.Chain16 with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

private final class CountingChain4 extends javaavro.Chain4 with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

private final class CountingDefaultValue extends javaavro.DefaultValue with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

private final class CountingDoubleValue extends javaavro.DoubleValue with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

private final class CountingEnumValue extends javaavro.EnumValue with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

private final class CountingFixedValue16 extends javaavro.FixedValue16 with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

private final class CountingFlat16 extends javaavro.Flat16 with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

private final class CountingFlat4 extends javaavro.Flat4 with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

private final class CountingFloatValue extends javaavro.FloatValue with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

private final class CountingIntArrayValue extends javaavro.IntArrayValue with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

private final class CountingIntValue extends javaavro.IntValue with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

private final class CountingLongMapValue extends javaavro.LongMapValue with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

private final class CountingLongValue extends javaavro.LongValue with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

private final class CountingOptionalIntValue extends javaavro.OptionalIntValue with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

private final class CountingProjectionValue extends javaavro.ProjectionValue with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

private final class CountingPromotionValue extends javaavro.PromotionValue with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

private final class CountingTextValue extends javaavro.TextValue with DispatchCounts:
  override def customEncode(out: Encoder): Unit =
    encodeCalls += 1
    super.customEncode(out)
  override def customDecode(in: ResolvingDecoder): Unit =
    decodeCalls += 1
    super.customDecode(in)

