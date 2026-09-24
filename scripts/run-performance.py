#!/usr/bin/env python3
"""Run reproducible JMH profiles and retain results, logs, source provenance and a report."""
import argparse
from datetime import datetime, timezone
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
import tarfile

ROOT = Path(__file__).resolve().parents[1]
PREFIX = "avro2s.wire.benchmarks."
PROFILES = ("pilot", "focused", "api", "trade", "evolution", "comparison", "decoded-strings", "strings", "big-decimal", "full")
FULL_PROFILES = ("comparison", "evolution", "decoded-strings", "big-decimal", "api")
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
    "BigDecimal": {"digits": ["6", "50", "500"], "scale": ["0", "6", "-6"]},
}
SOURCE_SUFFIXES = {".scala", ".java", ".avsc", ".avdl", ".sbt", ".properties", ".py", ".sh"}
IGNORED_DIRECTORIES = {"target", ".git", ".bsp", ".metals", ".idea", "__pycache__", ".venv", "node_modules"}
# A deliberately bounded inherited environment is passed to subprocesses in full and recorded.
# This avoids retaining unrelated credentials while recording exactly what sbt and JMH receive.
ENVIRONMENT_KEYS = {
    "HOME", "USER", "LOGNAME", "PATH", "TMPDIR", "TMP", "TEMP", "SHELL", "LANG", "LC_ALL", "LC_CTYPE", "TZ",
    "JAVA_OPTS", "JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "SBT_OPTS", "JVM_OPTS",
    "SBT_GLOBAL_BASE", "SBT_BOOT_DIRECTORY", "SBT_IVY_HOME", "COURSIER_CACHE", "IVY_HOME", "XDG_CACHE_HOME",
}


def groups(profile):
    if profile == "full":
        return [group for component in FULL_PROFILES for group in groups(component)]
    if profile == "big-decimal":
        return [("BigDecimal", [engine + op for engine in ["nativeScalaMapping", "nativeJavaMapping", "avroJavaConversion"]
                                for op in ["Read", "Write"]], {})]
    if profile == "decoded-strings":
        return [("DecodedString", ["nativeRead", "javaPrimitivesRead", "javaGenericStringRead",
                                    "javaSpecificStringRead", "avro2sRead"], {})]
    if profile == "strings":
        return [("String", [engine + op for engine in ["native", "javaPrimitives"]
                            for op in ["Read", "Write", "Encode", "Decode"]], {})]
    if profile == "pilot":
        return [(name, ["nativeRead", "nativeWrite"], {}) for name in ["Integer", "String", "Bytes", "Collections", "NestedUnion"]]
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
    if profile == "evolution":
        return [("Evolution", ["nativeResolved", "javaResolved", "nativeSameSchema", "compileResolution"], {})]
    raise ValueError(f"Unknown profile: {profile}")


def is_source(relative):
    path = Path(relative)
    if any(part in IGNORED_DIRECTORIES for part in path.parts):
        return False
    if len(path.parts) > 1 and path.parts[0] == "benchmarks" and path.parts[1] in {"results", "archive", "archives"}:
        return False
    return path.suffix in SOURCE_SUFFIXES or path.name in {".jvmopts", ".sbtopts"}


def source_hashes(root, output=None):
    root = root.resolve()
    output = output.resolve() if output is not None else None
    result = {}
    for directory, children, files in os.walk(root):
        children[:] = sorted(name for name in children if name not in IGNORED_DIRECTORIES
                             and not (Path(directory).relative_to(root) == Path("benchmarks")
                                      and name in {"results", "archive", "archives"})
                             and (output is None or (Path(directory) / name).resolve() != output))
        for name in sorted(files):
            path = Path(directory) / name
            relative = str(path.relative_to(root))
            if is_source(relative):
                result[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def result_key(item):
    return item["benchmark"], tuple(sorted(item.get("params", {}).items()))


def parse_overrides(items):
    result = {}
    for item in items:
        if "=" not in item:
            raise ValueError("parameter overrides must use NAME=VALUES")
        name, values = item.split("=", 1)
        values = values.split(",")
        if not name or name in result or any(not value for value in values) or len(values) != len(set(values)):
            raise ValueError("empty or duplicate parameter names/values")
        result[name] = values
    return result


def execution_environment(java):
    env = {name: value for name, value in os.environ.items() if name in ENVIRONMENT_KEYS or name.startswith("LC_")}
    env.update(JAVA_HOME=str(java.parent.parent), JDK_HOME=str(java.parent.parent),
               PATH=str(java.parent) + os.pathsep + os.environ.get("PATH", os.defpath), TERM="dumb")
    return env


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", type=Path, required=True, help="Absolute JDK java executable; used by sbt and JMH forks")
    parser.add_argument("--output", type=Path, help="Output directory (default: benchmarks/results/<UTC timestamp>-<profile>)")
    parser.add_argument("--label", help="Optional filename prefix, for compatibility with earlier runner commands")
    parser.add_argument("--profile", choices=PROFILES, required=True)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--filter", default=".*", help="Regex narrowing the profile's full benchmark names")
    parser.add_argument("--param", action="append", default=[], metavar="NAME=VALUES", help="Comma-separated parameter override; repeatable")
    parser.add_argument("--forks", type=int)
    parser.add_argument("--warmup-iterations", type=int)
    parser.add_argument("--measurement-iterations", type=int)
    parser.add_argument("--warmup-time", help="JMH duration, e.g. 500ms or 1s")
    parser.add_argument("--measurement-time", help="JMH duration, e.g. 500ms or 1s")
    parser.add_argument("--skip-tests", action="store_true", help="Explicitly skip the default benchmarks/test correctness gate")
    parser.add_argument("--dry-run", action="store_true", help="Print the case counts, commands, paths and environment without running sbt or creating files")
    args = parser.parse_args(argv)
    if not args.java.is_absolute() or not args.java.is_file() or not os.access(args.java, os.X_OK):
        parser.error("--java must be an absolute executable file")
    args.java = args.java.resolve()
    if args.label is not None and not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", args.label):
        parser.error("--label must be a simple filename prefix")
    args.root = args.root.resolve()
    if not (args.root / "build.sbt").is_file():
        parser.error("--root must contain build.sbt")
    args.output = (args.output or args.root / "benchmarks/results" /
                   (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ") + "-" + args.profile)).resolve()
    # Never exclude the repository (or an ancestor) from its own source fingerprint.
    if args.output == args.root or args.output in args.root.parents:
        parser.error("--output must not be the source root or an ancestor of it")
    try:
        args.pattern = re.compile(args.filter)
        args.overrides = parse_overrides(args.param)
    except (ValueError, re.error) as error:
        parser.error(str(error))
    pilot = args.profile == "pilot"
    args.timing = dict(forks=args.forks if args.forks is not None else (1 if pilot else 2),
                       warmupIterations=args.warmup_iterations if args.warmup_iterations is not None else (2 if pilot else 3),
                       measurementIterations=args.measurement_iterations if args.measurement_iterations is not None else (3 if pilot else 5),
                       warmupTime=args.warmup_time or ("200ms" if pilot else "500ms"),
                       measurementTime=args.measurement_time or ("200ms" if pilot else "500ms"), threads=1, heap="512m")
    if args.timing["forks"] < 1 or args.timing["warmupIterations"] < 0 or args.timing["measurementIterations"] < 1:
        parser.error("forks and measurement iterations must be positive; warmup iterations may be zero")
    if any(not re.fullmatch(r"[1-9][0-9]*(?:ns|us|ms|s|m|h)", args.timing[name]) for name in ["warmupTime", "measurementTime"]):
        parser.error("timing values must be positive JMH durations, e.g. 500ms or 1s")
    try:
        args.runs = build_runs(args)
    except ValueError as error:
        parser.error(str(error))
    return args


def artifact_path(args, name):
    return args.output / (f"{args.label}-{name}" if args.label else name)


def build_runs(args):
    runs, used_params, all_expected = [], set(), set()
    timing = args.timing
    for name, methods, defaults in groups(args.profile):
        names = [PREFIX + name + "Benchmark." + method for method in methods]
        names = [method for method in names if args.pattern.search(method)]
        if not names:
            continue
        params = {**MATRIX[name], **defaults, **{key: value for key, value in args.overrides.items() if key in MATRIX[name]}}
        used_params.update(params.keys() & args.overrides.keys())
        expected = {(method, tuple(sorted(zip(params, values)))) for method in names
                    for values in itertools.product(*params.values())}
        if expected & all_expected:
            raise ValueError("profile contains duplicate benchmark cases")
        all_expected.update(expected)
        result, log = artifact_path(args, name + ".json"), artifact_path(args, name + ".log")
        tokens = ["-jvm", str(args.java), "-jvmArgs", "-Xms512m -Xmx512m", "-prof", "gc", "-foe", "true",
                  "-bm", "avgt", "-tu", "ns", "-t", "1",
                  "-f", str(timing["forks"]), "-wi", str(timing["warmupIterations"]), "-i", str(timing["measurementIterations"]),
                  "-w", timing["warmupTime"], "-r", timing["measurementTime"], "-rf", "json", "-rff", str(result)]
        for key, values in params.items():
            tokens += ["-p", key + "=" + ",".join(values)]
        tokens += ["^(?:" + "|".join(value.replace(".", "[.]") for value in names) + ")$"]
        command = sbt_command(args) + ["benchmarks/Jmh/run " + " ".join(json.dumps(token) for token in tokens)]
        runs.append(dict(name=name, command=command, result=str(result), log=str(log), expected=expected))
    if not runs or set(args.overrides) != used_params:
        raise ValueError("filter selected no benchmarks, or a parameter override applies to none of the selected classes")
    return runs


def sbt_command(args):
    return ["sbt", "-java-home", str(args.java.parent.parent)]


def public_runs(runs):
    return [{key: value for key, value in run.items() if key != "expected"} |
            {"expectedCases": len(run["expected"])} for run in runs]


def validate_records(records, run, timing, log_text=""):
    try:
        if (not isinstance(records, list) or len(records) != len(run["expected"])
                or {result_key(item) for item in records} != run["expected"]):
            raise ValueError("Missing, duplicate or unexpected benchmark results")
        if "<failure>" in log_text:
            raise ValueError("JMH reported a benchmark failure")
        for item in records:
            for metric, unit in [(item["primaryMetric"], "ns/op"), (item["secondaryMetrics"]["gc.alloc.rate.norm"], "B/op")]:
                value = metric["score"]
                if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
                    raise ValueError("Non-finite or negative latency/allocation metric")
                if metric["scoreUnit"] != unit:
                    raise ValueError(f"Unexpected metric unit; expected {unit}")
            if item["mode"] != "avgt" or item["forks"] != timing["forks"] or item["threads"] != 1:
                raise ValueError("Unexpected benchmark mode, fork count or thread count")
    except (KeyError, TypeError) as error:
        raise ValueError("Malformed JMH result or missing latency/allocation metric") from error


def capture(command, root, env, check=True):
    result = subprocess.run(command, cwd=root, env=env, text=True, capture_output=True, check=check)
    return result.stdout + result.stderr if check else result


def git_provenance(root, env, before):
    revision = capture(["git", "rev-parse", "HEAD"], root, env, check=False)
    if revision.returncode != 0:
        return dict(gitRevision=None, gitStatus=None, dirtySources=True, deletedSources=[])
    status = capture(["git", "status", "--short", "--untracked-files=all"], root, env)
    tracked = set(capture(["git", "ls-files", "-z"], root, env).split("\0")) - {""}
    changed = set(capture(["git", "diff", "--name-only", "-z", "HEAD"], root, env).split("\0")) - {""}
    deleted = sorted(path for path in tracked if is_source(path) and not (root / path).exists())
    dirty = bool(set(before) - tracked or changed & set(before) or deleted)
    return dict(gitRevision=revision.stdout.strip(), gitStatus=status, dirtySources=dirty, deletedSources=deleted)


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def main(argv=None):
    args = parse_args(argv)
    root, output, runs = args.root, args.output, args.runs
    env = execution_environment(args.java)
    # Keep the original label.environment.json naming for existing automation.
    metadata_path = output / (f"{args.label}.environment.json" if args.label else "environment.json")
    report_path = output / (f"{args.label}.report.md" if args.label else "report.md")
    correctness = dict(command=sbt_command(args) + ["benchmarks/test"],
                       log=str(artifact_path(args, "correctness.log")), status="skipped" if args.skip_tests else "pending")
    plan = dict(profile=args.profile, output=str(output), metadata=str(metadata_path), report=str(report_path),
                java=str(args.java), timing=args.timing, expectedCases=sum(len(run["expected"]) for run in runs),
                environment=env, correctness=correctness, runs=public_runs(runs))
    if args.dry_run:
        print(json.dumps(plan, indent=2))
        return 0
    snapshot_path = artifact_path(args, "source-snapshot.tar.gz")
    sums_path = artifact_path(args, "SHA256SUMS")
    artifacts = [metadata_path, report_path, snapshot_path, sums_path, Path(correctness["log"])]
    artifacts += [Path(run[key]) for run in runs for key in ["result", "log"]]
    if any(path.exists() for path in artifacts):
        raise ValueError("output files already exist; choose a new --output or --label")
    output.mkdir(parents=True, exist_ok=True)
    before = source_hashes(root, output)
    java_version = capture([str(args.java), "-version"], root, env)
    metadata = dict(plan, startedAt=datetime.now(timezone.utc).isoformat(), status="running", sourceRoot=str(root),
                    javaVersion=java_version, platform=platform.platform(), machine=platform.machine(),
                    cpuCount=os.cpu_count(), pythonVersion=sys.version,
                    sbtVersion=(root / "project/build.properties").read_text().strip(), sourceSha256=before,
                    **git_provenance(root, env, before))
    # Record resolved options, so later reproduction does not depend on changed defaults.
    metadata["reproductionCommand"] = [sys.executable, str(root / "scripts/run-performance.py"),
        "--root", str(root), "--profile", args.profile, "--java", str(args.java), "--output", str(output),
        "--filter", args.filter, "--forks", str(args.timing["forks"]),
        "--warmup-iterations", str(args.timing["warmupIterations"]),
        "--measurement-iterations", str(args.timing["measurementIterations"]),
        "--warmup-time", args.timing["warmupTime"], "--measurement-time", args.timing["measurementTime"]]
    if args.label:
        metadata["reproductionCommand"] += ["--label", args.label]
    for item in args.param:
        metadata["reproductionCommand"] += ["--param", item]
    if args.skip_tests:
        metadata["reproductionCommand"].append("--skip-tests")
    if platform.system() == "Darwin":
        metadata["cpu"] = capture(["sysctl", "-n", "machdep.cpu.brand_string"], root, env).strip()
    write_json(metadata_path, metadata)
    all_records = []
    try:
        if metadata["dirtySources"]:
            with tarfile.open(snapshot_path, "w:gz") as archive:
                for relative in before:
                    archive.add(root / relative, arcname=relative, recursive=False)
            metadata["sourceSnapshot"] = str(snapshot_path)
            metadata["sourceRestore"] = ("Check out gitRevision, extract sourceSnapshot over that checkout, and remove deletedSources. "
                                         "The snapshot contains every file listed in sourceSha256; generated outputs and benchmark archives are excluded.")
            write_json(metadata_path, metadata)
        if source_hashes(root, output) != before:
            raise RuntimeError("Sources changed while capturing provenance")
        if not args.skip_tests:
            print(f"Running benchmark correctness tests; log: {correctness['log']}", flush=True)
            correctness["status"] = "running"
            with Path(correctness["log"]).open("w") as log:
                subprocess.run(correctness["command"], cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
            correctness["status"] = "passed"
            write_json(metadata_path, metadata)
        if source_hashes(root, output) != before:
            raise RuntimeError("Sources changed during correctness tests")
        for run in runs:
            print(f"Running {run['name']}: {len(run['expected'])} cases; log: {run['log']}", flush=True)
            with Path(run["log"]).open("w") as log:
                subprocess.run(run["command"], cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
            records = json.loads(Path(run["result"]).read_text())
            validate_records(records, run, args.timing, Path(run["log"]).read_text())
            if source_hashes(root, output) != before:
                raise RuntimeError("Sources changed during measurement; results are not an accepted comparison")
            all_records.extend({**record, "_sourceFile": str(run["result"])} for record in records)
        # Import only for measured runs, so --dry-run remains a cheap planning check.
        from benchmark_report import render
        metadata.update(status="complete", sourcesUnchanged=True)
        report = render(all_records, metadata=metadata)
        if source_hashes(root, output) != before:
            raise RuntimeError("Sources changed while creating the report")
        report_path.write_text(report)
    except BaseException as error:
        metadata.update(status="failed", error=f"{type(error).__name__}: {error}")
        if correctness["status"] == "running":
            correctness["status"] = "failed"
        raise
    finally:
        after = source_hashes(root, output)
        metadata.update(finishedAt=datetime.now(timezone.utc).isoformat(), sourceSha256After=after,
                        sourcesUnchanged=(before == after))
        if metadata["status"] != "complete" or before != after:
            metadata["status"] = "failed"
            report_path.unlink(missing_ok=True)
        write_json(metadata_path, metadata)
        sums_path.write_text("".join(hashlib.sha256(path.read_bytes()).hexdigest() + "  " + path.name + "\n"
                                    for path in sorted(artifacts) if path.is_file() and path != sums_path))
        if before != after:
            raise RuntimeError("Sources changed during the run; see failed environment metadata")
    print(f"Completed {plan['expectedCases']} cases. Report: {report_path}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
