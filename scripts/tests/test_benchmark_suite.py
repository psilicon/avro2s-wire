import copy
import importlib.util
import json
import math
from pathlib import Path
import statistics
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("suite_runner", SCRIPTS / "run-benchmarks.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
import benchmark_suite_report as report


def sample_record(case_id="P03", operation="encode", engine="wire", values=None, allocation=64):
    values = values or [100] * 10
    return {"benchmark": report.BENCHMARK + operation, "params": {"caseId": case_id, "engine": engine},
            "mode": "avgt", "forks": 1, "threads": 1,
            "warmupIterations": 10, "warmupTime": "1 s", "measurementIterations": len(values), "measurementTime": "1 s",
            "jvm": "/fake/java", "jvmArgs": ["-Xms1g", "-Xmx1g", "-XX:+UseG1GC"],
            "primaryMetric": {"score": statistics.mean(values), "scoreUnit": "ns/op", "rawData": [values]},
            "secondaryMetrics": {"gc.alloc.rate.norm": {"score": allocation, "scoreUnit": "B/op", "rawData": [[allocation] * len(values)]}}}


class CatalogueTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog, cls.capabilities = runner.load_inputs(SCRIPTS.parent)

    def test_exact_approved_catalogue(self):
        cases = self.catalog["cases"]
        self.assertEqual(len(cases), 61)
        self.assertEqual(sum(len(case["operations"]) for case in cases), 119)
        selected = {case["id"] for case in cases if case["avro2s"]}
        self.assertEqual(selected, {"P01", "P03", "P06", "P09", "P10", "T01", "T02", "T11", "C01", "C02", "C03", "C04", "U01", "U02", "U03", "U04", "U05"})
        self.assertEqual(sum(len(case["operations"]) for case in cases if case["avro2s"]), 34)

    def test_full_membership_uses_only_actual_custom_capabilities(self):
        plan = runner.make_plan(self.catalog, self.capabilities, "full")
        rows = {case["id"]: case for case in self.catalog["cases"]}
        supported = {case["id"] for case in self.capabilities["cases"] if case["engines"]["java-custom"]["supported"]}
        expected_custom = {(case_id, operation, "java-custom") for case_id in supported for operation in rows[case_id]["operations"]}
        self.assertEqual({tuple(cell) for cell in plan["cells"] if cell[2] == "java-custom"}, expected_custom)
        self.assertEqual(plan["expectedCells"], 3 * 119 - 4 + 34 + len(expected_custom))
        self.assertEqual(plan["expectedJVMForks"], 5 * plan["expectedCells"])
        self.assertEqual(plan["warmupAndMeasurementSeconds"], 100 * plan["expectedCells"])
        self.assertFalse(plan["diagnostic"])

    def test_invalid_java_nanos_writes_are_excluded_but_reads_remain(self):
        plan = runner.make_plan(self.catalog, self.capabilities, "full")
        cells = {tuple(cell) for cell in plan["cells"]}
        for case_id in ("L06", "L09"):
            for engine in ("java-specific", "java-generic"):
                self.assertNotIn((case_id, "encode", engine), cells)
                self.assertIn((case_id, "decode", engine), cells)
                self.assertIn("999 ms", plan["omitted"][case_id]["encode"][engine])
                self.assertNotIn(engine, plan["omitted"][case_id]["decode"])
            self.assertIn((case_id, "encode", "wire"), cells)

    def test_short_pilot_is_exactly_24_forks_and_480_seconds(self):
        plan = runner.make_plan(self.catalog, self.capabilities, "pilot")
        expected = {(case_id, operation, engine) for case_id in ("P03", "T11", "C02")
                    for operation in ("encode", "decode") for engine in ("wire", "java-specific")}
        self.assertEqual({tuple(cell) for cell in plan["cells"]}, expected)
        self.assertEqual(plan["expectedJVMForks"], 24)
        self.assertEqual(plan["warmupAndMeasurementSeconds"], 480)
        self.assertTrue(plan["diagnostic"])
        self.assertEqual(plan["timing"]["warmupIterations"], 10)

    def test_smoke_and_narrowed_full_are_diagnostic(self):
        self.assertTrue(runner.make_plan(self.catalog, self.capabilities, "full", cases=["P03"])["diagnostic"])
        smoke = runner.make_plan(self.catalog, self.capabilities, "smoke")
        self.assertEqual(smoke["expectedJVMForks"], 4)
        self.assertAlmostEqual(smoke["warmupAndMeasurementSeconds"], .8)
        with self.assertRaises(ValueError):
            runner.make_plan(self.catalog, self.capabilities, "full", cases=["NOPE"])

    def test_rounds_are_complete_and_engine_positions_rotate(self):
        cells = [["P03", "encode", engine] for engine in report.ENGINES]
        schedule = runner.schedule(cells, 5, 123)
        self.assertEqual(schedule, runner.schedule(cells, 5, 123))
        by_round = [[row["engine"] for row in schedule if row["round"] == index] for index in range(1, 6)]
        for index, engines in enumerate(by_round):
            self.assertEqual(engines, by_round[0][index:] + by_round[0][:index])
        self.assertEqual({engines[0] for engines in by_round}, set(report.ENGINES))

    def test_capability_mismatch_and_changed_generated_source_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / runner.CATALOG).parent.mkdir(parents=True)
            (root / runner.CAPABILITIES).parent.mkdir(parents=True)
            (root / runner.CATALOG).write_text(json.dumps(self.catalog))
            caps = copy.deepcopy(self.capabilities)
            caps["sources"] = []
            caps["cases"].pop()
            (root / runner.CAPABILITIES).write_text(json.dumps(caps))
            with self.assertRaisesRegex(ValueError, "exactly match"):
                runner.load_inputs(root)
            caps["cases"] = self.capabilities["cases"]
            caps["sources"] = [{"path": "missing.java", "sha256": "0" * 64}]
            (root / runner.CAPABILITIES).write_text(json.dumps(caps))
            with self.assertRaisesRegex(ValueError, "provenance mismatch"):
                runner.load_inputs(root)


class ValidationTests(unittest.TestCase):
    timing = {"warmupIterations": 10, "measurementIterations": 10, "warmupTime": "1s", "measurementTime": "1s",
              "jvmArgs": ["-Xms1g", "-Xmx1g", "-XX:+UseG1GC"]}

    def test_valid_retained_iteration_data(self):
        record = sample_record()
        self.assertEqual(report.validate_fork([record], ("P03", "encode", "wire"), self.timing, "/fake/java"), record)

    def test_missing_allocation_nonfinite_units_and_iteration_loss_fail(self):
        changes = [lambda r: r["secondaryMetrics"].clear(),
                   lambda r: r["primaryMetric"].update(score=math.nan),
                   lambda r: r["primaryMetric"].update(scoreUnit="ms/op"),
                   lambda r: r["primaryMetric"]["rawData"][0].pop(),
                   lambda r: r["primaryMetric"].update(score=101),
                   lambda r: r.update(warmupIterations=1),
                   lambda r: r.update(jvmArgs=["-Xmx512m"])]
        for change in changes:
            with self.subTest(change=change):
                record = sample_record()
                change(record)
                with self.assertRaises(ValueError):
                    report.validate_fork([record], ("P03", "encode", "wire"), self.timing, "/fake/java")

    def test_missing_duplicate_unexpected_results_fail(self):
        for records in ([], [sample_record(), sample_record()], [sample_record(case_id="P04")]):
            with self.assertRaises(ValueError):
                report.validate_fork(records, ("P03", "encode", "wire"), self.timing)

    def test_command_uses_one_direct_jmh_fork_and_exact_selector(self):
        command, result = runner.jmh_command(Path("/jdk/bin/java"), ["/classes", "/jmh.jar"],
                                            {"round": 2, "caseId": "P03", "operation": "encode", "engine": "wire"},
                                            self.timing, Path("/results"))
        self.assertNotIn("sbt", command)
        self.assertIn("org.openjdk.jmh.Main", command)
        self.assertEqual(command[command.index("-f") + 1], "1")
        self.assertIn("caseId=P03", command)
        self.assertIn("engine=wire", command)
        self.assertEqual(result.name, "r02-P03-encode-wire.json")


class StatisticsTests(unittest.TestCase):
    def measurements(self):
        result = []
        for index, time in enumerate((100, 110, 90, 105, 95), 1):
            for engine, factor in (("wire", 1), ("java-specific", 2)):
                record = sample_record(engine=engine, values=[time * factor] * 10)
                result.append({**record, "_round": index, "_sourceFile": f"raw/{engine}-{index}.json"})
        return result

    def test_uncertainty_uses_jvm_means_not_fifty_iterations(self):
        values = report.aggregate(self.measurements(), [["P03", "encode", "wire"], ["P03", "encode", "java-specific"]], 5)
        average, lower, upper = values[("P03", "encode", "wire")]["time"]
        expected_half = 2.7764451052 * statistics.stdev((100, 110, 90, 105, 95)) / math.sqrt(5)
        self.assertEqual(average, 100)
        self.assertAlmostEqual(upper - average, expected_half)
        self.assertAlmostEqual(average - lower, expected_half)

    def test_paired_ratios_preserve_round_pairing(self):
        result = report.paired_ratio([200, 220, 180, 210, 190], [100, 110, 90, 105, 95])
        for value in result:
            self.assertAlmostEqual(value, 2)

    def test_duplicates_and_missing_rounds_cannot_be_averaged_away(self):
        cells = [["P03", "encode", "wire"], ["P03", "encode", "java-specific"]]
        records = self.measurements()
        for broken in (records[:-1], records + [records[0]]):
            with self.assertRaises(ValueError):
                report.aggregate(broken, cells, 5)

    def test_report_retains_each_round_and_marks_diagnostic(self):
        cells = [["P03", "encode", "wire"], ["P03", "encode", "java-specific"]]
        metadata = {"plan": {"cells": cells, "rounds": 5, "diagnostic": True, "profile": "full", "seed": 123,
                             "omitted": {"P03": {"encode": {"java-custom": "No generated custom coder"}}}},
                    "catalog": {"cases": [{"id": "P03", "name": "int-3-byte"}]}, "gitRevision": "test-revision"}
        rendered = report.render(self.measurements(), metadata)
        self.assertIn("Diagnostic run", rendered)
        self.assertIn("N/A: No generated custom coder", rendered)
        self.assertIn("pointwise 95%", rendered)
        self.assertIn("2.000 [2.000, 2.000]", rendered)
        for index in range(1, 6):
            self.assertIn(f"raw/wire-{index}.json", rendered)
        self.assertNotIn("winner", rendered)


class ProvenanceTests(unittest.TestCase):
    def test_catalogue_manifest_and_new_sources_are_guarded_results_are_ignored(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / runner.CATALOG).parent.mkdir(parents=True)
            catalog = root / runner.CATALOG
            catalog.write_text('{}')
            (root / "target").mkdir()
            (root / "target/generated.scala").write_text('old')
            (root / "benchmarks/results/example").mkdir(parents=True)
            (root / "benchmarks/results/example/data.json").write_text('{}')
            before = runner.source_hashes(root)
            self.assertEqual(set(before), {str(runner.CATALOG)})
            (root / "target/generated.scala").write_text('new')
            runner.assert_unchanged(root, before)
            catalog.write_text('{"changed": true}')
            with self.assertRaisesRegex(RuntimeError, "Sources changed"):
                runner.assert_unchanged(root, before)

    def test_classpath_changes_are_detected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "META-INF").mkdir()
            (root / "META-INF/BenchmarkList").write_text('benchmark')
            (root / "Code.class").write_bytes(b'original')
            classpath = runner.parse_classpath(f"[info] * Attributed({root})\n")
            before = runner.classpath_signature(classpath)
            (root / "Code.class").write_bytes(b'changed bytecode')
            self.assertNotEqual(before, runner.classpath_signature(classpath))


if __name__ == "__main__":
    unittest.main()
