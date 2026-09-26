"""Stdlib tests for report grouping, provenance and misleading-input guards."""

from contextlib import redirect_stderr, redirect_stdout
from copy import deepcopy
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from benchmark_report import main, render


def measurement(method="nativeRead", benchmark="ComparisonBenchmark", params=None, score=100.0):
    return {
        "benchmark": f"avro2s.wire.benchmarks.{benchmark}.{method}",
        "mode": "avgt",
        "params": {} if params is None else params,
        "primaryMetric": {"score": score, "scoreError": 2.5, "scoreUnit": "ns/op"},
        "secondaryMetrics": {"gc.alloc.rate.norm": {"score": 24.0, "scoreUnit": "B/op"}},
        "jmhVersion": "1.37", "jdkVersion": "21", "jvm": "/example/jdk/bin/java",
        "vmName": "Example VM", "vmVersion": "21", "jvmArgs": ["-Xmx512m"],
        "threads": 1, "forks": 2, "warmupIterations": 3, "warmupTime": "500 ms",
        "measurementIterations": 5, "measurementTime": "500 ms",
    }


class ReportTests(unittest.TestCase):
    def test_reports_explicit_engines_and_metrics_without_fastest_selection(self):
        rows = [measurement(engine + operation, score=index * 10.0)
                for index, engine in enumerate(("native", "javaPrimitives", "avro2s", "javaSpecific", "javaCustom", "javaGeneric"), 1)
                for operation in ("Read", "Write")]
        report = render(rows)
        self.assertIn("**12 result rows**", report)
        self.assertIn("Wire Java primitive backend | `javaPrimitivesRead` | 20.00 ± 2.50 | 24.00", report)
        self.assertIn("Official Java specific (default model) | `javaSpecificRead`", report)
        self.assertIn("Official Java custom coders (default model) | `javaCustomRead`", report)
        self.assertIn("Official Java generic (default model) | `javaGenericRead`", report)
        self.assertNotIn("fastest", report.lower())
        self.assertEqual(report.count("#### Read"), 1)
        self.assertEqual(report.count("#### Write"), 1)

    def test_keeps_class_parameters_and_operations_separate(self):
        rows = [measurement(params={"profile": "one", "size": "1"}),
                measurement(params={"profile": "two", "size": "1"}),
                measurement("nativeWrite", params={"profile": "one", "size": "1"})]
        alternate_package = deepcopy(rows[0])
        alternate_package["benchmark"] = "another.package.ComparisonBenchmark.nativeRead"
        rows.append(alternate_package)
        report = render(rows)
        self.assertIn("**2 benchmark classes**", report)
        self.assertIn("**3 workload/parameter groups**", report)
        self.assertIn("another.package.ComparisonBenchmark", report)
        self.assertEqual(report.count("#### Read"), 3)
        self.assertEqual(report.count("#### Write"), 1)

    def test_api_encode_and_decode_are_distinct_from_read_and_write(self):
        report = render([measurement("native" + operation, "IntegerBenchmark")
                         for operation in ("Read", "Write", "Encode", "Decode")])
        self.assertIn("#### API Encode (includes owned byte-array result)", report)
        self.assertIn("#### API Decode (includes end-of-input check)", report)
        read = report.split("#### Read", 1)[1].split("#### Write", 1)[0]
        self.assertIn("`nativeRead`", read)
        self.assertNotIn("`nativeDecode`", read)
        self.assertNotIn("`nativeEncode`", read)

    def test_big_decimal_java_mapping_is_native_not_java_primitive_backend(self):
        report = render([measurement(engine + "Read", "BigDecimalBenchmark") for engine in
                         ("nativeScalaMapping", "nativeJavaMapping", "avroJavaConversion")])
        self.assertIn("Wire native (scala.math.BigDecimal mapping) | `nativeScalaMappingRead`", report)
        self.assertIn("Wire native (java.math.BigDecimal mapping) | `nativeJavaMappingRead`", report)
        self.assertIn("Official Java BigDecimalConversion | `avroJavaConversionRead`", report)
        self.assertNotIn("| Wire Java primitive backend | `nativeJavaMappingRead`", report)
        self.assertIn("it is not `javaPrimitives`", report)

    def test_evolution_work_is_not_combined_with_resolution(self):
        report = render([measurement(method, "EvolutionBenchmark") for method in
                         ("nativeResolved", "javaResolved", "nativeSameSchema", "compileResolution")])
        warm = report.split("#### Warm schema resolution", 1)[1].split("#### Same-schema", 1)[0]
        self.assertIn("`nativeResolved`", warm)
        self.assertIn("`javaResolved`", warm)
        self.assertNotIn("`nativeSameSchema`", warm)
        self.assertNotIn("`compileResolution`", warm)
        self.assertIn("#### Cold resolution-plan construction (different work)", report)
        self.assertIn("#### Same-schema read baseline (different work)", report)

    def test_stack_safety_reports_explicit_execution_parameters_and_distinct_operations(self):
        rows = [measurement(method, "StackSafetyBenchmark",
                            {"execution": execution, "shape": "recursive"})
                for execution in ("direct", "stack-safe")
                for method in ("encode", "decode", "resolvedDecode")]
        report = render(rows)
        self.assertIn("**6 result rows**", report)
        self.assertIn("**2 workload/parameter groups**", report)
        self.assertIn("direct", report)
        self.assertIn("stack-safe", report)
        self.assertEqual(report.count("#### API Encode (includes owned byte-array result)"), 2)
        self.assertEqual(report.count("#### API Decode (includes end-of-input check)"), 2)
        self.assertEqual(report.count("#### Warm schema resolution"), 2)
        self.assertIn("Wire native | `resolvedDecode`", report)
        self.assertNotIn("Other method", report)

    def test_string_results_are_explicit_and_missing_engines_are_na(self):
        report = render([measurement("javaSpecificStringRead", "DecodedStringBenchmark"),
                         measurement("javaGenericStringRead", "DecodedStringBenchmark")])
        self.assertIn("Official Java specific (String results) | `javaSpecificStringRead`", report)
        self.assertIn("Official Java generic (String results) | `javaGenericStringRead`", report)
        self.assertIn("Official Java specific (default model) | N/A | N/A | N/A | N/A", report)
        self.assertIn("| Wire native | N/A | N/A | N/A | N/A |", report)

    def test_unknown_methods_are_preserved(self):
        report = render([measurement("experimentalWrite", "FutureBenchmark"),
                         measurement("prepare", "FutureBenchmark")])
        self.assertIn("| experimental | `experimentalWrite`", report)
        self.assertIn("#### Other method: prepare", report)
        self.assertIn("| prepare | `prepare`", report)

    def test_missing_allocation_and_smoke_confidence_are_na_not_zero(self):
        row = measurement(score=0)
        row["primaryMetric"]["scoreError"] = "NaN"
        row.pop("secondaryMetrics")
        report = render([row])
        self.assertIn("| 0.00 ± N/A | N/A |", report)
        row["primaryMetric"]["scoreError"] = float("nan")
        self.assertIn("| 0.00 ± N/A | N/A |", render([row]))

    def test_rejects_nonfinite_or_non_numeric_measured_scores(self):
        for score in (float("nan"), float("inf"), -1, "NaN", "100", None, True):
            with self.subTest(score=score), self.assertRaisesRegex(ValueError, "finite"):
                render([measurement(score=score)])
        for score in (float("nan"), float("inf"), "NaN"):
            row = measurement()
            row["secondaryMetrics"]["gc.alloc.rate.norm"]["score"] = score
            with self.subTest(allocation=score), self.assertRaisesRegex(ValueError, "gc.alloc.rate.norm"):
                render([row])

    def test_rejects_wrong_units_and_modes(self):
        row = measurement()
        for unit in ("us/op", "ops/s", None):
            row["primaryMetric"]["scoreUnit"] = unit
            with self.subTest(unit=unit), self.assertRaisesRegex(ValueError, "ns/op"):
                render([row])
        row = measurement()
        row["mode"] = "thrpt"
        with self.assertRaisesRegex(ValueError, "avgt"):
            render([row])
        row = measurement()
        row["secondaryMetrics"]["gc.alloc.rate.norm"]["scoreUnit"] = "MB/sec"
        with self.assertRaisesRegex(ValueError, "B/op"):
            render([row])

    def test_rejects_duplicate_identity_even_with_reordered_params_or_different_settings(self):
        row = measurement(params={"size": "1", "profile": "one"})
        duplicate = deepcopy(row)
        duplicate["params"] = {"profile": "one", "size": "1"}
        duplicate["jdkVersion"] = "25"
        with self.assertRaisesRegex(ValueError, "Duplicate measurement"):
            render([row, duplicate])

    def test_validates_shape_and_metadata(self):
        for records in ({"gitRevision": "example"}, [], [None], [{"benchmark": "nativeRead"}]):
            with self.subTest(records=records), self.assertRaises(ValueError):
                render(records)
        with self.assertRaisesRegex(ValueError, "metadata"):
            render([measurement()], "environment.json")

    def test_exposes_multiple_campaigns_and_actual_jmh_settings(self):
        first = measurement("nativeRead")
        second = measurement("javaPrimitivesRead")
        second["jdkVersion"] = "25"
        second["forks"] = 1
        report = render([first, second], [
            {"gitRevision": "commit-one", "gitStatus": " M codec.scala", "profile": "comparison",
             "timing": {"forks": 2}, "sourceSha256": {"codec.scala": "hash"},
             "sourceSha256After": {"codec.scala": "hash-after"},
             "runs": [{"name": "Comparison", "result": "run.json", "expectedCases": 2, "status": "complete"}]},
            {"gitRevision": "commit-two", "gitStatus": "", "javaVersion": "Other VM", "sourcesUnchanged": True},
        ])
        self.assertIn("commit-one", report)
        self.assertIn("commit-two", report)
        self.assertIn("1 source/build hashes recorded", report)
        self.assertNotIn('"codec.scala":"hash"', report)
        self.assertNotIn('"codec.scala":"hash-after"', report)
        self.assertIn("Clean worktree (recorded by runner)", report)
        self.assertIn("| Comparison | run.json | 2 | complete |", report)
        self.assertIn("jdkVersion=25", report)
        self.assertIn("forks=1", report)
        self.assertIn("forks=2", report)
        self.assertIn("Different setups or campaigns should not", report)

    def test_same_settings_do_not_hide_per_row_campaign_source(self):
        native = {**measurement("nativeRead"), "_sourceFile": "campaign-a.json"}
        other = {**measurement("javaSpecificRead"), "_sourceFile": "campaign-b.json"}
        report = render([native, other], [{"commit": "A"}, {"commit": "B"}])
        self.assertIn("| 1 | campaign-a.json |", report)
        self.assertIn("| 2 | campaign-b.json |", report)
        native_line = next(line for line in report.splitlines() if "| `nativeRead` |" in line)
        other_line = next(line for line in report.splitlines() if "| `javaSpecificRead` |" in line)
        self.assertTrue(native_line.endswith("| 1 | 1 |"))
        self.assertTrue(other_line.endswith("| 1 | 2 |"))
        self.assertIn("Shared JMH settings do not imply", report)

    def test_escapes_table_and_heading_source_text(self):
        report = render([measurement(params={"profile": "a|b\n<script>`"})],
                        {"commit": "abc|def", "notes": "line one\nline two"})
        self.assertIn("a&#124;b<br>&lt;script&gt;&#96;", report)
        self.assertIn("abc&#124;def", report)
        self.assertNotIn("<script>", report)


class CommandLineTests(unittest.TestCase):
    def run_cli(self, args):
        output, errors = io.StringIO(), io.StringIO()
        with redirect_stdout(output), redirect_stderr(errors):
            try:
                main(args)
            except SystemExit as exc:
                return exc.code, output.getvalue(), errors.getvalue()
        return 0, output.getvalue(), errors.getvalue()

    def test_combines_only_explicit_inputs_with_repeatable_metadata_and_output(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            inputs = [root / "a.json", root / "b.json"]
            for path, method in zip(inputs, ("nativeRead", "javaSpecificRead")):
                path.write_text(json.dumps([measurement(method)]))
            metadata = [root / "first.environment.json", root / "second.environment.json"]
            for path, commit in zip(metadata, ("first-commit", "second-commit")):
                path.write_text(json.dumps({"gitRevision": commit}))
            # A directory scan would find and incorrectly ingest this unrelated data.
            (root / "unrelated.json").write_text("not JSON")
            output = root / "nested" / "report.md"
            code, stdout, error = self.run_cli([str(path) for path in inputs] + [
                "--metadata", str(metadata[0]), "--metadata", str(metadata[1]), "-o", str(output),
            ])
            self.assertEqual((code, stdout, error), (0, "", ""))
            report = output.read_text()
            self.assertIn("**2 result rows**", report)
            self.assertIn("first-commit", report)
            self.assertIn("second-commit", report)
            self.assertIn(str(inputs[0]), report)
            self.assertIn(str(metadata[1]), report)
            self.assertNotIn("unrelated.json", report)
            native_line = next(line for line in report.splitlines() if "| `nativeRead` |" in line)
            other_line = next(line for line in report.splitlines() if "| `javaSpecificRead` |" in line)
            self.assertTrue(native_line.endswith("| 1 | 1 |"))
            self.assertTrue(other_line.endswith("| 1 | 2 |"))
            for path in inputs:
                self.assertNotIn("_sourceFile", path.read_text())

    def test_environment_json_cannot_be_mistaken_for_results(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "environment.json"
            source.write_text('{"gitRevision":"example"}')
            code, stdout, error = self.run_cli([str(source)])
            self.assertEqual(code, 2)
            self.assertEqual(stdout, "")
            self.assertIn("pass environment JSON with --metadata", error)

    def test_duplicate_inputs_fail_before_writing_report(self):
        with tempfile.TemporaryDirectory() as temp:
            source, output = Path(temp) / "result.json", Path(temp) / "report.md"
            source.write_text(json.dumps([measurement()]))
            code, _, error = self.run_cli([str(source), str(source), "-o", str(output)])
            self.assertEqual(code, 2)
            self.assertIn("Duplicate measurement", error)
            self.assertFalse(output.exists())

    def test_input_and_metadata_files_cannot_be_overwritten(self):
        with tempfile.TemporaryDirectory() as temp:
            source, metadata = Path(temp) / "result.json", Path(temp) / "environment.json"
            source.write_text(json.dumps([measurement()]))
            metadata.write_text('{"gitRevision":"example"}')
            for output in (source, metadata):
                previous = output.read_text()
                code, _, error = self.run_cli([str(source), "--metadata", str(metadata), "-o", str(output)])
                self.assertEqual(code, 2)
                self.assertIn("Output path must differ", error)
                self.assertEqual(output.read_text(), previous)


if __name__ == "__main__":
    unittest.main()
