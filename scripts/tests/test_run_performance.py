"""Fast runner contract tests: python3 -m unittest discover -s scripts/tests."""
from contextlib import redirect_stderr, redirect_stdout
import copy
import importlib.util
import io
import json
from pathlib import Path
import shlex
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "run-performance.py"
spec = importlib.util.spec_from_file_location("run_performance", SCRIPT)
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class RunnerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        (self.root / "build.sbt").write_text('// test fixture\n')
        (self.root / "project").mkdir()
        (self.root / "project/build.properties").write_text('sbt.version=1.10.7\n')
        self.java = Path(sys.executable).resolve()

    def options(self, profile="big-decimal", *extra):
        return ["--java", str(self.java), "--root", str(self.root), "--profile", profile, *extra]

    def args(self, profile="big-decimal", *extra):
        return runner.parse_args(self.options(profile, *extra))

    def assert_invalid(self, *extra):
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.args("big-decimal", *extra)

    def test_profile_coverage_and_full_union(self):
        expected_counts = {"pilot": 68, "focused": 45, "api": 14, "trade": 36, "evolution": 8,
                           "comparison": 148, "decoded-strings": 15, "strings": 136, "big-decimal": 54,
                           "stack-safety": 18, "full": 239}
        cases = {}
        for profile, count in expected_counts.items():
            with self.subTest(profile=profile):
                runs = self.args(profile).runs
                cases[profile] = set().union(*(run["expected"] for run in runs))
                self.assertEqual(count, len(cases[profile]))
                self.assertEqual(count, sum(len(run["expected"]) for run in runs))
        self.assertEqual(cases["full"], set().union(*(cases[name] for name in
                         ["comparison", "evolution", "decoded-strings", "big-decimal", "api"])))
        self.assertFalse(cases["full"] & cases["stack-safety"])

    def test_stack_safety_profile_selects_both_executions_for_every_shape_and_operation(self):
        runs = self.args("stack-safety").runs
        self.assertEqual(["StackSafety"], [run["name"] for run in runs])
        expected = {(runner.PREFIX + "StackSafetyBenchmark." + method,
                     (("execution", execution), ("shape", shape)))
                    for method in ["encode", "decode", "resolvedDecode"]
                    for execution in ["direct", "stack-safe"]
                    for shape in ["shallow", "collections", "recursive"]}
        self.assertEqual(expected, runs[0]["expected"])
        tokens = shlex.split(runs[0]["command"][-1])
        self.assertIn("execution=direct,stack-safe", tokens)
        self.assertIn("shape=shallow,collections,recursive", tokens)
        self.assertEqual("avgt", tokens[tokens.index("-bm") + 1])
        self.assertEqual("ns", tokens[tokens.index("-tu") + 1])
        self.assertEqual("gc", tokens[tokens.index("-prof") + 1])

    def test_stack_safety_can_narrow_one_operation_and_shape_without_running_other_profiles(self):
        args = self.args("stack-safety", "--filter", "[.]resolvedDecode$",
                         "--param", "shape=recursive", "--param", "execution=stack-safe")
        self.assertEqual({(runner.PREFIX + "StackSafetyBenchmark.resolvedDecode",
                           (("execution", "stack-safe"), ("shape", "recursive")))}, args.runs[0]["expected"])

    def test_stack_safety_accepts_optional_scaling_shapes_without_expanding_its_default_matrix(self):
        args = self.args("stack-safety", "--param", "shape=recursive-256,recursive-containers")
        self.assertEqual(12, len(args.runs[0]["expected"]))
        self.assertEqual({"recursive-256", "recursive-containers"},
                         {dict(parameters)["shape"] for _, parameters in args.runs[0]["expected"]})
        self.assertEqual(18, len(self.args("stack-safety").runs[0]["expected"]))

    def test_big_decimal_cases_keep_negative_scales_and_distinct_mappings(self):
        cases = self.args().runs[0]["expected"]
        for method in ["nativeScalaMappingRead", "nativeJavaMappingRead", "avroJavaConversionRead",
                       "nativeScalaMappingWrite", "nativeJavaMappingWrite", "avroJavaConversionWrite"]:
            self.assertIn((runner.PREFIX + "BigDecimalBenchmark." + method,
                           (("digits", "500"), ("scale", "-6"))), cases)

    def test_filter_and_overrides_select_exact_requested_cases(self):
        args = self.args("big-decimal", "--filter", "nativeJavaMappingRead$", "--param", "digits=6,50", "--param", "scale=-6")
        self.assertEqual({(runner.PREFIX + "BigDecimalBenchmark.nativeJavaMappingRead", (("digits", digits), ("scale", "-6")))
                          for digits in ["6", "50"]}, args.runs[0]["expected"])
        tokens = shlex.split(args.runs[0]["command"][-1])
        self.assertIn("digits=6,50", tokens)
        self.assertIn("scale=-6", tokens)

    def test_invalid_parameters_filters_and_timing_fail_before_execution(self):
        for extra in [("--param", "digits"), ("--param", "=6"), ("--param", "digits="),
                      ("--param", "digits=6,6"), ("--param", "digits=6", "--param", "digits=50"),
                      ("--param", "unknown=6"), ("--filter", "Integer"), ("--filter", "["),
                      ("--forks", "0"), ("--measurement-iterations", "0"),
                      ("--warmup-iterations", "-1"), ("--warmup-time", "0ms"),
                      ("--measurement-time", "0.5s"), ("--label", "../escape")]:
            with self.subTest(extra=extra):
                self.assert_invalid(*extra)

    def test_default_output_survives_sbt_clean_and_labels_remain_compatible(self):
        first, second = self.args(), self.args()
        self.assertEqual(self.root / "benchmarks/results", first.output.parent)
        self.assertRegex(first.output.name, r"^\d{8}T\d{6}\.\d{6}Z-big-decimal$")
        self.assertNotEqual(first.output, second.output)
        self.assertFalse(first.output.exists())
        output = self.root / "legacy-output"
        args = self.args("big-decimal", "--output", str(output), "--label", "smoke")
        self.assertEqual(str(output / "smoke-BigDecimal.json"), args.runs[0]["result"])

    def test_selected_java_controls_sbt_forks_and_recorded_environment(self):
        args = self.args()
        command = args.runs[0]["command"]
        self.assertEqual(["sbt", "-java-home", str(self.java.parent.parent)], command[:3])
        tokens = shlex.split(command[-1])
        for key, value in [("-jvm", str(self.java)), ("-t", "1"), ("-f", "2"), ("-wi", "3"),
                           ("-i", "5"), ("-w", "500ms"), ("-r", "500ms"), ("-prof", "gc"),
                           ("-bm", "avgt"), ("-tu", "ns"), ("-jvmArgs", "-Xms512m -Xmx512m")]:
            self.assertEqual(value, tokens[tokens.index(key) + 1])
        with patch.dict(runner.os.environ, {"JAVA_HOME": "/wrong", "TOKEN": "secret", "JAVA_TOOL_OPTIONS": "-Dtest=true"}):
            env = runner.execution_environment(self.java)
        self.assertEqual(str(self.java.parent.parent), env["JAVA_HOME"])
        self.assertEqual(str(self.java.parent), env["PATH"].split(runner.os.pathsep)[0])
        self.assertEqual("-Dtest=true", env["JAVA_TOOL_OPTIONS"])
        self.assertNotIn("TOKEN", env)
        self.assertEqual({"forks": 1, "warmupIterations": 2, "measurementIterations": 3,
                          "warmupTime": "200ms", "measurementTime": "200ms", "threads": 1, "heap": "512m"},
                         self.args("pilot").timing)

    def test_dry_run_never_executes_a_process_or_creates_output(self):
        stdout = io.StringIO()
        with patch.object(runner.subprocess, "run", side_effect=AssertionError("dry-run launched a process")), redirect_stdout(stdout):
            self.assertEqual(0, runner.main(self.options("full", "--dry-run")))
        plan = json.loads(stdout.getvalue())
        self.assertEqual(239, plan["expectedCases"])
        self.assertEqual("benchmarks/test", plan["correctness"]["command"][-1])
        self.assertEqual("pending", plan["correctness"]["status"])
        self.assertEqual("report.md", Path(plan["report"]).name)
        self.assertFalse(Path(plan["output"]).exists())
        stdout = io.StringIO()
        with patch.object(runner.subprocess, "run", side_effect=AssertionError), redirect_stdout(stdout):
            runner.main(self.options("big-decimal", "--dry-run", "--skip-tests", "--label", "legacy"))
        plan = json.loads(stdout.getvalue())
        self.assertEqual("skipped", plan["correctness"]["status"])
        self.assertEqual("legacy.environment.json", Path(plan["metadata"]).name)

    def sample_result(self):
        return {"benchmark": runner.PREFIX + "BigDecimalBenchmark.nativeJavaMappingRead",
                "params": {"digits": "6", "scale": "0"}, "mode": "avgt", "forks": 2, "threads": 1,
                "primaryMetric": {"score": 10.0, "scoreUnit": "ns/op"},
                "secondaryMetrics": {"gc.alloc.rate.norm": {"score": 16.0, "scoreUnit": "B/op"}}}

    def test_result_validation_rejects_incomplete_duplicate_or_unexpected_cases(self):
        record = self.sample_result()
        run = {"expected": {runner.result_key(record)}}
        runner.validate_records([record], run, {"forks": 2})
        unexpected = copy.deepcopy(record)
        unexpected["params"]["scale"] = "6"
        for records in [[], [record, record], [unexpected], {}, [{"benchmark": record["benchmark"]}]]:
            with self.subTest(records=records), self.assertRaises(ValueError):
                runner.validate_records(records, run, {"forks": 2})
        with self.assertRaises(ValueError):
            runner.validate_records([record], run, {"forks": 2}, "JMH <failure>")

    def test_result_validation_requires_finite_latency_and_allocation(self):
        baseline = self.sample_result()
        run = {"expected": {runner.result_key(baseline)}}
        for metric in ["latency", "allocation"]:
            for invalid in [float("nan"), float("inf"), -1, "NaN", None, True]:
                record = copy.deepcopy(baseline)
                target = record["primaryMetric"] if metric == "latency" else record["secondaryMetrics"]["gc.alloc.rate.norm"]
                target["score"] = invalid
                with self.subTest(metric=metric, invalid=invalid), self.assertRaises(ValueError):
                    runner.validate_records([record], run, {"forks": 2})
        record = copy.deepcopy(baseline)
        del record["secondaryMetrics"]["gc.alloc.rate.norm"]
        with self.assertRaises(ValueError):
            runner.validate_records([record], run, {"forks": 2})
        for key, invalid in [("threads", 2), ("forks", 1), ("mode", "thrpt")]:
            record = copy.deepcopy(baseline)
            record[key] = invalid
            with self.subTest(key=key), self.assertRaises(ValueError):
                runner.validate_records([record], run, {"forks": 2})
        baseline["primaryMetric"]["scoreUnit"] = "us/op"
        with self.assertRaises(ValueError):
            runner.validate_records([baseline], run, {"forks": 2})


    def test_correctness_and_result_gates_control_report_acceptance(self):
        source = self.root / "source.scala"
        source.write_text("original")
        for outcome in ["success", "correctness-failed", "incomplete", "source-changed", "report-failed"]:
            with self.subTest(outcome=outcome):
                output = self.root / outcome
                commands = []

                def execute(command, **kwargs):
                    commands.append(command[-1])
                    if command[-1] == "benchmarks/test":
                        if outcome == "correctness-failed":
                            raise runner.subprocess.CalledProcessError(1, command)
                        return
                    tokens = shlex.split(command[-1])
                    result = Path(tokens[tokens.index("-rff") + 1])
                    result.write_text(json.dumps([] if outcome == "incomplete" else [self.sample_result()]))
                    if outcome == "source-changed":
                        source.write_text("changed")

                renderer = unittest.mock.Mock(return_value="# Report\n")
                if outcome == "report-failed":
                    renderer.side_effect = ValueError("invalid report data")
                with patch.object(runner, "capture", return_value="test JVM"), \
                     patch.object(runner.platform, "platform", return_value="test platform"), \
                     patch.object(runner, "git_provenance", return_value={"gitRevision": "abc123", "gitStatus": "", "dirtySources": False, "deletedSources": []}), \
                     patch.object(runner.subprocess, "run", side_effect=execute), \
                     patch.dict(sys.modules, {"benchmark_report": types.SimpleNamespace(render=renderer)}), \
                     redirect_stdout(io.StringIO()):
                    options = self.options("big-decimal", "--output", str(output), "--filter", "nativeJavaMappingRead$",
                                           "--param", "digits=6", "--param", "scale=0")
                    if outcome == "success":
                        self.assertEqual(0, runner.main(options))
                    else:
                        with self.assertRaises((ValueError, RuntimeError, runner.subprocess.CalledProcessError)):
                            runner.main(options)
                metadata = json.loads((output / "environment.json").read_text())
                self.assertEqual("benchmarks/test", commands[0])
                self.assertEqual(1 if outcome == "correctness-failed" else 2, len(commands))
                self.assertEqual("complete" if outcome == "success" else "failed", metadata["status"])
                self.assertEqual(outcome == "success", (output / "report.md").exists())
                self.assertEqual(outcome != "source-changed", metadata["sourcesUnchanged"])
                self.assertEqual("failed" if outcome == "correctness-failed" else "passed", metadata["correctness"]["status"])
                self.assertEqual(1 if outcome in {"success", "report-failed"} else 0, renderer.call_count)
                self.assertTrue((output / "SHA256SUMS").is_file())
                source.write_text("original")

    def test_generated_and_archived_sources_do_not_change_fingerprint(self):
        for relative in ["runtime/src/main.scala", "benchmarks/src/main.scala", "benchmarks/results/run/old.py",
                         "benchmarks/archive/old.py", "benchmarks/archives/old.py", "target/generated.scala",
                         "runtime/target/generated.scala", "custom-output/copy.scala"]:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("before")
        before = runner.source_hashes(self.root, self.root / "custom-output")
        self.assertIn("runtime/src/main.scala", before)
        self.assertIn("benchmarks/src/main.scala", before)
        for relative in ["benchmarks/results/run/old.py", "benchmarks/archive/old.py", "benchmarks/archives/old.py",
                         "target/generated.scala", "runtime/target/generated.scala", "custom-output/copy.scala"]:
            (self.root / relative).write_text("after")
        self.assertEqual(before, runner.source_hashes(self.root, self.root / "custom-output"))
        (self.root / "runtime/src/main.scala").write_text("changed source")
        self.assertNotEqual(before, runner.source_hashes(self.root, self.root / "custom-output"))


if __name__ == "__main__":
    unittest.main()
