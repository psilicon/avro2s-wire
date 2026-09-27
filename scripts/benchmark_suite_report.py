#!/usr/bin/env python3
"""Validate and report the consolidated suite, keeping independent JVM rounds."""
import argparse
from collections import defaultdict
import json
import math
from pathlib import Path
import statistics

BENCHMARK = "avro2s.wire.benchmarks.suite.SuiteBenchmark."
DEFAULT_ENGINES = ("wire", "java-specific", "java-generic", "java-custom", "avro2s")
WIRE_VARIANTS = ("wire-stack-safe", "wire-java", "wire-java-stack-safe")
ENGINES = DEFAULT_ENGINES + WIRE_VARIANTS
# Two-sided 95% Student t quantiles; our fixed publication protocol has five
# rounds (df=4). No iteration is treated as an independent JVM replicate.
T95 = {1: 12.7062047364, 2: 4.3026527297, 3: 3.1824463053,
       4: 2.7764451052, 5: 2.5705818356, 6: 2.4469118511,
       7: 2.3646242516, 8: 2.3060041352, 9: 2.2621571629}


def cell_key(record):
    return record["params"]["caseId"], record["benchmark"].removeprefix(BENCHMARK), record.get("_variant", record["params"]["engine"])


def finite_number(value, label, positive=False):
    if (isinstance(value, bool) or not isinstance(value, (int, float))
            or not math.isfinite(value) or (value <= 0 if positive else value < 0)):
        raise ValueError(f"Invalid {label}: expected finite {'positive' if positive else 'nonnegative'} number")
    return float(value)


def validate_fork(records, expected, timing, java=None, log_text="", usage=None, wire_string_policy=None):
    """Each launcher selects exactly one case/operation/engine and one JVM fork."""
    try:
        if not isinstance(records, list) or len(records) != 1:
            raise ValueError("Missing, duplicate or unexpected JMH results")
        record = records[0]
        if cell_key(record) != tuple(expected) or record["benchmark"] != BENCHMARK + expected[1]:
            raise ValueError("Unexpected JMH result membership")
        # Archived campaigns predate the lifecycle parameter. Only metadata
        # without an explicit usage permits that old two-parameter shape.
        expected_params = {"caseId", "engine"} if usage is None else {"caseId", "engine", "usage"}
        if wire_string_policy is not None:
            if usage is None or wire_string_policy not in ("reject", "replace"):
                raise ValueError("Invalid frozen Wire string policy")
            expected_params.add("wireStringPolicy")
        if set(record["params"]) != expected_params:
            raise ValueError("Unexpected JMH parameters")
        if usage is not None and (usage not in ("fresh", "reuse") or record["params"]["usage"] != usage):
            raise ValueError("Unexpected API usage mode")
        if wire_string_policy is not None and record["params"]["wireStringPolicy"] != wire_string_policy:
            raise ValueError("Unexpected Wire string policy")
        if "<failure>" in log_text or record["mode"] != "avgt" or record["forks"] != 1 or record["threads"] != 1:
            raise ValueError("JMH failure or unexpected mode/forks/threads")
        for field in ("warmupIterations", "measurementIterations"):
            if record[field] != timing[field]:
                raise ValueError(f"Unexpected {field}")
        for field in ("warmupTime", "measurementTime"):
            if duration_seconds(record[field]) != duration_seconds(timing[field]):
                raise ValueError(f"Unexpected {field}")
        if java is not None and Path(record["jvm"]).resolve() != Path(java).resolve():
            raise ValueError("JMH used a different JVM")
        if java is not None and record["jvmArgs"] != timing["jvmArgs"]:
            raise ValueError("JMH JVM flags do not match the frozen protocol")
        for name, metric, unit in (("timing", record["primaryMetric"], "ns/op"),
                                   ("allocation", record["secondaryMetrics"]["gc.alloc.rate.norm"], "B/op")):
            finite_number(metric["score"], name, positive=name == "timing")
            if metric["scoreUnit"] != unit:
                raise ValueError(f"Unexpected {name} units")
            raw = metric["rawData"]
            if len(raw) != 1 or len(raw[0]) != timing["measurementIterations"]:
                raise ValueError(f"Missing {name} iteration data or extra forks")
            for value in raw[0]:
                finite_number(value, name, positive=name == "timing")
            if not math.isclose(metric["score"], statistics.mean(raw[0]), rel_tol=1e-8, abs_tol=1e-9):
                raise ValueError(f"{name} score does not agree with retained iteration data")
    except (KeyError, TypeError, IndexError, AttributeError) as error:
        raise ValueError("Malformed JMH result or missing timing/allocation data") from error
    return record


def duration_seconds(value):
    import re
    match = re.fullmatch(r"\s*([0-9]+(?:\.[0-9]+)?)\s*(ns|us|ms|s|m|h)\s*", value)
    if not match:
        raise ValueError(f"Invalid duration: {value}")
    return float(match[1]) * {"ns": 1e-9, "us": 1e-6, "ms": .001, "s": 1, "m": 60, "h": 3600}[match[2]]


def interval(values, logarithmic=False):
    values = [math.log(value) if logarithmic else value for value in values]
    mean = statistics.mean(values)
    if len(values) < 2:
        return math.exp(mean) if logarithmic else mean, None, None
    if len(values) - 1 not in T95:
        raise ValueError("The frozen protocol supports 2–10 independent rounds")
    half = T95[len(values) - 1] * statistics.stdev(values) / math.sqrt(len(values))
    if logarithmic:
        return math.exp(mean), math.exp(mean - half), math.exp(mean + half)
    return mean, mean - half, mean + half


def aggregate(records, cells, rounds):
    expected = {tuple(cell) for cell in cells}
    if len(expected) != len(cells):
        raise ValueError("Duplicate expected cells")
    grouped = defaultdict(dict)
    for record in records:
        key, round_number = cell_key(record), record["_round"]
        if key not in expected or round_number not in range(1, rounds + 1) or round_number in grouped[key]:
            raise ValueError("Duplicate, unexpected or invalid round measurement")
        grouped[key][round_number] = record
    if set(grouped) != expected or any(set(grouped[key]) != set(range(1, rounds + 1)) for key in expected):
        raise ValueError("Missing case/operation/engine/round measurements")
    result = {}
    for key in sorted(grouped):
        ordered = [grouped[key][index] for index in range(1, rounds + 1)]
        # Equal weight per independently started JVM, regardless of iteration count.
        times = [statistics.mean(item["primaryMetric"]["rawData"][0]) for item in ordered]
        allocations = [statistics.mean(item["secondaryMetrics"]["gc.alloc.rate.norm"]["rawData"][0]) for item in ordered]
        result[key] = {"time": interval(times), "allocation": interval(allocations),
                       "forkTimes": times, "forkAllocations": allocations, "records": ordered}
    return result


def paired_ratio(numerator, denominator):
    """One log(time_numerator / time_denominator) observation per matched round."""
    if len(numerator) != len(denominator):
        raise ValueError("Ratios require the same independent rounds")
    return interval([left / right for left, right in zip(numerator, denominator)], logarithmic=True)


def fmt(value):
    return f"{value:,.3f}"


def fmt_interval(result):
    mean, low, high = result
    return fmt(mean) if low is None else f"{fmt(mean)} [{fmt(low)}, {fmt(high)}]"


def api_contract(usage, wire_string_policy=None):
    """Keep historical contracts exact; new runs explicitly freeze the string policy."""
    if wire_string_policy is not None:
        if wire_string_policy not in ("reject", "replace"):
            raise ValueError("Unknown Wire string policy")
        return {**api_contract(usage),
                "wireMalformedStrings": "Reject unpaired UTF-16 surrogates" if wire_string_policy == "reject"
                                        else "Replace unpaired UTF-16 surrogates with ASCII ? using JDK UTF-8 encoding",
                "javaMalformedStrings": "Replace unpaired UTF-16 surrogates with ASCII ? using JDK UTF-8 encoding"}
    if usage == "reuse":
        return {
            "wireEncode": "Retained BinaryOutput: reset, codec.write, toByteArray; fresh independently owned Array[Byte]",
            "javaEncode": "Retained official RawMessageEncoder with default output copying and its direct encoder; independently owned ByteBuffer",
            "wireDecode": "codec.decode with fresh BinaryInput and fresh model; rejects trailing bytes",
            "javaDecode": "Retained official RawMessageDecoder and its direct decoder with no record reuse; fresh model; no trailing-byte rejection check",
            "text": "String values and map keys for every measured implementation",
            "inputs": "Valid raw Avro datum bytes; equivalent values, not equivalent malformed-input validation",
            "setup": "Input construction and retained codec, reader/writer, schema, message-helper, and working-buffer setup outside measurement",
            "scope": "Wire and supported Java variants; avro2s excluded",
        }
    if usage == "fresh":
        return {
            "wireEncode": "codec.encode with fresh BinaryOutput; fresh independently owned Array[Byte]",
            "javaEncode": "Fresh ByteArrayOutputStream and buffered BinaryEncoder; fresh independently owned Array[Byte]",
            "wireDecode": "codec.decode with fresh BinaryInput and fresh model; rejects trailing bytes",
            "javaDecode": "Fresh BinaryDecoder and fresh model; explicit complete-consumption check",
            "text": "String values and map keys for every measured implementation",
            "inputs": "Valid raw Avro datum bytes",
            "setup": "Input construction and reusable schema/reader setup outside measurement",
        }
    raise ValueError("Unknown API usage mode")


def wire_variant_contracts(usage, wire_string_policy, engines):
    """Additional contracts are explicit without relabelling historical reports."""
    native = api_contract(usage, wire_string_policy)
    contracts = {}
    for engine in WIRE_VARIANTS:
        if engine not in engines:
            continue
        if engine == "wire-stack-safe":
            contracts[engine] = {
                "codec": "Generated stackSafeCodec with native BinaryInput/BinaryOutput",
                "encode": native["wireEncode"], "decode": native["wireDecode"],
                "malformedStrings": native["wireMalformedStrings"],
                "readerValidation": "Native strict UTF-8 validation and trailing-byte rejection",
            }
        else:
            contracts[engine] = {
                "codec": "Generated " + ("stackSafeCodec" if engine == "wire-java-stack-safe" else "direct codec")
                         + " with public JavaAvroInput/JavaAvroOutput adapters",
                "encode": ("Retained ByteArrayOutputStream, buffered BinaryEncoder and JavaAvroOutput: reset, codec.write, flush, toByteArray"
                           if usage == "reuse" else
                           "Fresh ByteArrayOutputStream, buffered BinaryEncoder and JavaAvroOutput: codec.write, flush, toByteArray")
                          + "; fresh independently owned Array[Byte]",
                "decode": ("Retained JavaAvroInput and BinaryDecoder reconfigured with DecoderFactory.binaryDecoder(bytes, reuse)"
                           if usage == "reuse" else "Fresh BinaryDecoder and JavaAvroInput")
                          + "; fresh model; explicit complete-consumption check",
                "malformedStrings": "Java UTF-8 replacement behavior; wire-string-policy does not affect this backend",
                "readerValidation": "Java UTF-8 decoding behavior and explicit trailing-byte rejection",
            }
    return contracts


def validate_variant_records(records, plan):
    variants = plan["variants"]
    if not variants or len(variants) != len(set(variants)):
        raise ValueError("Invalid frozen variants")
    for label, variant in variants.items():
        engine, usage, policy = variant["engine"], variant["usage"], variant["wireStringPolicy"]
        if engine not in ENGINES or usage not in ("fresh", "reuse") or policy not in ("reject", "replace"):
            raise ValueError(f"Invalid frozen variant configuration: {label}")
        if (variant["apiContract"] != api_contract(usage, policy)
                or variant["wireVariantContracts"] != wire_variant_contracts(usage, policy, [engine])):
            raise ValueError(f"Frozen variant contract does not match configuration: {label}")
    for record in records:
        label = record.get("_variant")
        if label not in variants:
            raise ValueError("Result has a missing or unknown variant label")
        variant = variants[label]
        if any(record["params"].get(key) != variant[key] for key in ("engine", "usage", "wireStringPolicy")):
            raise ValueError(f"Result parameters do not match frozen variant: {label}")


def render_variants(records, metadata):
    """Named configurations share a schedule, but freeze their own lifecycle/policy."""
    plan = metadata["plan"]
    validate_variant_records(records, plan)
    variants, reference = plan["variants"], plan["referenceVariant"]
    if reference not in variants:
        raise ValueError("Unknown reference variant")
    values = aggregate(records, plan["cells"], plan["rounds"])
    cases = {case["id"]: case for case in metadata["catalog"]["cases"]}
    lines = ["# Consolidated Avro benchmark results", "",
             "**Diagnostic configuration comparison: unsuitable for publication claims.**", "",
             f"Source revision: `{metadata['gitRevision']}`. Profile: `{plan['profile']}`. Order seed: `{plan['seed']}`. "
             f"{len(values)} configuration/operation cells; {plan['rounds']} independent JVM rounds.", "",
             "| Configuration | Engine | Usage | Native string policy |", "| --- | --- | --- | --- |"]
    for label, variant in variants.items():
        policy = variant["wireStringPolicy"] if variant["engine"] in ("wire", "wire-stack-safe") else "Java behavior (native policy does not apply)"
        lines.append(f"| {label} | {variant['engine']} | {variant['usage']} | {policy} |")
    lines.append("")
    for label, variant in variants.items():
        contracts = variant["wireVariantContracts"].get(variant["engine"])
        if contracts is None:
            contract = variant["apiContract"]
            prefix = "wire" if variant["engine"] == "wire" else "java"
            contracts = {"encode": contract[prefix + "Encode"], "decode": contract[prefix + "Decode"],
                         "malformedStrings": contract[prefix + "MalformedStrings"],
                         "text": contract["text"], "setup": contract["setup"]}
        lines += [f"**`{label}`:** " + ". ".join(contracts.values()) + ".", ""]
    lines += ["Time is ns/op; allocation is allocated heap B/op, including temporary objects, not retained or peak memory. "
              "Bracketed intervals are pointwise 95% Student t intervals over independent JVM-round means; iterations within a JVM are not independent replicates. "
              "Timing intervals are not tail latency. "
              + ("Two JVM observations provide only a preliminary diagnostic; inspect both individual means. " if plan["rounds"] == 2 else ""), "",
              f"Ratios pair the same case/operation within each round: row configuration time / `{reference}` time. "
              "The centre is the geometric mean of round ratios, with a Student t interval on their logarithms. "
              "A ratio above one means the row configuration took more time than the denominator. "
              "Scheduling adjacency and order rotation reduce drift but do not remove machine or session effects. "
              "Intervals are not simultaneous guarantees across the table. The practical ratio band is 0.95–1.05; "
              "a quoted claim needs independent-session confirmation. No measurements are discarded. "
              "GC diagnostics and warmup/measurement traces remain in the raw files.", "",
              "[Environment and frozen protocol](environment.json) · [Raw round records](records.json) · [Source checksums](SHA256SUMS)", ""]
    for case_id, operation in dict.fromkeys((cell[0], cell[1]) for cell in plan["cells"]):
        lines += [f"## {case_id}: {cases[case_id]['name']} — {operation}", "",
                  f"| Configuration | ns/op [95% interval] | B/op [95% interval] | Configuration / {reference} [95% interval] |",
                  "| --- | ---: | ---: | ---: |"]
        denominator = values.get((case_id, operation, reference))
        for label in variants:
            value = values.get((case_id, operation, label))
            if value is None:
                reason = plan["omitted"].get(case_id, {}).get(operation, {}).get(label, "Outside this diagnostic selection")
                lines.append(f"| {label} | N/A: {reason} | — | — |")
            else:
                ratio = "—" if label == reference or denominator is None else fmt_interval(paired_ratio(value["forkTimes"], denominator["forkTimes"]))
                lines.append(f"| {label} | {fmt_interval(value['time'])} | {fmt_interval(value['allocation'])} | {ratio} |")
        lines += ["", "Independent JVM means (ns/op; B/op), in round order:", ""]
        for label in variants:
            value = values.get((case_id, operation, label))
            if value:
                forks = [f"[r{index}: {fmt(time)}; {fmt(allocation)}]({record['_sourceFile']})"
                         for index, (time, allocation, record) in enumerate(zip(value["forkTimes"], value["forkAllocations"], value["records"]), 1)]
                lines.append(f"- {label}: " + "; ".join(forks))
        lines.append("")
    return "\n".join(lines)


def render(records, metadata):
    plan = metadata["plan"]
    if "variants" in plan:
        return render_variants(records, metadata)
    usage = plan.get("usage", "fresh")
    policy = plan.get("wireStringPolicy")
    contract = api_contract(usage, policy)
    if "apiContract" in plan and plan["apiContract"] != contract:
        raise ValueError("Frozen API contract does not match usage mode or Wire string policy")
    if any(record["params"].get("usage", "fresh") != usage for record in records):
        raise ValueError("Result API usage does not match the report")
    if any(record["params"].get("wireStringPolicy") != policy for record in records):
        raise ValueError("Result Wire string policy does not match the report")
    selected_engines = {cell[2] for cell in plan["cells"]}
    reference_engine = plan.get("referenceEngine", "wire")
    if reference_engine not in ENGINES:
        raise ValueError("Unknown report reference engine")
    variant_contracts = wire_variant_contracts(usage, policy, selected_engines) if selected_engines.intersection(WIRE_VARIANTS) else {}
    if variant_contracts != plan.get("wireVariantContracts", {}):
        raise ValueError("Frozen Wire variant contracts do not match selected engines")
    # Existing reports retain their exact rows and wording. New variants appear
    # only when selected, rather than adding three N/A rows to every old result.
    report_engines = DEFAULT_ENGINES + tuple(engine for engine in WIRE_VARIANTS if engine in selected_engines)
    values = aggregate(records, plan["cells"], plan["rounds"])
    cases = {case["id"]: case for case in metadata["catalog"]["cases"]}
    diagnostic = plan["diagnostic"]
    contract_summary = (
        "Wire encode uses caller-owned reusable BinaryOutput and returns a fresh independently owned Array[Byte]. "
        "Java encode uses the official retained RawMessageEncoder, including its direct encoder and default output copying, "
        "and returns an independently owned ByteBuffer. No additional array conversion is measured. "
        "Wire decode uses codec.decode with fresh BinaryInput; Java decode uses retained RawMessageDecoder and its direct decoder without record reuse. "
        "Each decode creates a fresh model with String text. Wire rejects trailing bytes; Java RawMessageDecoder has no "
        "complete-consumption check. This compares valid-message APIs, not identical malformed-input validation. "
        "Input construction and retained codec, schema, reader/writer, message-helper, and working-buffer setup are outside measurement."
        if usage == "reuse" else
        "Each encode returns a fresh byte array; each decode creates a fresh model with String text. "
        "Working outputs and encoders/decoders are created per call. "
        "Input construction and reusable schema/reader setup are outside measurement."
    )
    policy_summary = (
        "" if policy is None else
        f"Wire string policy: `{policy}`. " +
        ("Wire rejects unpaired UTF-16 surrogates; Java replaces them with ASCII `?`. " if policy == "reject" else
         "Wire and Java replace unpaired UTF-16 surrogates with ASCII `?` using JDK UTF-8 encoding. ") +
        "The selected policy is prepared outside measurement and affects Wire encoding only; decoding is unchanged."
    )
    if variant_contracts:
        contract_summary = "The following base contracts describe `wire` and the official Java engines. " + contract_summary
        policy_summary = (f"Native Wire string policy: `{policy}`; applies to `wire` and `wire-stack-safe` only. "
                          "Java-backed Wire always uses Java string encoding and decoding behavior. "
                          "Each variant's effective contract is recorded below.")
    ratio_summary = (
        "Ratios pair the same case/operation within a round and use a Student t interval on log(reference time / Wire time). "
        "The reported centre is the geometric mean of round ratios. A ratio above one means the reference took more time. "
        if "referenceEngine" not in plan else
        f"Ratios pair the same case/operation within a round and use a Student t interval on log(engine time / {reference_engine} time). "
        f"The denominator is `{reference_engine}`; the numerator is the implementation named in each row. "
        "The reported centre is the geometric mean of round ratios. A ratio above one means the row implementation took more time than the denominator. "
        + ("No ratios are available because the denominator engine was not selected. " if reference_engine not in selected_engines else "")
    )
    variant_lines = []
    for engine, details in variant_contracts.items():
        variant_lines += [f"**`{engine}`:** " + ". ".join(details.values()) + ".", ""]
    lines = ["# Consolidated Avro benchmark results", "",
             "**Diagnostic run: unsuitable for publication claims.**" if diagnostic else f"{plan['rounds']} independent JVM rounds; individual comparisons only.", "",
             f"Source revision: `{metadata['gitRevision']}`. Profile: `{plan['profile']}`. "
             f"API usage: `{usage}`. Order seed: `{plan['seed']}`. {len(values)} implementation/operation cells; "
             f"{plan['rounds']} independent JVM rounds.", "",
             contract_summary, "", *([policy_summary, ""] if policy is not None else []), *variant_lines,
             "Time is ns/op; allocation is allocated heap B/op, including temporary objects, not retained or peak memory. "
             "Bracketed intervals are pointwise 95% Student t intervals over the independent JVM-round means. "
             f"They assume approximately normal independent round means; {plan['rounds']} rounds cannot establish that assumption. "
             + ("Two JVM observations provide only a preliminary diagnostic; inspect both individual means rather than treating the interval as strong certainty. "
                if plan["rounds"] == 2 else "") +
             "Iterations within a JVM are not independent replicates. Timing intervals are not tail latency.", "",
             ratio_summary +
             "Scheduling adjacency and order rotation reduce drift but do not remove machine or session effects. "
             "Intervals are not simultaneous guarantees across the table; there is no win count or overall speed score. "
             "The predeclared practical ratio band is 0.95–1.05; a quoted claim needs independent-session confirmation. "
             "No measurement is discarded or repeated until a desired result appears.", "",
             "GC count and GC time are retained as supporting diagnostics in every raw JMH JSON file. "
             "Warmup iterations and measured iterations are also preserved in each adjacent `.log` file. "
             "Successful execution alone does not establish that warmup or between-fork stability was adequate; inspect those traces before making claims.", "",
             "[Environment and frozen protocol](environment.json) · [Raw round records](records.json) · [Source checksums](SHA256SUMS)", ""]
    for case_id, operation in dict.fromkeys((cell[0], cell[1]) for cell in plan["cells"]):
        case = cases[case_id]
        ratio_heading = "Reference / Wire" if "referenceEngine" not in plan else f"Engine / {reference_engine}"
        lines += [f"## {case_id}: {case['name']} — {operation}", "",
                  f"| Implementation | ns/op [95% interval] | B/op [95% interval] | {ratio_heading} [95% interval] |",
                  "| --- | ---: | ---: | ---: |"]
        reference = values.get((case_id, operation, reference_engine))
        for engine in report_engines:
            value = values.get((case_id, operation, engine))
            if value is None:
                reason = plan["omitted"].get(case_id, {}).get(operation, {}).get(engine, "Outside this diagnostic selection")
                lines.append(f"| {engine} | N/A: {reason} | — | — |")
                continue
            ratio = "—" if engine == reference_engine or reference is None else fmt_interval(paired_ratio(value["forkTimes"], reference["forkTimes"]))
            lines.append(f"| {engine} | {fmt_interval(value['time'])} | {fmt_interval(value['allocation'])} | {ratio} |")
        lines += ["", "Independent JVM means (ns/op; B/op), in round order:", ""]
        for engine in report_engines:
            value = values.get((case_id, operation, engine))
            if value:
                forks = [f"[r{index}: {fmt(time)}; {fmt(allocation)}]({record['_sourceFile']})"
                         for index, (time, allocation, record) in enumerate(zip(value["forkTimes"], value["forkAllocations"], value["records"]), 1)]
                lines.append(f"- {engine}: " + "; ".join(forks))
        lines.append("")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path, help="Completed run directory")
    parser.add_argument("-o", "--output", type=Path)
    args = parser.parse_args()
    metadata = json.loads((args.run / "environment.json").read_text())
    if metadata["status"] != "complete" or not metadata["sourcesUnchanged"]:
        raise ValueError("Only successful, unchanged-source campaigns can be reported")
    records = json.loads((args.run / "records.json").read_text())
    # Revalidate raw files rather than trusting the convenience aggregate.
    for record in records:
        raw = json.loads((args.run / record["_sourceFile"]).read_text())
        plan = metadata["plan"]
        if "variants" in plan:
            validate_variant_records([record], plan)
            variant = plan["variants"][record["_variant"]]
            expected = (record["params"]["caseId"], record["benchmark"].removeprefix(BENCHMARK), variant["engine"])
        else:
            variant, expected = plan, cell_key(record)
        validated = validate_fork(raw, expected, plan["timing"], metadata["java"],
                                  usage=variant.get("usage"), wire_string_policy=variant.get("wireStringPolicy"))
        if validated != {key: value for key, value in record.items() if not key.startswith("_")}:
            raise ValueError("Raw file and aggregate records disagree")
    (args.output or args.run / "report.md").write_text(render(records, metadata))


if __name__ == "__main__":
    main()
