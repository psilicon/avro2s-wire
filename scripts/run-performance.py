#!/usr/bin/env python3
"""Run reproducible JMH profiles; retain raw results, logs, commands and source hashes."""
import argparse
import hashlib
import itertools
import json
import math
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
from datetime import datetime, timezone

PREFIX = "avro2s.wire.benchmarks."
MATRIX = {
    "Integer": {"kind": ["int", "long"], "distribution": ["one-byte", "medium", "wide", "mixed"]},
    "String": {"profile": ["empty", "ascii-short", "ascii-long", "multilingual-short", "multilingual-long", "emoji-short", "emoji-long",
                            "latin1-short", "latin1-long", "question-short", "question-long", "replacement-short", "replacement-long",
                            "supplementary-prefix-ascii-long", "supplementary-prefix-bmp-long", "supplementary-mixed-long", "supplementary-mixed-short"]},
    "Bytes": {"byteCount": ["0", "32", "4096"]},
    "Collections": {"collectionSize": ["0", "4", "128"]},
    "NestedUnion": {"depth": ["0", "1", "4"]},
    "Trade": {"collectionSize": ["0", "32", "1024"]},
    "Evolution": {"discardedBytes": ["0", "4096"]},
    "Comparison": {"profile": ["ints-small", "ints-wide", "longs-mixed", "string-ascii", "string-unicode",
                                   "bytes", "collections-empty", "collections-full", "enum-fixed", "numerics"]},
    "NestedComparison": {},
    "LogicalComparison": {},
    "DecimalComparison": {},
    "DecodedString": {"profile": ["string-ascii", "string-unicode", "collections-full"]},
}


def groups(profile):
    if profile == "decoded-strings":
        return [("DecodedString", ["nativeRead", "javaPrimitivesRead", "javaGenericStringRead",
                                    "javaSpecificStringRead", "avro2sRead"], {})]
    if profile == "strings":
        return [("String", [engine + op for engine in ["native", "javaPrimitives"]
                            for op in ["Read", "Write", "Encode", "Decode"]], {})]
    if profile == "pilot":
        return [(name, ["nativeRead", "nativeWrite"], {}) for name in list(MATRIX)[:5]]
    if profile == "focused":
        return [("Integer", ["nativeWrite"], {}), ("String", ["nativeRead", "nativeWrite"], {}),
                ("Collections", ["nativeRead"], {})]
    if profile == "api":
        return [(name, ["nativeEncode", "nativeDecode"], params) for name, params in [
            ("Integer", {"kind": ["int"], "distribution": ["mixed"]}),
            ("String", {"profile": ["ascii-short", "emoji-long"]}),
            ("Collections", {"collectionSize": ["0", "128"]}),
            ("Bytes", {"byteCount": ["4096"]}), ("NestedUnion", {"depth": ["1"]})]]
    if profile == "trade":
        return [("Trade", [engine + op for engine in ["native", "javaPrimitives", "javaGeneric",
                 "javaSpecific", "javaCustom", "avro2s"] for op in ["Read", "Write"]], {})]
    if profile == "comparison":
        return [(name, [engine + op for engine in engines for op in ["Read", "Write"]], {})
                for name, engines in [
                    ("Comparison", ["native", "javaPrimitives", "javaGeneric", "javaSpecific", "javaCustom", "avro2s"]),
                    ("NestedComparison", ["native", "javaPrimitives", "javaGeneric", "javaSpecific", "avro2s"]),
                    ("LogicalComparison", ["native", "javaPrimitives", "javaGeneric", "javaSpecific", "avro2s"]),
                    ("DecimalComparison", ["native", "javaPrimitives", "javaGeneric", "javaSpecific"])]]
    return [("Evolution", ["nativeResolved", "javaResolved", "nativeSameSchema", "compileResolution"], {})]


def source_hashes(root):
    result = {}
    for directory, children, files in os.walk(root):
        children[:] = sorted(name for name in children if name not in
                             {"target", ".git", ".bsp", ".metals", ".idea", "__pycache__"})
        for name in sorted(files):
            path = Path(directory) / name
            if path.suffix in {".scala", ".java", ".avsc", ".sbt", ".properties", ".py", ".sh"} or name in {".jvmopts", ".sbtopts"}:
                result[str(path.relative_to(root))] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def result_key(item):
    return item["benchmark"], tuple(sorted(item.get("params", {}).items()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", type=Path, required=True, help="Absolute fork JVM executable")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--label", required=True, help="Unique filename prefix")
    parser.add_argument("--profile", choices=["pilot", "focused", "api", "trade", "evolution", "comparison", "decoded-strings", "strings"], required=True)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--filter", default=".*", help="Regex narrowing the profile's full benchmark names")
    parser.add_argument("--param", action="append", default=[], metavar="NAME=VALUES", help="Comma-separated parameter override; repeatable")
    parser.add_argument("--forks", type=int)
    parser.add_argument("--warmup-iterations", type=int)
    parser.add_argument("--measurement-iterations", type=int)
    parser.add_argument("--warmup-time", help="JMH duration, e.g. 500ms or 1s")
    parser.add_argument("--measurement-time", help="JMH duration, e.g. 500ms or 1s")
    args = parser.parse_args()
    if not args.java.is_absolute() or not args.java.is_file() or not os.access(args.java, os.X_OK):
        parser.error("--java must be an absolute executable file")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", args.label):
        parser.error("--label must be a simple filename prefix")
    root, output = args.root.resolve(), args.output.resolve()
    if not (root / "build.sbt").is_file():
        parser.error("--root must contain build.sbt")
    try:
        pattern = re.compile(args.filter)
        overrides = dict(item.split("=", 1) for item in args.param)
        overrides = {name: values.split(",") for name, values in overrides.items()}
        if any(not name or any(not value for value in values) or len(values) != len(set(values)) for name, values in overrides.items()):
            raise ValueError("empty/duplicate parameter values")
    except (ValueError, re.error) as error:
        parser.error(str(error))
    pilot = args.profile == "pilot"
    timing = dict(forks=args.forks if args.forks is not None else (1 if pilot else 2),
                  warmupIterations=args.warmup_iterations if args.warmup_iterations is not None else (2 if pilot else 3),
                  measurementIterations=args.measurement_iterations if args.measurement_iterations is not None else (3 if pilot else 5),
                  warmupTime=args.warmup_time or ("200ms" if pilot else "500ms"),
                  measurementTime=args.measurement_time or ("200ms" if pilot else "500ms"))
    if timing["forks"] < 1 or timing["warmupIterations"] < 0 or timing["measurementIterations"] < 1:
        parser.error("forks and measurement iterations must be positive; warmup iterations may be zero")
    if any(not re.fullmatch(r"[1-9][0-9]*(?:ns|us|ms|s|m|h)", timing[name]) for name in ["warmupTime", "measurementTime"]):
        parser.error("timing values must be positive JMH durations, e.g. 500ms or 1s")
    runs, used_params = [], set()
    for name, methods, defaults in groups(args.profile):
        names = [PREFIX + name + "Benchmark." + method for method in methods]
        names = [name for name in names if pattern.search(name)]
        if not names:
            continue
        params = {**MATRIX[name], **defaults, **{key: value for key, value in overrides.items() if key in MATRIX[name]}}
        used_params.update(params.keys() & overrides.keys())
        expected = {(method, tuple(sorted(zip(params, values)))) for method in names
                    for values in itertools.product(*params.values())}
        result, log = output / f"{args.label}-{name}.json", output / f"{args.label}-{name}.log"
        tokens = ["-jvm", str(args.java), "-jvmArgs", "-Xms512m -Xmx512m", "-prof", "gc", "-foe", "true",
                  "-f", str(timing["forks"]), "-wi", str(timing["warmupIterations"]), "-i", str(timing["measurementIterations"]),
                  "-w", timing["warmupTime"], "-r", timing["measurementTime"], "-rf", "json", "-rff", str(result)]
        for key, values in params.items():
            tokens += ["-p", key + "=" + ",".join(values)]
        tokens += ["^(?:" + "|".join(value.replace(".", "[.]") for value in names) + ")$"]
        command = ["sbt", "benchmarks/Jmh/run " + " ".join(json.dumps(token) for token in tokens)]
        runs.append(dict(name=name, command=command, result=str(result), log=str(log), expected=expected))
    if not runs or set(overrides) != used_params:
        parser.error("filter selected no benchmarks, or a parameter override applies to none of the selected classes")
    output.mkdir(parents=True, exist_ok=True)
    metadata_path = output / f"{args.label}.environment.json"
    if any(path.exists() for path in [metadata_path] + [Path(run[key]) for run in runs for key in ["result", "log"]]):
        parser.error("output files already exist; use a new --label")
    before = source_hashes(root)
    version = subprocess.run([str(args.java), "-version"], text=True, capture_output=True, check=True)
    revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, text=True, capture_output=True)
    metadata = dict(startedAt=datetime.now(timezone.utc).isoformat(), status="running", profile=args.profile,
                    sourceRoot=str(root), gitRevision=revision.stdout.strip() if revision.returncode == 0 else None,
                    java=str(args.java), javaVersion=version.stdout + version.stderr, platform=platform.platform(),
                    machine=platform.machine(), cpuCount=os.cpu_count(), pythonVersion=sys.version,
                    sbtVersion=(root / "project/build.properties").read_text().strip(), timing=timing,
                    sourceSha256=before, runs=[{key: value for key, value in run.items() if key != "expected"} |
                    {"expectedCases": len(run["expected"])} for run in runs])
    metadata_path.write_text(json.dumps(metadata, indent=2) + "\n")
    try:
        for run in runs:
            print(f"Running {run['name']}: {len(run['expected'])} cases; log {run['log']}", flush=True)
            with Path(run["log"]).open("w") as log:
                subprocess.run(run["command"], cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True)
            records = json.loads(Path(run["result"]).read_text())
            if not isinstance(records, list) or len(records) != len(run["expected"]) or {result_key(item) for item in records} != run["expected"]:
                raise RuntimeError(f"Missing, duplicate or unexpected benchmark results: {run['result']}")
            if any(not math.isfinite(item["primaryMetric"]["score"]) for item in records) or "<failure>" in Path(run["log"]).read_text():
                raise RuntimeError(f"JMH reported failure: {run['log']}")
            if source_hashes(root) != before:
                raise RuntimeError("Sources changed during measurement; results are not an accepted comparison")
        metadata["status"] = "complete"
    finally:
        after = source_hashes(root)
        metadata.update(finishedAt=datetime.now(timezone.utc).isoformat(), sourceSha256After=after,
                        sourcesUnchanged=(before == after))
        if metadata["status"] != "complete" or before != after:
            metadata["status"] = "failed"
        metadata_path.write_text(json.dumps(metadata, indent=2) + "\n")
        if before != after:
            raise RuntimeError("Sources changed during measurement; see failed environment metadata")
    print(f"Completed {sum(len(run['expected']) for run in runs)} cases. Metadata: {metadata_path}")


if __name__ == "__main__":
    main()
