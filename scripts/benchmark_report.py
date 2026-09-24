#!/usr/bin/env python3
"""Render explicitly supplied JMH results as one provenance-aware Markdown report.

Library interface: render(records, metadata=None) -> str. Metadata may be one
mapping or a sequence of mappings when combining distinct measured campaigns.
This module uses only the Python standard library and never discovers result files.
"""

import argparse
from collections import Counter, defaultdict
from collections.abc import Mapping
import json
import math
from pathlib import Path
import sys


ENGINES = {
    "native": "Wire native",
    "javaPrimitives": "Wire Java primitive backend",
    "avro2s": "avro2s generated Scala",
    "javaSpecific": "Official Java specific (default model)",
    "javaCustom": "Official Java custom coders (default model)",
    "javaGeneric": "Official Java generic (default model)",
    "javaSpecificString": "Official Java specific (String results)",
    "javaGenericString": "Official Java generic (String results)",
    "nativeScalaMapping": "Wire native (scala.math.BigDecimal mapping)",
    "nativeJavaMapping": "Wire native (java.math.BigDecimal mapping)",
    "avroJavaConversion": "Official Java BigDecimalConversion",
    "javaResolved": "Official Java generic resolving reader (GenericRecord/Utf8)",
}
COMMON_ENGINES = (
    "native", "javaPrimitives", "avro2s", "javaSpecific", "javaCustom", "javaGeneric",
)
API_CLASSES = {
    "IntegerBenchmark", "StringBenchmark", "BytesBenchmark",
    "CollectionsBenchmark", "NestedUnionBenchmark",
}
COMPARISON_CLASSES = {
    "ComparisonBenchmark", "NestedComparisonBenchmark", "LogicalComparisonBenchmark",
    "DecimalComparisonBenchmark", "TradeBenchmark", "DecodedStringBenchmark",
}
OPERATIONS = ("Read", "Write", "Decode", "Encode", "Resolution", "Same schema", "Plan construction")
OPERATION_LABELS = {
    "Read": "Read",
    "Write": "Write",
    "Decode": "API Decode (includes end-of-input check)",
    "Encode": "API Encode (includes owned byte-array result)",
    "Resolution": "Warm schema resolution",
    "Same schema": "Same-schema read baseline (different work)",
    "Plan construction": "Cold resolution-plan construction (different work)",
}
JVM_KEYS = ("jvm", "jdkVersion", "vmName", "vmVersion", "jvmArgs")
SETTING_KEYS = (
    "jmhVersion", "threads", "forks", "warmupIterations", "warmupTime", "warmupBatchSize",
    "measurementIterations", "measurementTime", "measurementBatchSize",
)


def _cell(value):
    """Escape arbitrary source labels without letting them change table structure."""
    return str(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace(
        "|", "&#124;"
    ).replace("\r", "").replace("\n", "<br>").replace("`", "&#96;")


def _json(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _value(value):
    if isinstance(value, (Mapping, list, tuple)):
        return _cell(_json(value))
    if value is None:
        return "N/A"
    return _cell(value)


def _number(value):
    return "N/A" if value is None else f"{value:,.2f}"


def _finite_score(value, context):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
        raise ValueError(f"{context}: expected a finite, non-negative numeric score, got {value!r}")
    return value


def _confidence_error(value, context):
    # JMH uses NaN when a smoke run has too few samples for a confidence interval.
    if value is None or value == "NaN" or (isinstance(value, float) and math.isnan(value)):
        return None
    return _finite_score(value, context)


def _params(row, context):
    params = row.get("params", {})
    if not isinstance(params, Mapping):
        raise ValueError(f"{context}: params must be an object")
    for key, value in params.items():
        if not isinstance(key, str) or not isinstance(value, (str, int, float, bool)):
            raise ValueError(f"{context}: params must contain string keys and scalar values")
        if isinstance(value, float) and not math.isfinite(value):
            raise ValueError(f"{context}: non-finite parameter {key}")
    return tuple((key, _json(value)) for key, value in sorted(params.items()))


def _operation(benchmark_class, method):
    if benchmark_class.rsplit(".", 1)[-1] == "EvolutionBenchmark":
        special = {
            "nativeResolved": ("Resolution", "native"),
            "javaResolved": ("Resolution", "javaResolved"),
            "nativeSameSchema": ("Same schema", "native"),
            "compileResolution": ("Plan construction", "native"),
        }
        if method in special:
            return special[method]
    for suffix in ("Read", "Write", "Decode", "Encode"):
        if method.endswith(suffix) and len(method) > len(suffix):
            return suffix, method[:-len(suffix)]
    # Preserve future/unknown methods without assigning an inferred competitor.
    return f"Other method: {method}", method


def _validate(records):
    if isinstance(records, (Mapping, str, bytes)):
        raise ValueError("Expected a JMH result array; environment/metadata JSON is not a result file")
    try:
        records = list(records)
    except TypeError as exc:
        raise ValueError("Expected an iterable of JMH result objects") from exc
    if not records:
        raise ValueError("No JMH measurements supplied")
    seen = set()
    validated = []
    for index, row in enumerate(records):
        if not isinstance(row, Mapping):
            raise ValueError(f"Result {index + 1}: expected a JMH result object")
        name = row.get("benchmark")
        if not isinstance(name, str) or "." not in name or not all(name.rsplit(".", 1)):
            raise ValueError(f"Result {index + 1}: missing fully qualified benchmark method")
        benchmark_class, method = name.rsplit(".", 1)
        params = _params(row, name)
        identity = (benchmark_class, method, params)
        if identity in seen:
            raise ValueError(f"Duplicate measurement: {name}, {dict(params)}; report repeated experiments separately")
        seen.add(identity)
        metric = row.get("primaryMetric")
        if row.get("mode") != "avgt" or not isinstance(metric, Mapping) or metric.get("scoreUnit") != "ns/op":
            raise ValueError(f"{name}: expected JMH average time (avgt) in ns/op")
        score = _finite_score(metric.get("score"), f"{name} primaryMetric")
        error = _confidence_error(metric.get("scoreError"), f"{name} scoreError")
        secondary = row.get("secondaryMetrics", {})
        if not isinstance(secondary, Mapping):
            raise ValueError(f"{name}: secondaryMetrics must be an object")
        allocation = secondary.get("gc.alloc.rate.norm")
        if allocation is not None:
            if not isinstance(allocation, Mapping) or allocation.get("scoreUnit") != "B/op":
                raise ValueError(f"{name}: gc.alloc.rate.norm must use B/op")
            allocation = _finite_score(allocation.get("score"), f"{name} gc.alloc.rate.norm")
        source = row.get("_sourceFile")
        if source is not None and (not isinstance(source, str) or not source):
            raise ValueError(f"{name}: _sourceFile must be a non-empty path string")
        operation, engine = _operation(benchmark_class, method)
        setup = _json({key: row.get(key) for key in JVM_KEYS + SETTING_KEYS})
        validated.append({
            "class": benchmark_class, "method": method, "params": params,
            "operation": operation, "engine": engine, "score": score,
            "error": error, "allocation": allocation, "setup": setup, "source": source,
        })
    return validated


def _metadata_list(metadata):
    if metadata is None:
        return []
    if isinstance(metadata, Mapping):
        return [metadata]
    if isinstance(metadata, (list, tuple)) and all(isinstance(item, Mapping) for item in metadata):
        return list(metadata)
    raise ValueError("metadata must be an object or a list of objects")


def _metadata_section(metadata):
    lines = ["## Source metadata", ""]
    if not metadata:
        return lines + [
            "No source metadata supplied. Commit, worktree state, correctness checks and campaign coverage are unknown.", "",
        ]
    for index, source in enumerate(metadata, 1):
        lines.extend([f"### Metadata {index}", "", "| Field | Recorded value |", "| --- | --- |"])
        for key, value in source.items():
            if key in ("sourceSha256", "sourceSha256After") and isinstance(value, Mapping):
                value = f"{len(value)} source/build hashes recorded in the metadata file"
            elif key == "runs" and isinstance(value, list):
                continue
            elif key == "gitStatus" and value == "":
                value = "Clean worktree (recorded by runner)"
            lines.append(f"| {_cell(key)} | {_value(value)} |")
        if not any(key in source for key in ("gitRevision", "commit")):
            lines.append("| Source commit | N/A (not recorded) |")
        lines.append("")
        runs = source.get("runs")
        if isinstance(runs, list) and runs:
            lines.extend(["Recorded run coverage:", "", "| Run | Result | Expected cases | Status |", "| --- | --- | ---: | --- |"])
            for run in runs:
                if isinstance(run, Mapping):
                    lines.append("| " + " | ".join(_value(run.get(key)) for key in (
                        "name", "result", "expectedCases", "status",
                    )) + " |")
            lines.append("")
    lines.extend([
        "Metadata is reproduced as provenance, not reverified against the current checkout. "
        "Separate manifests may describe different commits or campaigns; their presence does not establish identical environments.", "",
    ])
    return lines


def _engine_order(benchmark_class, operation, records):
    short = benchmark_class.rsplit(".", 1)[-1]
    if short == "BigDecimalBenchmark":
        expected = ["nativeScalaMapping", "nativeJavaMapping", "avroJavaConversion"]
    elif short == "EvolutionBenchmark" and operation == "Resolution":
        expected = ["native", "javaResolved"]
    elif short == "EvolutionBenchmark" and operation in ("Same schema", "Plan construction"):
        expected = ["native"]
    elif short in API_CLASSES or short in COMPARISON_CLASSES:
        expected = list(COMMON_ENGINES)
        if short == "DecodedStringBenchmark":
            expected.extend(["javaSpecificString", "javaGenericString"])
    else:
        expected = []
    return expected + sorted(set(records).difference(expected))


def _class_notes(short):
    if short == "BigDecimalBenchmark":
        return (
            "Both native mappings use the Wire native runtime. `nativeJavaMapping` changes the result model to "
            "`java.math.BigDecimal`; it is not `javaPrimitives`, the Wire Java primitive backend. "
            "The official Java baseline includes BigDecimalConversion and the enclosing Avro bytes field."
        )
    if short == "EvolutionBenchmark":
        return (
            "Only the two warm resolution methods perform writer-to-reader schema resolution. "
            "Same-schema decoding returns the old model and does different work; cold plan construction does not read a datum. "
            "The native resolving reader returns a generated immutable model; Java returns GenericRecord/Utf8."
        )
    if short == "DecodedStringBenchmark":
        return (
            "The measured read variants materialize java.lang.String for text fields and map keys. "
            "Java String-result variants are explicit rows, separate from default-model readers. "
            "Record and collection representations still differ."
        )
    if short in API_CLASSES:
        return (
            "Read/Write use the lower-level codec paths; Write reuses output buffers. "
            "API Encode creates buffers and returns an owned byte array; API Decode includes an end-of-input check. "
            "Native and Java primitive paths retain their own validation policies."
        )
    if short in COMPARISON_CLASSES:
        return (
            "Wire native and its Java primitive backend use the same generated Wire model. "
            "avro2s and official Java readers use their own generated or generic models; string and collection "
            "representations can differ. Java default-model readers may return Utf8. Custom coders are a separate baseline."
        )
    return "Unknown benchmark methods are retained by name; equivalent work is not inferred."


def render(records, metadata=None):
    """Return Markdown; reject duplicate identities, invalid scores and incompatible units.

    A result identity is the full class + method + exact parameters. No repeated
    runs are averaged. Missing allocation or unavailable JMH confidence errors
    are N/A. Callers may add _sourceFile to copies of JMH rows for per-row file
    attribution. The caller is responsible for campaign correctness/coverage checks.
    """
    validated = _validate(records)
    metadata = _metadata_list(metadata)
    groups = defaultdict(lambda: defaultdict(dict))
    for record in validated:
        group = groups[(record["class"], record["params"])][record["operation"]]
        group[record["engine"]] = record
    setups = sorted({row["setup"] for row in validated})
    setup_ids = {setup: str(index) for index, setup in enumerate(setups, 1)}
    setup_counts = Counter(row["setup"] for row in validated)
    sources = list(dict.fromkeys(row["source"] for row in validated if row["source"] is not None))
    source_ids = {source: str(index) for index, source in enumerate(sources, 1)}
    source_header = " | Source" if sources else ""
    source_alignment = " | ---:" if sources else ""
    classes = len({row["class"] for row in validated})
    lines = [
        "# Benchmark report", "",
        f"Measured coverage: **{len(validated)} result rows**, **{classes} benchmark classes**, "
        f"**{len(groups)} workload/parameter groups**. Only explicitly supplied measurements are included.", "",
        "Timing is JMH average time in **ns/op ± JMH confidence error** (`scoreError`, normally a 99.9% confidence interval half-width). "
        "Allocation is `gc.alloc.rate.norm` in **B/op**. Lower means less time or allocation for the measured operation.", "",
        "**N/A** means an engine or metric was unsupported or unmeasured in the supplied results, or JMH could not estimate a "
        "confidence error. It does not mean zero. No missing results, confidence intervals or speedup claims are inferred.", "",
    ]
    if sources:
        lines.extend(["## Result sources", "", "| Source | JMH result file |", "| --- | --- |"] )
        lines.extend(f"| {source_ids[source]} | {_cell(source)} |" for source in sources)
        lines.extend(["", "Source identifies the input file for each measured row. "
                      "Shared JMH settings do not imply that different source files belong to the same campaign or commit.", ""])
    lines.extend(_metadata_section(metadata))
    lines.extend(["## Measured JMH setups", "", "| Setup | Rows | JVM | JMH settings |", "| --- | ---: | --- | --- |"])
    for setup in setups:
        values = json.loads(setup)
        jvm = "; ".join(f"{key}={_value(values[key])}" for key in JVM_KEYS)
        settings = "; ".join(f"{key}={_value(values[key])}" for key in SETTING_KEYS)
        lines.append(f"| {setup_ids[setup]} | {setup_counts[setup]} | {jvm} | {settings} |")
    lines.extend([
        "", "Setup numbers identify settings recorded in JMH JSON, not machine identity. Missing settings are N/A. "
        "Different setups or campaigns should not be treated as controlled comparisons. "
        "Results describe their recorded sources and environment; do not assume they apply to another checkout or machine.", "",
        "## Measurements", "",
        "Each table keeps one full benchmark class, exact parameter set and operation. "
        "Wire Java primitive backend means Wire codecs over official Java Avro primitive I/O; "
        "official Java specific, custom and generic datum implementations remain distinct.", "",
    ])
    for (benchmark_class, params), operations in sorted(groups.items()):
        short = benchmark_class.rsplit(".", 1)[-1]
        label = ", ".join(f"{key}={json.loads(value)}" for key, value in params) or "no parameters"
        lines.extend([
            f"### {_cell(short)} — {_cell(label)}", "",
            f"Full benchmark class: `{_cell(benchmark_class)}`.", "",
            _class_notes(short), "",
        ])
        for operation in sorted(operations, key=lambda name: (OPERATIONS.index(name) if name in OPERATIONS else len(OPERATIONS), name)):
            methods = operations[operation]
            lines.extend([
                f"#### {_cell(OPERATION_LABELS.get(operation, operation))}", "",
                f"| Engine / result model | Method | ns/op ± JMH error | B/op | Setup{source_header} |",
                f"| --- | --- | ---: | ---: | ---:{source_alignment} |",
            ])
            for engine in _engine_order(benchmark_class, operation, methods):
                label = _cell(ENGINES.get(engine, engine))
                row = methods.get(engine)
                if row is None:
                    source_cell = " | N/A" if sources else ""
                    lines.append(f"| {label} | N/A | N/A | N/A | N/A{source_cell} |")
                else:
                    source_cell = f" | {source_ids.get(row['source'], 'N/A')}" if sources else ""
                    lines.append(
                        f"| {label} | `{_cell(row['method'])}` | {_number(row['score'])} ± {_number(row['error'])} | "
                        f"{_number(row['allocation'])} | {setup_ids[row['setup']]}{source_cell} |"
                    )
            lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", nargs="+", type=Path, help="Explicit JMH JSON result files (arrays); no directory discovery")
    parser.add_argument("--metadata", action="append", default=[], type=Path, help="Environment/provenance JSON object; repeat for separate campaigns")
    parser.add_argument("-o", "--output", type=Path, help="Write Markdown to this path instead of stdout")
    args = parser.parse_args(argv)
    try:
        records = []
        for path in args.results:
            payload = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(payload, list):
                raise ValueError(f"{path}: expected a JMH result array; pass environment JSON with --metadata")
            if not payload:
                raise ValueError(f"{path}: empty JMH result array")
            records.extend({**row, "_sourceFile": str(path)} if isinstance(row, dict) else row for row in payload)
        metadata = []
        for path in args.metadata:
            payload = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(payload, dict):
                raise ValueError(f"{path}: metadata must be a JSON object")
            metadata.append({"metadataFile": str(path), **payload})
        result = render(records, metadata)
        if args.output:
            # Avoid silently destroying an input or provenance file.
            if args.output.resolve() in {path.resolve() for path in args.results + args.metadata}:
                raise ValueError("Output path must differ from every input and metadata path")
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(result, encoding="utf-8")
        else:
            sys.stdout.write(result)
    except (OSError, ValueError, TypeError) as exc:
        parser.error(str(exc))


if __name__ == "__main__":
    main()
