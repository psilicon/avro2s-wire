import copy
import importlib.util
import hashlib
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

    def test_reuse_check_is_exactly_the_ten_approved_operations_and_720_seconds(self):
        plan = runner.make_plan(self.catalog, self.capabilities, "reuse-check")
        approved = {("P03", "encode"), ("R01", "encode"), ("L12", "encode"),
                    ("L16", "encode"), ("T01", "encode"), ("P02", "decode"),
                    ("P10", "decode"), ("R02", "decode"), ("L12", "decode"), ("L08", "decode")}
        expected = {(case_id, operation, engine) for case_id, operation in approved
                    for engine in ("wire", "java-specific", "java-generic", "java-custom")
                    if engine != "java-custom" or not case_id.startswith("L")}
        self.assertEqual({tuple(cell) for cell in plan["cells"]}, expected)
        self.assertEqual(plan["expectedCells"], 36)
        self.assertEqual(plan["expectedJVMForks"], 72)
        self.assertEqual(plan["warmupAndMeasurementSeconds"], 720)
        self.assertEqual(plan["usage"], "reuse")
        self.assertEqual(plan["apiContract"], report.api_contract("reuse", "reject"))
        self.assertTrue(plan["diagnostic"])
        self.assertEqual(plan["timing"]["warmupIterations"], 5)
        self.assertEqual(plan["timing"]["measurementIterations"], 5)
        for round_number in (1, 2):
            self.assertEqual({(row["caseId"], row["operation"], row["engine"])
                              for row in plan["schedule"] if row["round"] == round_number}, expected)

    def test_reuse_check_cannot_silently_change_approved_membership(self):
        for kwargs in ({"cases": ["P03"]}, {"engines": ["wire"]}, {"usage": "fresh"},
                       {"selections": ["P03:encode"]}):
            with self.subTest(kwargs=kwargs), self.assertRaisesRegex(ValueError, "fixes the approved"):
                runner.make_plan(self.catalog, self.capabilities, "reuse-check", **kwargs)
        changed = copy.deepcopy(self.capabilities)
        row = next(case for case in changed["cases"] if case["id"] == "P03")
        row["engines"]["java-custom"]["operations"] = ["decode"]
        row["engines"]["java-custom"]["reason"] = "Changed capabilities"
        with self.assertRaisesRegex(ValueError, "membership changed"):
            runner.make_plan(self.catalog, changed, "reuse-check")

    def test_quick_exact_operations_have_24_cells_48_forks_and_480_seconds(self):
        selections = ["T11:encode", "T11:decode", "B03:encode", "C02:decode", "C04:decode", "P06:decode"]
        plan = runner.make_plan(self.catalog, self.capabilities, "quick", usage="reuse", selections=selections)
        expected = {(case_id, operation, engine) for case_id, operation in (value.split(":") for value in selections)
                    for engine in ("wire", "java-specific", "java-generic", "java-custom")}
        self.assertEqual({tuple(cell) for cell in plan["cells"]}, expected)
        self.assertEqual(plan["selectedOperations"], [value.split(":") for value in selections])
        self.assertEqual(plan["expectedCells"], 24)
        self.assertEqual(plan["expectedJVMForks"], 48)
        self.assertEqual(plan["warmupAndMeasurementSeconds"], 480)
        self.assertEqual(plan["usage"], "reuse")
        self.assertTrue(plan["diagnostic"])
        self.assertEqual(plan["timing"]["warmupIterations"], 5)
        self.assertEqual(plan["timing"]["measurementIterations"], 5)
        for round_number in (1, 2):
            self.assertEqual({(row["caseId"], row["operation"], row["engine"])
                              for row in plan["schedule"] if row["round"] == round_number}, expected)

    def test_exact_operations_keep_profile_timing_and_default_fresh_usage(self):
        for profile, rounds, iterations in (("full", 5, 10), ("quick", 2, 5), ("smoke", 1, 1)):
            plan = runner.make_plan(self.catalog, self.capabilities, profile, selections=["T11:encode"], engines=["wire"])
            self.assertEqual(plan["cells"], [["T11", "encode", "wire"]])
            self.assertEqual(plan["usage"], "fresh")
            self.assertTrue(plan["diagnostic"])
            self.assertEqual(plan["rounds"], rounds)
            self.assertEqual(plan["timing"]["warmupIterations"], iterations)
            self.assertEqual(plan["timing"]["measurementIterations"], iterations)

    def test_exact_operation_selectors_reject_invalid_or_unsupported_requests(self):
        invalid = [(["P03:encode", "P03:encode"], "Duplicate"),
                   (["UNKNOWN:encode"], "Invalid"), (["P03:write"], "Invalid"),
                   (["P03"], "Invalid"), (["P03:encode:extra"], "Invalid"),
                   (["E01:encode"], "Unsupported catalogue operation")]
        for selections, message in invalid:
            with self.subTest(selections=selections), self.assertRaisesRegex(ValueError, message):
                runner.make_plan(self.catalog, self.capabilities, "quick", selections=selections)
        with self.assertRaisesRegex(ValueError, "mutually exclusive"):
            runner.make_plan(self.catalog, self.capabilities, "quick", cases=["P03"], selections=["P03:encode"])
        # One supported pair must not hide an explicitly selected pair excluded
        # by the chosen engines' declared capabilities.
        with self.assertRaisesRegex(ValueError, "no supported cells.*L06:encode"):
            runner.make_plan(self.catalog, self.capabilities, "quick", engines=["java-specific"],
                             selections=["P03:encode", "L06:encode"])

    def test_usage_defaults_preserve_full_protocol_and_reuse_excludes_avro2s(self):
        full = runner.make_plan(self.catalog, self.capabilities, "full")
        self.assertEqual(full["usage"], "fresh")
        self.assertEqual(full["apiContract"], report.api_contract("fresh", "reject"))
        reused = runner.make_plan(self.catalog, self.capabilities, "full", usage="reuse")
        self.assertTrue(reused["diagnostic"])
        self.assertNotIn("avro2s", {cell[2] for cell in reused["cells"]})
        with self.assertRaisesRegex(ValueError, "avro2s is outside"):
            runner.make_plan(self.catalog, self.capabilities, "smoke", usage="reuse", engines=["avro2s"])

    def test_string_policy_is_explicit_in_cli_plan_and_contract(self):
        args = runner.parse_args(["--java", sys.executable])
        self.assertEqual(args.wire_string_policy, "reject")
        args = runner.parse_args(["--java", sys.executable, "--wire-string-policy", "replace"])
        self.assertEqual(args.wire_string_policy, "replace")
        for usage in ("fresh", "reuse"):
            for policy in ("reject", "replace"):
                plan = runner.make_plan(self.catalog, self.capabilities, "full", usage=usage, wire_string_policy=policy)
                self.assertEqual(plan["wireStringPolicy"], policy)
                self.assertEqual(plan["apiContract"], report.api_contract(usage, policy))
                if policy == "replace":
                    self.assertTrue(plan["diagnostic"])
        with self.assertRaisesRegex(ValueError, "string policy"):
            runner.make_plan(self.catalog, self.capabilities, "quick", wire_string_policy="unknown")

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

    def test_usage_parameter_must_match_frozen_protocol(self):
        for usage in ("fresh", "reuse"):
            record = sample_record()
            record["params"]["usage"] = usage
            self.assertEqual(report.validate_fork([record], ("P03", "encode", "wire"), self.timing,
                                                 usage=usage), record)
            other = "fresh" if usage == "reuse" else "reuse"
            with self.assertRaisesRegex(ValueError, "usage mode"):
                report.validate_fork([record], ("P03", "encode", "wire"), self.timing, usage=other)
            with self.assertRaisesRegex(ValueError, "parameters"):
                report.validate_fork([record], ("P03", "encode", "wire"), self.timing)
        with self.assertRaisesRegex(ValueError, "parameters"):
            report.validate_fork([sample_record()], ("P03", "encode", "wire"), self.timing, usage="reuse")

    def test_string_policy_parameter_must_match_frozen_protocol(self):
        for usage in ("fresh", "reuse"):
            for policy in ("reject", "replace"):
                record = sample_record()
                record["params"].update(usage=usage, wireStringPolicy=policy)
                self.assertEqual(report.validate_fork([record], ("P03", "encode", "wire"), self.timing,
                                                     usage=usage, wire_string_policy=policy), record)
                other = "reject" if policy == "replace" else "replace"
                with self.assertRaisesRegex(ValueError, "Wire string policy"):
                    report.validate_fork([record], ("P03", "encode", "wire"), self.timing,
                                         usage=usage, wire_string_policy=other)
                # New records cannot masquerade as pre-policy records, or vice versa.
                with self.assertRaisesRegex(ValueError, "parameters"):
                    report.validate_fork([record], ("P03", "encode", "wire"), self.timing, usage=usage)
                del record["params"]["wireStringPolicy"]
                with self.assertRaisesRegex(ValueError, "parameters"):
                    report.validate_fork([record], ("P03", "encode", "wire"), self.timing,
                                         usage=usage, wire_string_policy=policy)
                self.assertEqual(report.validate_fork([record], ("P03", "encode", "wire"), self.timing, usage=usage), record)

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
        self.assertIn("usage=fresh", command)
        self.assertIn("wireStringPolicy=reject", command)
        self.assertEqual(result.name, "r02-P03-encode-wire.json")

    def test_reuse_command_selects_reuse_without_adding_extra_forks(self):
        command, _ = runner.jmh_command(Path("/jdk/bin/java"), ["/classes", "/jmh.jar"],
                                        {"round": 2, "caseId": "P03", "operation": "encode", "engine": "wire"},
                                        self.timing, Path("/results"), usage="reuse")
        self.assertIn("usage=reuse", command)
        self.assertNotIn("usage=fresh", command)
        self.assertEqual(command[command.index("-f") + 1], "1")


    def test_command_freezes_replacement_for_both_wire_and_java_cells(self):
        for engine in ("wire", "java-specific"):
            command, _ = runner.jmh_command(Path("/jdk/bin/java"), ["/classes", "/jmh.jar"],
                                            {"round": 2, "caseId": "T11", "operation": "encode", "engine": engine},
                                            self.timing, Path("/results"), usage="reuse", wire_string_policy="replace")
            self.assertIn("wireStringPolicy=replace", command)
            self.assertNotIn("wireStringPolicy=reject", command)
            self.assertEqual(command[command.index("-f") + 1], "1")


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

    def test_reuse_report_states_natural_outputs_and_validation_difference(self):
        cells = [["P03", "encode", "wire"], ["P03", "encode", "java-specific"]]
        metadata = {"plan": {"cells": cells, "rounds": 2, "diagnostic": True, "profile": "reuse-check", "seed": 123,
                             "usage": "reuse", "apiContract": report.api_contract("reuse"), "omitted": {}},
                    "catalog": {"cases": [{"id": "P03", "name": "int-3-byte"}]}, "gitRevision": "test-revision"}
        records = [record for record in self.measurements() if record["_round"] <= 2]
        for record in records:
            record["params"]["usage"] = "reuse"
        rendered = report.render(records, metadata)
        for description in ("RawMessageEncoder", "direct encoder", "ByteBuffer", "Array[Byte]", "String text",
                            "RawMessageDecoder", "no complete-consumption check", "2 independent JVM rounds",
                            "Two JVM observations", "Diagnostic run"):
            self.assertIn(description, rendered)
        self.assertNotIn("Each encode returns a fresh byte array", rendered)
        self.assertNotIn("five rounds", rendered)
        records[0]["params"]["usage"] = "fresh"
        with self.assertRaisesRegex(ValueError, "Result API usage"):
            report.render(records, metadata)


    def test_new_policy_report_labels_semantics_and_rejects_policy_mismatch(self):
        cells = [["P03", "encode", "wire"], ["P03", "encode", "java-specific"]]
        for usage in ("fresh", "reuse"):
            for policy in ("reject", "replace"):
                metadata = {"plan": {"cells": cells, "rounds": 5, "diagnostic": True, "profile": "full", "seed": 123,
                                     "usage": usage, "wireStringPolicy": policy,
                                     "apiContract": report.api_contract(usage, policy), "omitted": {}},
                            "catalog": {"cases": [{"id": "P03", "name": "int-3-byte"}]}, "gitRevision": "test-revision"}
                records = self.measurements()
                for record in records:
                    record["params"].update(usage=usage, wireStringPolicy=policy)
                rendered = report.render(records, metadata)
                self.assertIn(f"Wire string policy: `{policy}`", rendered)
                self.assertIn("outside measurement", rendered)
                self.assertIn("unpaired UTF-16 surrogates", rendered)
                records[0]["params"]["wireStringPolicy"] = "reject" if policy == "replace" else "replace"
                with self.assertRaisesRegex(ValueError, "Result Wire string policy"):
                    report.render(records, metadata)
                records[0]["params"]["wireStringPolicy"] = policy
                metadata["plan"]["apiContract"] = report.api_contract(usage)
                with self.assertRaisesRegex(ValueError, "Frozen API contract"):
                    report.render(records, metadata)

    def test_archived_reports_are_not_silently_relabelled_with_a_new_policy(self):
        cells = [["P03", "encode", "wire"], ["P03", "encode", "java-specific"]]
        for usage in (None, "fresh", "reuse"):
            metadata = {"plan": {"cells": cells, "rounds": 5, "diagnostic": True, "profile": "full", "seed": 123,
                                 "omitted": {}},
                        "catalog": {"cases": [{"id": "P03", "name": "int-3-byte"}]}, "gitRevision": "test-revision"}
            records = self.measurements()
            if usage is not None:
                metadata["plan"].update(usage=usage, apiContract=report.api_contract(usage))
                for record in records:
                    record["params"]["usage"] = usage
            rendered = report.render(records, metadata)
            # Digests captured from the reporter before the policy parameter was added.
            # Preserve historical report bytes as well as accepting their metadata shape.
            expected_digest = ("ba9091eeb83d2ba9736c7657618aa9562da18d6bff2fdcb39aab7e91f2a08589" if usage == "reuse"
                               else "dfbcbb2e9fbebf91082b823f047769a2dc391822ba64b4f9f893207f81e1050a")
            self.assertEqual(hashlib.sha256(rendered.encode()).hexdigest(), expected_digest)
            self.assertNotIn("Wire string policy", rendered)
            self.assertNotIn("Wire string policy: `replace`", rendered)
            self.assertNotIn("Wire string policy: `reject`", rendered)
            records[0]["params"]["wireStringPolicy"] = "replace"
            with self.assertRaisesRegex(ValueError, "Result Wire string policy"):
                report.render(records, metadata)


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
