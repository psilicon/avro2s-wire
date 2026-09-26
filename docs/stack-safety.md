# Direct and stack-safe codecs

Every generated record, enum and fixed companion offers two codecs:

| API | Execution | Selection |
| --- | --- | --- |
| `Node.codec` | Direct calls between generated codecs | Existing default `given AvroCodec[Node]` |
| `Node.stackSafeCodec` | Suspended operations executed by one iterative runtime | Explicit, non-given value |

Both expose the same `AvroCodec[A]` read/write and encode/decode methods. They use
the same models, schema JSON, logical mappings and wire bytes. The default remains
direct; application code can opt into stack safety where it needs it. No generator
flag, mutable configuration or registry setting is required. Regenerate models
with the updated compiler and use the corresponding runtime to obtain the new
companion member.

## Complete runnable example

[Usage.scala](examples/stack-safety/Usage.scala) imports and constructs everything
it uses. Its [Node schema](examples/stack-safety/schemas/Node.avsc) defines an
immutable recursive record with `next: Option[Node]` and `value: Int`.
[NodeV2](examples/stack-safety/schemas/NodeV2.avsc) demonstrates schema evolution:
a record alias, reordered fields, promotion to `Long` and an added default.

Run it from the repository root:

```sh
sbt \
  'compiler/run docs/examples/stack-safety/schemas target/stack-safety-example-generated' \
  'set resolution / Compile / unmanagedSourceDirectories ++= Seq(file("docs/examples/stack-safety"), file("target/stack-safety-example-generated"))' \
  'resolution/runMain example.stack.Usage'
```

These source-directory settings affect that sbt session only. In a consuming
application, compile the generated sources and example with the runtime and
resolution dependencies. The program checks byte equivalence on a small value,
then writes, reads and resolves a chain of 100,000 records without relying on
recursive case-class equality to check the result.

The essential selection in that program is:

```scala
import avro2s.wire.resolution.ResolvingReader
import avro2s.wire.runtime.AvroCodec
import example.stack.{Node, NodeV2}

val codec: AvroCodec[Node] = Node.stackSafeCodec
val value = Node(Some(Node(None, 1)), 2)
val bytes = codec.encode(value)
val restored: Node = codec.decode(bytes)

val reader = ResolvingReader(Node.schemaJson, NodeV2.stackSafeCodec)
val evolved: NodeV2 = reader.decode(bytes)
```

`ResolvingReader` selects execution from the reader codec once at construction.
Identical schemas use that codec directly. Evolved schemas use the corresponding
resolution interpreter, including skipped writer fields and reader defaults.
The schema-registry factories already accept a codec, so their signatures and
settings do not change: pass `Trade.stackSafeCodec` wherever the
[complete registry program](examples/schema-registry/FullConfiguration.scala)
currently passes `Trade.codec`. Key and value codecs can be selected independently.

## What is stack safe

The guarantee concerns traversal of **values**. A child record can contain another
record directly, through `Option` or a general union, or inside an array or map.
Mutually recursive record types are covered too. Record and nested-collection
boundaries return control to one runtime loop, rather than calling a new loop
for each child. Primitive operations and flat scalar collections run in batches. This
applies to reading and writing, plus resolution's traversal of discarded fields
and construction of defaults. Java primitive input/output adapters can also run
the generated stack-safe codec; the behavior of an arbitrary caller-supplied
`AvroInput`/`AvroOutput` remains the caller's responsibility.

A million elements in one flat array do not create a million nested calls in
either codec. String length and byte length are also independent of call-stack
depth. Stack-safe collection loops schedule one element at a time; pending work
grows with nesting depth rather than the number of sibling elements. The result
still needs memory for all its elements.

The models remain immutable. For records that need to suspend, the generated
codec creates a private execution frame with typed partial-field slots and a
position to resume after a child. The runtime loop keeps these frames in a
per-operation array. Collections that need to suspend reuse one frame across
their elements, with a builder for reads or an iterator for writes. Leaf-only
records and flat scalar collections use the batched direct bodies instead. This
execution state is mutable and confined to one call; it is never stored in the
model or shared codec. Codecs and compiled resolution plans remain shareable.
Inputs and outputs must belong to one operation at a time. Stack-safe execution
uses heap memory for pending operations and does not promise unbounded data can
fit in memory.

These boundaries remain:

- Schema parsing, code generation and resolution-plan/default compilation have
  not been made stack safe. The resolver retains its existing 256-level schema
  parsing and default-compilation safeguards. A shallow recursive schema can
  nevertheless describe a value with 100,000 record levels.
- Generated case-class `equals`, `hashCode` and `toString` can recurse through
  models. Use iterative inspection for very deep values, as the example does.
- Application-provided codec/construction hooks must uphold their own promises.
  Overriding an arbitrary codec's `execution` accessor does not rewrite its
  implementation.

`DecodeLimits` remains independent: all five options default to `None` in both
modes. Applications can still set a nesting or size budget. Mandatory bounds,
UTF-8, union and collection-block validation remains active. The native
`Array[Byte]` input retains its JVM array representation limits.

For the direct implementation, increasing JVM stack size with an option such as
`-Xss4m` may be appropriate for bounded application data. There is no universal
safe depth or fixed bytes-per-record relationship: schema shape, JVM compilation
and the surrounding call stack affect it. A stack-size setting is not a substitute
for testing the expected maximum depth on the deployment JVM.

## API and implementation locations

- [`CodecExecution` and `AvroCodec.execution`](../runtime/src/main/scala/avro2s/wire/runtime/AvroIO.scala)
  describe the chosen execution. Existing handwritten codecs inherit `Direct`.
- [`CodeGenerator.scala`](../compiler/src/main/scala/avro2s/wire/compiler/CodeGenerator.scala)
  emits the additional codec, typed record frames and child operations. Direct generated
  method bodies retain their previous output, checked against existing golden hashes.
- [`runtime.codegen`](../runtime/src/main/scala/avro2s/wire/runtime/codegen/)
  contains `Step`, its iterative driver, `Step.Frame`, `StackSafeCodec`, and collection/record
  helpers. This support protocol is public so generated code in application
  packages can use it; applications normally use `AvroCodec` instead. Frames are
  fresh execution state for one call. A reusable support-level program must
  create them inside `Step.defer` rather than retain a completed frame.
- [`ResolvingReader.scala`](../resolution/src/main/scala/avro2s/wire/resolution/ResolvingReader.scala)
  contains both resolution implementations and selects the one requested by the codec.

Adding a generated companion member extends the compiler's reserved-name set.
An Avro enum symbol named `stackSafeCodec` is renamed in Scala using the existing
collision policy; its Avro symbol and ordinal remain unchanged. Default-package
type names that would shadow the new codec members are rejected like other
ambiguous default-package names; a namespace mapping can move them into a package.

## Verification and performance

The tests cover 100,000 nested records, 40,000 mutually recursive records,
20,000 mixed record/union/array/map levels and 48 alternating array/map layers
without child records on threads requesting a 256 KiB stack.
They cover both successful traversal and cleanup after deep failures, plus
resolved, skipped and registry-framed values. JVMs may adjust the requested
thread stack size. The existing Java interoperability and generated-property
campaigns also exercise both modes, including logical mappings and malformed input.

The dedicated [benchmark profile](../benchmarks/README.md) compares complete
`encode`, `decode` and resolved `decode` operations in both modes for shallow,
collection-heavy and recursive values. Codec selection and resolution compilation
occur outside timing. It records latency and allocation with a fixed JDK, forks,
source fingerprint and raw JMH results.

See [the measured comparison](../benchmarks/stack-safety-optimisation.md) before choosing
an execution mode for performance-sensitive code. The direct implementation remains
the default while these costs are evaluated.
