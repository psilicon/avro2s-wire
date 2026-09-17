# Reproducing the report tables

From this package directory, with Python 3 installed, run:

```sh
python3 render-final.py \
  --historical-dir ../comparison-2026-09-17 \
  --results-dir raw \
  --output .
```

This reads only the explicitly named campaign manifests and JMH JSON files. It
does not run sbt/JMH or modify project sources. It requires each campaign to be
recorded complete with unchanged source hashes. Historical inputs are the four
original broad `comparison-*.json` files in the sibling historical directory.

The command regenerates:

- `tables.md`: the fixed original 13 deficits, all 26 native before/after cases,
  all 148 final comparison cases, decoded-String controls, Trade controls, matched
  public-API before/after results, evolution and supplementary-string results,
  distinct confirmation tables, allocation data and provenance.
- `measurements.csv`: full-precision means, confidence bounds, allocations and
  source paths for each measurement, with separate group labels for each kind of
  run.
- `analysis-audit.json`: case counts, input hashes, actual protocols, source
  implementation hashes, missing-case checks and interpretation warnings.
- `render-invocation.json`: the exact expanded analyzer invocation.

The renderer does not modify narrative summary documents. Source paths in the
generated audit reflect the location where it was rerun; benchmark results and
their file digests remain unchanged.

The main native before/after estimates use the fresh `before` and `after` runs.
Historical Java data only establish the original deficit inventory. Current
Java ranking uses the lowest mean among supported generic, specific and generated
custom datum implementations in the final broad run. JavaPrimitives is reported
separately because it uses the same generated Scala model with Java primitive I/O.

The longer small-int confirmation has its own `confirmed-after` CSV group and
report section. It never replaces the broad run, changes its target counts or
mixes samples with it. The earlier emoji confirmation likewise has a separate
`confirmed-before` group alongside the earlier string pilot. The exploratory
supplementary control retains all ten recorded rows in its own pilot group, while
its report table shows only the four newly added workloads; the six repeated
string cases do not override final measurements.

The equivalent decoded-String controls are distinct from default Java Utf8
results. Matching String materialization still leaves immutable Scala versus
mutable Java models/collections and validation differences. Public encode/decode
methods include convenience-API costs and are not compared interchangeably with
direct read/write methods.

Reported confidence-interval overlap is descriptive, not a paired significance
test. Ratios are ratios of means without inferred confidence intervals. Complete
actual protocols are included, and the shorter one-fork before-string pilot is
explicitly distinguished from the final measurements.
