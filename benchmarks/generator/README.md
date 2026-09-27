# Reproducing comparison models

The suite uses genuine generated models, checked into source control so normal
builds and benchmarks need no external generator checkout.

- Apache Avro **1.12.1** `SpecificCompiler`, with String fields/map keys, decimal
  logical types enabled, and official duration/big-decimal conversions registered.
- Current avro2s revision **861c816b63643edfaf012d2b84f1bc0ea8b4ce99**, Scala 3
  output, logical types enabled and **ScalaEnum** selected explicitly.
- Wire sources generated during the build from `src/main/resources/suite/schemas`,
  using the default direct codecs. Evolution writer schemas are separate inputs.

From the repository root:

```sh
scripts/regenerate-suite-baselines.sh /path/to/avro2s-checkout
```

The avro2s checkout must match the pinned clean revision. Generation runs using
in-memory sbt settings; it does not edit that checkout's source or build files.
`suite-baselines.json` records versions, generator options, capability reasons and
source hashes. The runner rejects stale or modified baseline provenance.

Custom-coder support is discovered from genuine generated classes and independently
checked with generated counting subclasses during correctness tests. Unsupported
custom coders are unavailable, never relabelled ordinary reader/writer fallback.
Logical fields are checked to have the requested domain types during generation.

The catalogue is [documented case by case](../CASES.md). Historical generators and
models remain [recoverable from Git](../../docs/benchmarks/HISTORY.md).
