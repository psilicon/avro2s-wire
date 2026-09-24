#!/usr/bin/env python3
"""Run only the BigDecimal benchmark and its focused correctness test; retain an ignored audit bundle."""
import argparse
from datetime import datetime, timezone
import hashlib
import itertools
import json
import math
import os
from pathlib import Path
import platform
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
PREFIX = "avro2s.wire.benchmarks.BigDecimalBenchmark."
ENGINES = ["nativeScalaMapping", "nativeJavaMapping", "avroJavaConversion"]
DIGITS, SCALES = ["6", "50", "500"], ["0", "6", "-6"]


def source_files():
    for directory, children, files in os.walk(ROOT):
        children[:] = sorted(c for c in children if c not in
                             {"target", ".git", ".bsp", ".metals", ".idea", "__pycache__"})
        for name in sorted(files):
            path = Path(directory) / name
            if path.suffix in {".scala", ".java", ".avsc", ".sbt", ".properties", ".py", ".sh"} or name in {".jvmopts", ".sbtopts"}:
                yield path


def hashes():
    return {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in source_files()}


def capture(command):
    result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, check=True)
    return result.stdout + result.stderr


def report(records, output):
    by_key = {(r["benchmark"].removeprefix(PREFIX), r["params"]["digits"], r["params"]["scale"]): r for r in records}
    lines = ["# Big-decimal logical codec benchmark", "",
             "JMH average ns/op and gc.alloc.rate.norm B/op; lower is better. Ratios are Avro ns/op divided by native ns/op (above 1 means native is faster).",
             "", "Each cell is **ns/op ± JMH 99.9% error / B/op**. Two forks, three 500 ms warmups, five 500 ms measurements, one thread, 512 MiB heap, Corretto 21.", ""]
    for operation in ["Read", "Write"]:
        lines += ["## " + operation, "", "| Digits | Scale | Native Scala mapping | Native Java mapping | Avro Java conversion | Avro / Scala | Avro / Java |",
                  "|---:|---:|---:|---:|---:|---:|---:|"]
        for digits, scale in itertools.product(DIGITS, SCALES):
            values = [by_key[(engine + operation, digits, scale)] for engine in ENGINES]
            ns = [r["primaryMetric"]["score"] for r in values]
            cells = [f'{r["primaryMetric"]["score"]:.1f} ± {r["primaryMetric"]["scoreError"]:.1f} / {r["secondaryMetrics"]["gc.alloc.rate.norm"]["score"]:.1f}' for r in values]
            lines.append(f'| {digits} | {scale} | ' + " | ".join(cells) + f' | {ns[2] / ns[0]:.2f}× | {ns[2] / ns[1]:.2f}× |')
        lines.append("")
    lines += ["## Scope and caveats", "",
              "This measures the Avro `big-decimal` logical type, not constrained `decimal`. Six, 50 and 500 digits refer to unscaled integer precision. Each case cycles through 16 deterministic values, eight positive and eight negative; scale is exactly 0, 6 or -6. Values and Scala exact wrappers are prepared before timing.", "",
              "One operation reads or writes one complete logical value including the outer Avro bytes field. Native Scala and native Java mapping both use Wire's native BinaryInput/BinaryOutput and LogicalValues helpers; Java mapping does not mean the optional Java primitive backend. The official Apache Avro 1.12.1 baseline calls Conversions.BigDecimalConversion and its binary encoder/decoder. No generated records or datum readers/writers are measured.", "",
              "All readers use the same bytes prepared by official Avro, create fresh outer decoding contexts and materialize fresh logical values. Writes reuse and reset outer output buffers, and the Avro encoder is reused and flushed each operation; the observable result is byte count. Final output copies, corpus construction, verification, parsing and normalization are outside timing. Both libraries' internal nested payload allocations and copies remain inside timing.", "",
              "The focused test and each fork's setup verify exact scale/unscaled integer, identical full wire bytes, and cross-reading all three writers with all three readers. Outer exhaustion checks are untimed. Wire's helper retains its nested length limits and trailing-payload validation; official Avro's conversion has different validation. These are each implementation's real paths, not identical validation policies.", "",
              "These are local microbenchmarks on one machine. The 500 ms iterations limit precision; retain the reported errors and inspect fork-level data before treating small differences as meaningful. Ratios use point estimates, not ratio confidence intervals. Scala reads add an exact wrapper around the Java decimal; Java and Scala native writes delegate to the same Java-valued helper, so small write differences are not evidence of distinct algorithms.", "",
              "Reproduce with the command in environment.json. This directory contains raw results.json, run.log, environment.json, working-tree.patch, source-snapshot.tar.gz and SHA256SUMS. The source archive includes tracked and untracked code/build/scripts at run start, because the feature was measured in an uncommitted working tree. Source hashes are checked after the run. Results remain under ignored benchmarks/target by default."]
    (output / "report.md").write_text("\n".join(lines) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", type=Path, required=True, help="Absolute Corretto 21 java executable; used for sbt and forks")
    parser.add_argument("--output", type=Path, default=ROOT / "benchmarks/target/big-decimal-2026-09-24")
    args = parser.parse_args()
    java = args.java.resolve()
    if not java.is_file() or not os.access(java, os.X_OK):
        parser.error("--java must be an executable")
    java_version = capture([str(java), "-version"])
    if 'version "21' not in java_version or "Corretto" not in java_version:
        parser.error("Use Corretto JDK 21 for this comparison")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        parser.error("--output must be empty; preserve previous runs")
    before = hashes()
    (output / "working-tree.patch").write_text(capture(["git", "diff", "--binary", "HEAD"]))
    with tarfile.open(output / "source-snapshot.tar.gz", "w:gz") as archive:
        for relative in before:
            archive.add(ROOT / relative, arcname=relative)
    tokens = ["-jvm", str(java), "-jvmArgs", "-Xms512m -Xmx512m", "-prof", "gc", "-foe", "true",
              "-f", "2", "-wi", "3", "-i", "5", "-w", "500ms", "-r", "500ms", "-t", "1",
              "-p", "digits=" + ",".join(DIGITS), "-p", "scale=" + ",".join(SCALES),
              "-rf", "json", "-rff", str(output / "results.json"),
              "^avro2s[.]wire[.]benchmarks[.]BigDecimalBenchmark[.].*$"]
    command = ["sbt", "-java-home", str(java.parent.parent),
               "benchmarks/testOnly avro2s.wire.benchmarks.BigDecimalBenchmarkSuite",
               "benchmarks/Jmh/run " + " ".join(json.dumps(t) for t in tokens)]
    metadata = dict(startedAt=datetime.now(timezone.utc).isoformat(), status="running", sourceRoot=str(ROOT),
                    gitRevision=capture(["git", "rev-parse", "HEAD"]).strip(), gitStatus=capture(["git", "status", "--short"]),
                    java=str(java), javaVersion=java_version, platform=platform.platform(), machine=platform.machine(),
                    cpuCount=os.cpu_count(), sbtVersion=(ROOT / "project/build.properties").read_text().strip(),
                    command=command, reproductionCommand=["python3", str(Path(__file__).resolve()), "--java", str(java), "--output", str(output)],
                    expectedCases=54, sourceSha256=before)
    if platform.system() == "Darwin":
        metadata["cpu"] = capture(["sysctl", "-n", "machdep.cpu.brand_string"]).strip()
    meta_path = output / "environment.json"
    meta_path.write_text(json.dumps(metadata, indent=2) + "\n")
    print(f"Starting focused test then 54 JMH cases; log: {output / 'run.log'}", flush=True)
    try:
        env = dict(os.environ, JAVA_HOME=str(java.parent.parent))
        with (output / "run.log").open("w") as log:
            subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
        records = json.loads((output / "results.json").read_text())
        expected = {(PREFIX + engine + op, digits, scale) for engine, op, digits, scale in
                    itertools.product(ENGINES, ["Read", "Write"], DIGITS, SCALES)}
        actual = {(r["benchmark"], r["params"]["digits"], r["params"]["scale"]) for r in records}
        if len(records) != 54 or actual != expected or "<failure>" in (output / "run.log").read_text():
            raise RuntimeError("Incomplete or failed JMH matrix")
        for record in records:
            for metric in [record["primaryMetric"], record["secondaryMetrics"]["gc.alloc.rate.norm"]]:
                if not math.isfinite(metric["score"]):
                    raise RuntimeError("Non-finite benchmark metric")
            if record["forks"] != 2 or record["threads"] != 1:
                raise RuntimeError("Unexpected fork/thread count")
        if hashes() != before:
            raise RuntimeError("Sources changed during the run")
        report(records, output)
        metadata["status"] = "complete"
    finally:
        after = hashes()
        metadata.update(finishedAt=datetime.now(timezone.utc).isoformat(), sourceSha256After=after, sourcesUnchanged=(before == after))
        if metadata["status"] != "complete":
            metadata["status"] = "failed"
        meta_path.write_text(json.dumps(metadata, indent=2) + "\n")
        (output / "SHA256SUMS").write_text("".join(hashlib.sha256(p.read_bytes()).hexdigest() + "  " + p.name + "\n"
                                                 for p in sorted(output.iterdir()) if p.is_file() and p.name != "SHA256SUMS"))
    print(f"Complete: {output / 'report.md'}", flush=True)


if __name__ == "__main__":
    main()
