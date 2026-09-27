#!/usr/bin/env python3
"""Run the approved Avro benchmark catalogue with a frozen, auditable protocol."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import random
import re
import shutil
import subprocess
import sys
import tarfile

from benchmark_suite_report import BENCHMARK, ENGINES, render, validate_fork

ROOT = Path(__file__).resolve().parents[1]
CATALOG = Path("benchmarks/src/main/resources/suite/catalog.json")
CAPABILITIES = Path("benchmarks/generator/suite-baselines.json")
EXCLUDED = {"target", ".git", ".bsp", ".bloop", ".metals", ".idea", "__pycache__", ".venv", "node_modules"}
SOURCE_SUFFIXES = {".scala", ".java", ".avsc", ".avdl", ".sbt", ".properties", ".py", ".sh", ".json"}
ENV_KEYS = {"HOME", "USER", "LOGNAME", "PATH", "TMPDIR", "TMP", "TEMP", "SHELL", "LANG", "LC_ALL", "LC_CTYPE", "TZ",
            "SBT_GLOBAL_BASE", "SBT_BOOT_DIRECTORY", "SBT_IVY_HOME", "COURSIER_CACHE", "IVY_HOME", "XDG_CACHE_HOME"}
OPTION_VARIABLES = ("JAVA_OPTS", "JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "SBT_OPTS", "JVM_OPTS")


def load_inputs(root):
    catalog = json.loads((root / CATALOG).read_text())
    capabilities = json.loads((root / CAPABILITIES).read_text())
    cases = catalog["cases"]
    ids = [case["id"] for case in cases]
    if len(ids) != len(set(ids)) or not ids:
        raise ValueError("Catalogue contains duplicate IDs or no cases")
    capability_rows = capabilities["cases"]
    if len(capability_rows) != len(ids) or {case["id"] for case in capability_rows} != set(ids):
        raise ValueError("Capability manifest does not exactly match catalogue IDs")
    for case in cases:
        if not re.fullmatch(r"[A-Z][0-9]{2}", case["id"]):
            raise ValueError("Invalid catalogue ID")
        if case["operations"] not in (["encode", "decode"], ["decode"]):
            raise ValueError("Invalid catalogue operations")
        if not isinstance(case["avro2s"], bool):
            raise ValueError("Catalogue avro2s support must be boolean")
    operations = {case["id"]: case["operations"] for case in cases}
    for case in capability_rows:
        if set(case["engines"]) != set(ENGINES):
            raise ValueError("Capability manifest must describe every engine")
        for engine in ENGINES:
            capability = case["engines"][engine]
            supported_ops = capability["operations"]
            if (not isinstance(capability["supported"], bool)
                    or capability["supported"] != bool(supported_ops)
                    or len(supported_ops) != len(set(supported_ops))
                    or not set(supported_ops).issubset(operations[case["id"]])
                    or (set(supported_ops) != set(operations[case["id"]]) and not capability.get("reason"))):
                raise ValueError("Engine capabilities require explicit supported operations and any exclusion reason")
    for source in capabilities.get("sources", []):
        path = root / source["path"]
        if path.resolve().is_relative_to(root.resolve()) is False or not path.is_file() or sha256(path) != source["sha256"]:
            raise ValueError(f"Generated baseline provenance mismatch: {source['path']}")
    return catalog, capabilities


def make_plan(catalog, capabilities, profile, seed=20260926, cases=None, engines=None):
    case_filter = set(cases or (("P03", "T11", "C02") if profile == "pilot" else ("P03",) if profile == "smoke" else ()))
    selected_engines = tuple(engines or (("wire", "java-specific") if profile in ("pilot", "smoke") else ENGINES))
    all_ids = {case["id"] for case in catalog["cases"]}
    if case_filter - all_ids or set(selected_engines) - set(ENGINES):
        raise ValueError("Unknown case ID or engine")
    if len(selected_engines) != len(set(selected_engines)):
        raise ValueError("Duplicate selected engines")
    engine_capabilities = {case["id"]: case["engines"] for case in capabilities["cases"]}
    cells, omitted = [], {}
    for case in catalog["cases"]:
        if case_filter and case["id"] not in case_filter:
            continue
        omitted[case["id"]] = {}
        for operation in case["operations"]:
            omitted[case["id"]][operation] = {}
            for engine in ENGINES:
                capability = engine_capabilities[case["id"]][engine]
                if engine == "avro2s" and not case["avro2s"]:
                    omitted[case["id"]][operation][engine] = "Outside the approved avro2s subset"
                elif operation not in capability["operations"]:
                    omitted[case["id"]][operation][engine] = capability["reason"]
                elif engine in selected_engines:
                    cells.append([case["id"], operation, engine])
    if not cells:
        raise ValueError("Selection contains no supported benchmark cells")
    rounds = {"full": 5, "pilot": 2, "smoke": 1}[profile]
    timing = {"warmupIterations": 1 if profile == "smoke" else 10,
              "measurementIterations": 1 if profile == "smoke" else 10,
              "warmupTime": "100ms" if profile == "smoke" else "1s",
              "measurementTime": "100ms" if profile == "smoke" else "1s",
              "forksPerSelection": 1, "threads": 1, "mode": "avgt", "timeUnit": "ns",
              "jvmArgs": ["-Xms1g", "-Xmx1g", "-XX:+UseG1GC"], "profiler": "gc"}
    from benchmark_suite_report import duration_seconds
    seconds = rounds * len(cells) * (timing["warmupIterations"] * duration_seconds(timing["warmupTime"])
                                    + timing["measurementIterations"] * duration_seconds(timing["measurementTime"]))
    return {"profile": profile, "diagnostic": profile != "full" or bool(cases or engines),
            "seed": seed, "rounds": rounds, "timing": timing, "cells": cells, "omitted": omitted,
            "expectedCells": len(cells), "expectedJVMForks": rounds * len(cells),
            "warmupAndMeasurementSeconds": seconds,
            "estimateExcludes": "Compilation, correctness checks, JVM startup, corpus/reader setup, reporting and any GC overruns",
            "schedule": schedule(cells, rounds, seed)}


def schedule(cells, rounds, seed):
    groups = {}
    for case_id, operation, engine in cells:
        groups.setdefault((case_id, operation), []).append(engine)
    # Freeze a random initial group and engine order; each new round rotates the
    # engine positions within each group. Keep competing engines adjacent.
    rng = random.Random(seed)
    keys = list(groups)
    rng.shuffle(keys)
    for engines in groups.values():
        rng.shuffle(engines)
    result = []
    for round_index in range(rounds):
        for case_id, operation in keys:
            engines = groups[(case_id, operation)]
            shift = round_index % len(engines)
            for engine in engines[shift:] + engines[:shift]:
                result.append({"round": round_index + 1, "caseId": case_id, "operation": operation, "engine": engine})
    return result


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def is_source(relative):
    path = Path(relative)
    return (not any(part in EXCLUDED for part in path.parts)
            and not (len(path.parts) > 1 and path.parts[0] == "benchmarks" and path.parts[1] in {"results", "reference", "archive", "archives"})
            and (path.suffix in SOURCE_SUFFIXES or path.name in {".jvmopts", ".sbtopts"}))


def source_hashes(root, output=None):
    result = {}
    for directory, children, files in os.walk(root):
        children[:] = sorted(name for name in children if name not in EXCLUDED
                             and not (Path(directory).relative_to(root) == Path("benchmarks") and name in {"results", "reference", "archive", "archives"})
                             and (output is None or (Path(directory) / name).resolve() != output.resolve()))
        for name in sorted(files):
            path = Path(directory) / name
            relative = str(path.relative_to(root))
            if is_source(relative):
                result[relative] = sha256(path)
    return result


def assert_unchanged(root, before, output=None):
    after = source_hashes(root, output)
    if before != after:
        changed = sorted(path for path in set(before) | set(after) if before.get(path) != after.get(path))
        raise RuntimeError("Sources changed; campaign rejected: " + ", ".join(changed[:10]))


def execution_environment(java):
    env = {key: value for key, value in os.environ.items() if key in ENV_KEYS or key.startswith("LC_")}
    env.update(JAVA_HOME=str(java.parent.parent), JDK_HOME=str(java.parent.parent),
               PATH=str(java.parent) + os.pathsep + os.environ.get("PATH", os.defpath), TERM="dumb")
    return env


def capture(command, root, env, check=True):
    completed = subprocess.run(command, cwd=root, env=env, text=True, capture_output=True, check=check)
    return completed.stdout + completed.stderr


def git_provenance(root, env):
    return {"gitRevision": capture(["git", "rev-parse", "HEAD"], root, env).strip(),
            "gitStatus": capture(["git", "status", "--short", "--untracked-files=all"], root, env),
            "deletedSources": [path for path in capture(["git", "diff", "--name-only", "--diff-filter=D", "HEAD"], root, env).splitlines() if is_source(path)]}


def host_observations(root, env):
    """Best-effort diagnostics, never a claim that the machine was idle."""
    result = {}
    commands = {"uptime": ["uptime"]}
    if platform.system() == "Darwin":
        commands.update(power=["pmset", "-g", "batt"], powerSettings=["pmset", "-g", "custom"],
                        memoryPressure=["memory_pressure", "-Q"])
    for name, command in commands.items():
        try:
            observation = subprocess.run(command, cwd=root, env=env, text=True, capture_output=True, timeout=10)
            result[name] = {"command": command, "exitCode": observation.returncode,
                            "output": observation.stdout + observation.stderr}
        except (OSError, subprocess.TimeoutExpired) as error:
            result[name] = {"command": command, "error": str(error)}
    return result


def parse_classpath(text):
    paths = re.findall(r"^\[info\] \* Attributed\((.+)\)\s*$", text, re.MULTILINE)
    if not paths or any(not Path(path).exists() for path in paths):
        raise ValueError("Could not extract compiled JMH fullClasspath from sbt output")
    if not any((Path(path) / "META-INF/BenchmarkList").is_file() for path in paths if Path(path).is_dir()):
        raise ValueError("Compiled JMH classpath contains no generated BenchmarkList")
    return list(dict.fromkeys(paths))


def classpath_files(classpath):
    files = []
    for entry in classpath:
        path = Path(entry)
        files.extend(sorted(child for child in path.rglob("*") if child.is_file()) if path.is_dir() else [path])
    return files


def classpath_signature(classpath):
    return {str(path): [path.stat().st_size, path.stat().st_mtime_ns] for path in classpath_files(classpath)}


def jmh_command(java, classpath, row, timing, output):
    result = output / "raw" / f"r{row['round']:02d}-{row['caseId']}-{row['operation']}-{row['engine']}.json"
    command = [str(java), "-cp", os.pathsep.join(classpath), "org.openjdk.jmh.Main",
               "^" + re.escape(BENCHMARK + row["operation"]) + "$",
               "-p", "caseId=" + row["caseId"], "-p", "engine=" + row["engine"],
               "-jvm", str(java), "-jvmArgs", " ".join(timing["jvmArgs"]),
               "-f", "1", "-wi", str(timing["warmupIterations"]), "-w", timing["warmupTime"],
               "-i", str(timing["measurementIterations"]), "-r", timing["measurementTime"],
               "-t", "1", "-bm", "avgt", "-tu", "ns", "-prof", "gc", "-foe", "true",
               "-rf", "json", "-rff", str(result)]
    return command, result


def write_json(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")
    temporary.replace(path)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True, type=Path, help="Absolute executable JDK java; used for compilation and every JMH fork")
    parser.add_argument("--profile", choices=("full", "pilot", "smoke"), default="full")
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--seed", type=int, default=20260926)
    parser.add_argument("--case", action="append", dest="cases", help="Narrow to an ID; repeatable, marks results diagnostic")
    parser.add_argument("--engine", action="append", choices=ENGINES, dest="engines", help="Narrow engines; repeatable, marks results diagnostic")
    parser.add_argument("--skip-tests", action="store_true", help="Explicitly skip correctness tests after validating these exact sources separately")
    parser.add_argument("--dry-run", action="store_true", help="Print exact membership, order, settings and timed-stage estimate; do not write or run anything")
    args = parser.parse_args(argv)
    if not args.java.is_absolute():
        parser.error("--java must be an absolute path")
    args.root, args.java = args.root.resolve(), args.java.resolve()
    if not args.java.is_file() or not os.access(args.java, os.X_OK):
        parser.error("--java must point to an executable JDK java")
    if not (args.root / "build.sbt").is_file():
        parser.error("--root must contain build.sbt")
    if args.cases and len(args.cases) != len(set(args.cases)):
        parser.error("Duplicate --case selection")
    args.output = (args.output or args.root / "benchmarks/results" /
                   (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ") + "-" + args.profile)).resolve()
    if args.output == args.root or args.output in args.root.parents:
        parser.error("Output must not be the source root or an ancestor")
    return args


def main(argv=None):
    args = parse_args(argv)
    catalog, capabilities = load_inputs(args.root)
    plan = make_plan(catalog, capabilities, args.profile, args.seed, args.cases, args.engines)
    if args.dry_run:
        print(json.dumps({"java": str(args.java), "output": str(args.output), "correctness": "explicitly skipped" if args.skip_tests else "benchmarks/test",
                          "build": "One sbt invocation: tests, Jmh/compile, show Jmh/fullClasspath; every measurement launches JMH directly", "plan": plan}, indent=2))
        return 0
    if args.output.exists():
        raise ValueError("Output directory already exists; choose a new directory")
    if not shutil.which("sbt"):
        raise ValueError("sbt must be on PATH")
    args.output.mkdir(parents=True)
    (args.output / "raw").mkdir()
    env = execution_environment(args.java)
    before = source_hashes(args.root, args.output)
    metadata = {"status": "running", "startedAt": datetime.now(timezone.utc).isoformat(),
                "sourceRoot": str(args.root), "sourceSha256": before,
                "java": str(args.java), "javaVersion": capture([str(args.java), "-version"], args.root, env),
                "javaSha256": sha256(args.java), "environment": env,
                "discardedAmbientOptionVariables": [name for name in OPTION_VARIABLES if os.environ.get(name)],
                "platform": platform.platform(), "machine": platform.machine(), "cpuCount": os.cpu_count(),
                "pythonVersion": sys.version, "catalog": catalog, "capabilities": capabilities, "plan": plan,
                "correctness": "explicitly skipped" if args.skip_tests else "pending",
                "commands": [], "completedForks": 0, **git_provenance(args.root, env),
                "hostObservationsBefore": host_observations(args.root, env)}
    metadata_path = args.output / "environment.json"
    write_json(metadata_path, metadata)
    records, keep_awake, classpath, compiled_signature = [], None, None, None
    try:
        # Always preserve the exact measured source inputs, including uncommitted
        # catalogue/manifests. Git revision alone is insufficient provenance.
        with tarfile.open(args.output / "source-snapshot.tar.gz", "w:gz") as archive:
            for relative in before:
                archive.add(args.root / relative, arcname=relative, recursive=False)
        assert_unchanged(args.root, before, args.output)
        if platform.system() == "Darwin" and shutil.which("caffeinate"):
            keep_awake = subprocess.Popen(["caffeinate", "-i", "-w", str(os.getpid())], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        build = ["sbt", "-batch", "-no-colors", "-java-home", str(args.java.parent.parent)]
        if not args.skip_tests:
            build.append("benchmarks/test")
        build.extend(["benchmarks/Jmh/compile", "show benchmarks / Jmh / fullClasspath"])
        metadata["buildCommand"] = build
        write_json(metadata_path, metadata)
        print(f"Building and {'checking correctness' if not args.skip_tests else 'explicitly skipping correctness checks'}; log: {args.output / 'build.log'}", flush=True)
        with (args.output / "build.log").open("w") as log:
            subprocess.run(build, cwd=args.root, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
        if not args.skip_tests:
            metadata["correctness"] = "passed"
        assert_unchanged(args.root, before, args.output)
        classpath = parse_classpath((args.output / "build.log").read_text())
        compiled_signature = classpath_signature(classpath)
        metadata["classpath"] = classpath
        metadata["classpathSha256"] = {str(path): sha256(path) for path in classpath_files(classpath)}
        write_json(metadata_path, metadata)
        for index, row in enumerate(plan["schedule"], 1):
            assert_unchanged(args.root, before, args.output)
            if classpath_signature(classpath) != compiled_signature or sha256(args.java) != metadata["javaSha256"]:
                raise RuntimeError("Compiled classpath or Java executable changed during campaign")
            command, result = jmh_command(args.java, classpath, row, plan["timing"], args.output)
            log_path = result.with_suffix(".log")
            metadata["commands"].append({**row, "command": command, "log": str(log_path.relative_to(args.output)), "result": str(result.relative_to(args.output))})
            write_json(metadata_path, metadata)
            print(f"[{index}/{plan['expectedJVMForks']}] round {row['round']}: {row['caseId']} {row['operation']} {row['engine']}", flush=True)
            with log_path.open("w") as log:
                subprocess.run(command, cwd=args.root, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
            record = validate_fork(json.loads(result.read_text()), (row["caseId"], row["operation"], row["engine"]),
                                   plan["timing"], args.java, log_path.read_text())
            assert_unchanged(args.root, before, args.output)
            records.append({**record, "_round": row["round"], "_sourceFile": str(result.relative_to(args.output))})
            metadata["completedForks"] = index
            write_json(args.output / "records.json", records)
            write_json(metadata_path, metadata)
        metadata["status"] = "complete"
        (args.output / "report.md").write_text(render(records, metadata))
    except BaseException as error:
        metadata.update(status="failed", error=f"{type(error).__name__}: {error}")
        raise
    finally:
        if keep_awake is not None:
            keep_awake.terminate()
            keep_awake.wait()
        after = source_hashes(args.root, args.output)
        metadata.update(finishedAt=datetime.now(timezone.utc).isoformat(), sourcesUnchanged=before == after, sourceSha256After=after)
        if classpath:
            try:
                metadata["classpathUnchanged"] = (classpath_signature(classpath) == compiled_signature
                    and {str(path): sha256(path) for path in classpath_files(classpath)} == metadata.get("classpathSha256"))
            except OSError as error:
                metadata.update(classpathUnchanged=False, classpathError=str(error))
        metadata["hostObservationsAfter"] = host_observations(args.root, env)
        if before != after or metadata.get("classpathUnchanged") is False:
            metadata.update(status="failed", error="Sources or compiled classpath changed; campaign rejected")
        if metadata["status"] != "complete":
            (args.output / "report.md").unlink(missing_ok=True)
        write_json(metadata_path, metadata)
        artifacts = sorted(path for path in args.output.rglob("*") if path.is_file() and path.name != "SHA256SUMS")
        (args.output / "SHA256SUMS").write_text("".join(sha256(path) + "  " + str(path.relative_to(args.output)) + "\n" for path in artifacts))
    if metadata["status"] != "complete":
        raise RuntimeError(metadata["error"])
    print(f"Completed {len(records)} JVM forks. Report: {args.output / 'report.md'}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
