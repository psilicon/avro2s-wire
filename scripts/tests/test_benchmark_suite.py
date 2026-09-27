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
        self.assertEqual(plan["configurations"]["wire"], report.resolve_configuration("wire", "reuse", "reject"))
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
        self.assertEqual(full["configurations"]["wire"], report.resolve_configuration("wire", "fresh", "reject"))
        reused = runner.make_plan(self.catalog, self.capabilities, "full", usage="reuse")
        self.assertTrue(reused["diagnostic"])
        self.assertNotIn("avro2s", {cell[2] for cell in reused["cells"]})
        with self.assertRaisesRegex(ValueError, "avro2s is outside"):
            runner.make_plan(self.catalog, self.capabilities, "smoke", usage="reuse", engines=["avro2s"])

    def test_wire_variants_are_opt_in_and_preserve_every_default_membership(self):
        for profile in ("full", "pilot", "smoke", "reuse-check", "quick"):
            for usage in (("reuse",) if profile == "reuse-check" else ("fresh", "reuse")):
                plan = runner.make_plan(self.catalog, self.capabilities, profile, usage=usage)
                self.assertFalse({cell[2] for cell in plan["cells"]}.intersection(report.WIRE_VARIANTS))
                self.assertFalse(set(plan["configurations"]).intersection(report.WIRE_VARIANTS))
        for usage in ("fresh", "reuse"):
            base = runner.make_plan(self.catalog, self.capabilities, "quick", engines=["wire"], usage=usage)
            expected = {(case_id, operation) for case_id, operation, _ in base["cells"]}
            for engine in report.WIRE_VARIANTS:
                plan = runner.make_plan(self.catalog, self.capabilities, "quick", engines=[engine], usage=usage,
                                        reference_engine=engine)
                self.assertEqual({(case_id, operation) for case_id, operation, _ in plan["cells"]}, expected)
                self.assertEqual(plan["expectedCells"], 119)
                self.assertEqual(set(plan["configurations"]), {engine})
                self.assertEqual(plan["referenceEngine"], engine)

    def test_wire_variant_capabilities_follow_wire_instead_of_java_specific(self):
        engines = ["wire"] + list(report.WIRE_VARIANTS)
        plan = runner.make_plan(self.catalog, self.capabilities, "quick", engines=engines,
                                selections=["L06:encode", "L09:encode", "E*:decode"])
        self.assertEqual(plan["expectedCells"], 5 * len(engines))
        changed = copy.deepcopy(self.capabilities)
        row = next(case for case in changed["cases"] if case["id"] == "L06")
        row["engines"]["wire"].update(operations=["decode"], reason="Test capability exclusion")
        with self.assertRaisesRegex(ValueError, "no supported cells.*L06:encode"):
            runner.make_plan(self.catalog, changed, "quick", engines=["wire-java"], selections=["L06:encode", "P03:encode"])

    def test_case_globs_expand_in_catalogue_order_and_overlap_is_deduplicated(self):
        plan = runner.make_plan(self.catalog, self.capabilities, "quick", engines=["wire"], cases=["P*", "P03", "P0?"])
        self.assertEqual(plan["expectedCells"], 20)
        self.assertEqual({cell[0] for cell in plan["cells"]}, {f"P{index:02d}" for index in range(1, 11)})
        cases = runner.expand_selectors(["T0[12]", "T1*", "T01"], {case["id"]: case["operations"] for case in self.catalog["cases"]})
        self.assertEqual(cases, ["T01", "T02", "T10", "T11", "T12", "T13", "T14", "T15"])
        for selected, error in ((["p*"], "case-sensitive"), (["Q*"], "no catalogue IDs"),
                                (["P03", "P03"], "Duplicate"), (["P*", "P*"], "Duplicate")):
            with self.subTest(selected=selected), self.assertRaisesRegex(ValueError, error):
                runner.make_plan(self.catalog, self.capabilities, "quick", cases=selected)

    def test_operation_globs_expand_exact_pairs_and_reject_unsupported_matches(self):
        plan = runner.make_plan(self.catalog, self.capabilities, "quick", engines=["wire", "wire-java"],
                                selections=["T*:encode", "T01:encode", "T0?:decode"])
        expected = [[f"T{index:02d}", "encode"] for index in range(1, 16)]
        expected += [[f"T{index:02d}", "decode"] for index in range(1, 10)]
        self.assertEqual(plan["selectedOperations"], expected)
        self.assertEqual(plan["expectedCells"], 48)
        for selections, error in ((["t*:encode"], "no catalogue IDs"), (["E*:encode"], "Unsupported catalogue operation"),
                                  (["*:encode"], "Unsupported catalogue operation"), (["P*:write"], "Invalid"),
                                  (["T*:encode", "T*:encode"], "Duplicate")):
            with self.subTest(selections=selections), self.assertRaisesRegex(ValueError, error):
                runner.make_plan(self.catalog, self.capabilities, "quick", selections=selections)
        with self.assertRaisesRegex(ValueError, "no supported cells.*L06:encode"):
            runner.make_plan(self.catalog, self.capabilities, "quick", engines=["java-specific"],
                             selections=["L0[56]:encode"])

    def test_cli_accepts_quoted_globs_and_an_explicit_comparison_denominator(self):
        args = runner.parse_args(["--java", sys.executable, "--select", "T*:encode", "--engine", "wire-java",
                                  "--engine", "wire-java-stack-safe", "--reference-engine", "wire-java"])
        self.assertEqual(args.selections, ["T*:encode"])
        self.assertEqual(args.engines, ["wire-java", "wire-java-stack-safe"])
        self.assertEqual(args.reference_engine, "wire-java")
        plan = runner.make_plan(self.catalog, self.capabilities, "quick", engines=args.engines,
                                selections=args.selections, reference_engine=args.reference_engine)
        self.assertEqual(plan["referenceEngine"], "wire-java")
        self.assertEqual(plan["expectedCells"], 30)
        with self.assertRaisesRegex(ValueError, "reference engine must be one of"):
            runner.make_plan(self.catalog, self.capabilities, "quick", engines=["wire"], reference_engine="wire-java")
        # Existing engine-only selection remains valid without a reference row.
        plan = runner.make_plan(self.catalog, self.capabilities, "quick", engines=["java-specific"])
        self.assertEqual(plan["referenceEngine"], "wire")

    def test_string_policy_is_explicit_in_cli_plan_and_contract(self):
        args = runner.parse_args(["--java", sys.executable])
        self.assertEqual(args.wire_string_policy, "reject")
        args = runner.parse_args(["--java", sys.executable, "--wire-string-policy", "replace"])
        self.assertEqual(args.wire_string_policy, "replace")
        for usage in ("fresh", "reuse"):
            for policy in ("reject", "replace"):
                plan = runner.make_plan(self.catalog, self.capabilities, "full", usage=usage, wire_string_policy=policy)
                self.assertEqual(plan["wireStringPolicy"], policy)
                self.assertEqual(plan["configurations"]["wire"], report.resolve_configuration("wire", usage, policy))
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
        for selected in (report.DEFAULT_ENGINES, report.ENGINES):
            cells = [["P03", "encode", engine] for engine in selected]
            schedule = runner.schedule(cells, len(selected), 123)
            self.assertEqual(schedule, runner.schedule(cells, len(selected), 123))
            by_round = [[row["engine"] for row in schedule if row["round"] == index] for index in range(1, len(selected) + 1)]
            for index, engines in enumerate(by_round):
                self.assertEqual(engines, by_round[0][index:] + by_round[0][:index])
            self.assertEqual({engines[0] for engines in by_round}, set(selected))

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

    def test_java_io_parameters_must_match_and_cannot_masquerade_as_historical_results(self):
        configurations = [("wire", "fresh", "none", "none"),
                          ("java-specific", "fresh", "factory", "unbuffered"),
                          ("java-generic", "reuse", "factory", "buffered"),
                          ("java-custom", "reuse", "raw-message", "unbuffered"),
                          ("wire-java-stack-safe", "reuse", "factory", "unbuffered")]
        for engine, usage, api, encoder in configurations:
            record = sample_record(engine=engine)
            record["params"].update(usage=usage, wireStringPolicy="reject", javaApi=api, javaEncoder=encoder)
            kwargs = {"usage": usage, "wire_string_policy": "reject", "java_api": api, "java_encoder": encoder}
            self.assertEqual(report.validate_fork([record], ("P03", "encode", engine), self.timing, **kwargs), record)
            for key in ("javaApi", "javaEncoder"):
                changed = copy.deepcopy(record)
                changed["params"][key] = "wrong"
                with self.subTest(engine=engine, key=key), self.assertRaisesRegex(ValueError, "Unexpected Java API or encoder"):
                    report.validate_fork([changed], ("P03", "encode", engine), self.timing, **kwargs)
                del changed["params"][key]
                with self.assertRaisesRegex(ValueError, "parameters"):
                    report.validate_fork([changed], ("P03", "encode", engine), self.timing, **kwargs)
            with self.assertRaisesRegex(ValueError, "parameters"):
                report.validate_fork([record], ("P03", "encode", engine), self.timing,
                                     usage=usage, wire_string_policy="reject")

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
        self.assertIn("javaApi=none", command)
        self.assertIn("javaEncoder=none", command)
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

    def test_wire_configuration_ratios_use_explicit_denominator_and_preserve_round_pairs(self):
        catalog, caps = runner.load_inputs(SCRIPTS.parent)
        for denominator in ("wire", "wire-java", "wire-stack-safe"):
            engines = ["wire", "wire-java", "wire-stack-safe"]
            plan = runner.make_plan(catalog, caps, "quick", engines=engines, selections=["P03:encode"],
                                    reference_engine=denominator, usage="reuse")
            records = []
            for index, base in enumerate((100, 120), 1):
                for engine, factor in zip(engines, (1, 2, 4)):
                    record = sample_record(engine=engine, values=[base * factor] * 5)
                    record["params"].update(usage="reuse", wireStringPolicy="reject")
                    config = plan["configurations"][engine]
                    record["params"].update(javaApi=config["javaApi"], javaEncoder=config["javaEncoder"])
                    records.append({**record, "_round": index, "_sourceFile": f"raw/{engine}-{index}.json"})
            rendered = report.render(records, {"plan": plan, "catalog": catalog, "gitRevision": "test"})
            self.assertIn(f"Configuration / {denominator}", rendered)
            self.assertIn(f"denominator is `{denominator}`", rendered)
            self.assertIn("Java-backed Wire always uses Java string", rendered)
            self.assertIn("Retained ByteArrayOutputStream and buffered BinaryEncoder", rendered)
            self.assertIn("explicit complete-consumption check", rendered)
            self.assertIn("Generated stack-safe Wire codec", rendered)
            for engine, factor in zip(engines, (1, 2, 4)):
                row = next(line for line in rendered.splitlines() if line.startswith(f"| {engine} |") and "[" in line)
                expected = factor / (1, 2, 4)[engines.index(denominator)]
                self.assertIn("| — |" if engine == denominator else f"{expected:.3f} [{expected:.3f}, {expected:.3f}]", row)


class ConfigurationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog, cls.capabilities = runner.load_inputs(SCRIPTS.parent)

    def plan(self, variants=None, **kwargs):
        return runner.make_plan(self.catalog, self.capabilities, "quick", selections=["P03:encode"],
                                variants=variants or ["name=fresh,engine=wire,usage=fresh,string-policy=replace", "name=reuse,engine=wire,usage=reuse,string-policy=replace"], **kwargs)

    def records(self, plan):
        records = []
        for row in plan["schedule"]:
            variant = plan["variants"][row["engine"]]
            # Same actual engine, different configuration and inter-round base.
            factor = 2 if row["engine"] == next(iter(plan["variants"])) else 1
            record = sample_record(engine=variant["engine"], values=[(100 + row["round"] * 10) * factor] * 5)
            record["params"].update(usage=variant["usage"], wireStringPolicy=variant["wireStringPolicy"])
            record["params"].update(javaApi=variant["javaApi"], javaEncoder=variant["javaEncoder"])
            records.append({**record, "_variant": row["engine"], "_round": row["round"],
                            "_sourceFile": f"raw/{row['engine']}-{row['round']}.json"})
        return records

    def test_same_engine_usage_and_policy_variants_have_distinct_cells_and_paired_rounds(self):
        for variants in (["name=fresh,engine=wire,usage=fresh,string-policy=replace", "name=reuse,engine=wire,usage=reuse,string-policy=replace"],
                         ["name=strict,engine=wire,usage=reuse,string-policy=reject", "name=replacement,engine=wire,usage=reuse,string-policy=replace"]):
            plan = self.plan(variants)
            names = list(plan["variants"])
            self.assertEqual(plan["cells"], [["P03", "encode", name] for name in names])
            self.assertEqual(plan["referenceVariant"], names[0])
            self.assertEqual(plan["expectedCells"], 2)
            self.assertEqual(plan["expectedJVMForks"], 4)
            self.assertEqual(plan["warmupAndMeasurementSeconds"], 40)
            self.assertNotIn("usage", plan)
            self.assertNotIn("wireStringPolicy", plan)
            records = self.records(plan)
            rendered = report.render(records, {"plan": plan, "catalog": self.catalog, "gitRevision": "test"})
            self.assertIn(f"Configuration / {names[0]}", rendered)
            self.assertIn("0.500 [0.500, 0.500]", rendered)
            for name in names:
                for index in (1, 2):
                    self.assertIn(f"raw/{name}-{index}.json", rendered)
            for round_number in (1, 2):
                self.assertEqual({row["engine"] for row in plan["schedule"] if row["round"] == round_number}, set(names))

    def test_variant_reference_override_and_global_policy_default(self):
        plan = self.plan(["name=fresh,engine=wire,usage=fresh", "name=reuse,engine=wire,usage=reuse,string-policy=reject"], wire_string_policy="replace", reference_variant="reuse")
        self.assertEqual(plan["variants"]["fresh"]["wireStringPolicy"], "replace")
        self.assertEqual(plan["variants"]["reuse"]["wireStringPolicy"], "reject")
        rendered = report.render(self.records(plan), {"plan": plan, "catalog": self.catalog, "gitRevision": "test"})
        self.assertIn("Configuration / reuse", rendered)
        self.assertIn("2.000 [2.000, 2.000]", rendered)

    def test_named_variant_fields_are_order_independent_and_trim_whitespace(self):
        specification = " usage = reuse , string-policy = replace , engine = wire , name = reused-output "
        args = runner.parse_args(["--java", sys.executable, "--variant", specification])
        plan = self.plan(args.variants)
        self.assertEqual(list(plan["variants"]), ["reused-output"])
        self.assertEqual(plan["variants"]["reused-output"]["engine"], "wire")
        self.assertEqual(plan["variants"]["reused-output"]["usage"], "reuse")
        self.assertEqual(plan["variants"]["reused-output"]["wireStringPolicy"], "replace")

    def test_named_variant_fields_reject_missing_duplicate_unknown_and_empty_values(self):
        complete = "name=reused,engine=wire,usage=reuse"
        for field in ("name", "engine", "usage"):
            tokens = complete.split(",")
            missing = ",".join(token for token in tokens if not token.startswith(field + "="))
            empty = ",".join(field + "= " if token.startswith(field + "=") else token for token in tokens)
            duplicate = complete + "," + next(token for token in tokens if token.startswith(field + "="))
            for specification, error in ((missing, "Missing --variant fields"), (empty, "Empty --variant field"),
                                         (duplicate, "Duplicate --variant field")):
                with self.subTest(specification=specification), self.assertRaisesRegex(ValueError, error):
                    self.plan([specification])
        invalid = [(complete + ",string-policy=", "Empty --variant field"),
                   (complete + ",string-policy=reject,string-policy=replace", "Duplicate --variant field"),
                   (complete + ",policy=replace", "Unknown --variant field"),
                   (complete + ",=replace", "Unknown --variant field"),
                   (complete + ",string-policy=replace=reject", "Invalid --variant field"),
                   (complete + ",", "Invalid --variant field"),
                   ("reuse:wire:reuse:replace", "Invalid --variant field"),
                   ("", "Invalid --variant field")]
        for specification, error in invalid:
            with self.subTest(specification=specification), self.assertRaisesRegex(ValueError, error):
                self.plan([specification])

    def test_official_java_named_variants_require_api_and_factory_matrix_is_independent(self):
        for engine in ("java-specific", "java-generic", "java-custom"):
            with self.assertRaisesRegex(ValueError, "require explicit java-api"):
                self.plan([f"name=java,engine={engine},usage=fresh"])
            for usage in ("fresh", "reuse"):
                for encoder in ("buffered", "unbuffered"):
                    plan = self.plan([f"name=java,engine={engine},usage={usage},java-api=factory,java-encoder={encoder}"])
                    config = plan["variants"]["java"]
                    self.assertEqual((config["javaApi"], config["javaEncoder"], config["usage"]), ("factory", encoder, usage))
                    self.assertIn("independently owned Array[Byte]", config["contract"]["encode"])
                    self.assertIn("explicit complete-consumption", config["contract"]["decode"])
                    self.assertNotIn("RawMessage", str(config["contract"]))
            default = self.plan([f"name=java,engine={engine},usage=reuse,java-api=factory"])
            self.assertEqual(default["variants"]["java"]["javaEncoder"], "buffered")

    def test_raw_message_requires_reuse_and_unbuffered_but_defaults_encoder(self):
        config = self.plan(["name=java,engine=java-specific,usage=reuse,java-api=raw-message"])["variants"]["java"]
        self.assertEqual(config["javaEncoder"], "unbuffered")
        self.assertIn("independently owned ByteBuffer", config["contract"]["encode"])
        self.assertIn("no complete-consumption check", config["contract"]["decode"])
        for specification in ("name=java,engine=java-specific,usage=fresh,java-api=raw-message",
                              "name=java,engine=java-specific,usage=reuse,java-api=raw-message,java-encoder=buffered",
                              "name=java,engine=wire-java,usage=reuse,java-api=raw-message"):
            with self.assertRaisesRegex(ValueError, "raw-message requires"):
                self.plan([specification])

    def test_native_rejects_java_settings_and_wire_java_supports_factory_encoder_matrix(self):
        for engine in ("wire", "wire-stack-safe"):
            for setting in ("java-api=factory", "java-encoder=buffered", "java-api=none", "java-encoder=none"):
                with self.assertRaisesRegex(ValueError, "Native Wire does not accept"):
                    self.plan([f"name=native,engine={engine},usage=fresh,{setting}"])
        for engine in ("wire-java", "wire-java-stack-safe"):
            for usage in ("fresh", "reuse"):
                for encoder in ("buffered", "unbuffered"):
                    config = self.plan([f"name=java,engine={engine},usage={usage},java-encoder={encoder}"])["variants"]["java"]
                    self.assertEqual(config["javaApi"], "factory")
                    self.assertEqual(config["javaEncoder"], encoder)
                    self.assertIn("JavaAvroOutput", config["contract"]["encode"])
        avro2s = self.plan(["name=scala,engine=avro2s,usage=fresh,java-encoder=unbuffered"])["variants"]["scala"]
        self.assertEqual((avro2s["javaApi"], avro2s["javaEncoder"]), ("factory", "unbuffered"))

    def test_ordinary_presets_resolve_unchanged_paths_and_new_contracts_say_standard(self):
        for usage in ("fresh", "reuse"):
            plan = runner.make_plan(self.catalog, self.capabilities, "quick", usage=usage,
                                    engines=["wire", "wire-stack-safe", "wire-java", "wire-java-stack-safe", "java-specific"])
            self.assertEqual(plan["configurationVersion"], 1)
            for engine, config in plan["configurations"].items():
                expected = (("none", "none") if engine in ("wire", "wire-stack-safe") else
                            ("raw-message", "unbuffered") if engine == "java-specific" and usage == "reuse" else
                            ("factory", "buffered"))
                self.assertEqual((config["javaApi"], config["javaEncoder"]), expected)
                if engine in ("wire", "wire-java"):
                    self.assertIn("Standard", config["contract"]["codec"])
                    self.assertNotIn("direct codec", config["contract"]["codec"])

    def test_named_java_settings_are_frozen_into_commands_and_reports(self):
        plan = self.plan(["name=new,engine=java-specific,usage=fresh,java-api=factory,java-encoder=unbuffered",
                          "name=retained,engine=java-specific,usage=reuse,java-api=factory,java-encoder=buffered",
                          "name=message,engine=java-specific,usage=reuse,java-api=raw-message"])
        records = self.records(plan)
        rendered = report.render(records, {"plan": plan, "catalog": self.catalog, "gitRevision": "test"})
        self.assertIn("| new | java-specific | fresh | factory | unbuffered |", rendered)
        self.assertIn("| retained | java-specific | reuse | factory | buffered |", rendered)
        self.assertIn("| message | java-specific | reuse | raw-message | unbuffered |", rendered)
        self.assertIn("encoder setting affects encoding only", rendered)
        for row in plan["schedule"]:
            config = plan["variants"][row["engine"]]
            command, _ = runner.jmh_command(Path("/jdk/bin/java"), ["/classes"], row, plan["timing"], Path("/results"),
                                            config["usage"], config["wireStringPolicy"], config["engine"], config["javaApi"], config["javaEncoder"])
            self.assertIn(f"javaApi={config['javaApi']}", command)
            self.assertIn(f"javaEncoder={config['javaEncoder']}", command)
        for field in ("javaApi", "javaEncoder"):
            broken = copy.deepcopy(records)
            broken[0]["params"][field] = "none"
            with self.assertRaisesRegex(ValueError, "parameters do not match frozen configuration"):
                report.render(broken, {"plan": plan, "catalog": self.catalog, "gitRevision": "test"})
        tampered = copy.deepcopy(plan)
        tampered["variants"]["new"]["javaEncoder"] = "buffered"
        with self.assertRaisesRegex(ValueError, "Frozen configuration contract"):
            report.render(records, {"plan": tampered, "catalog": self.catalog, "gitRevision": "test"})

    def test_java_variant_settings_reject_unknown_empty_or_duplicate_fields(self):
        base = "name=java,engine=java-specific,usage=reuse,java-api=factory"
        for specification, message in ((base + ",java-api=factory", "Duplicate --variant field"),
                                       (base + ",java-encoder=", "Empty --variant field"),
                                       (base.replace("java-api=factory", "java-api="), "Empty --variant field"),
                                       (base.replace("java-api=factory", "java-api=bogus"), "Unknown Java API"),
                                       (base + ",java-encoder=direct", "Unknown Java encoder")):
            with self.subTest(specification=specification), self.assertRaisesRegex(ValueError, message):
                self.plan([specification])

    def test_variant_parameters_and_contracts_cannot_be_mislabelled(self):
        plan = self.plan()
        for key, value in (("engine", "wire-stack-safe"), ("usage", "fresh"), ("wireStringPolicy", "reject")):
            records = self.records(plan)
            record = next(record for record in records if record["_variant"] == "reuse")
            record["params"][key] = value
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "parameters do not match frozen configuration"):
                report.render(records, {"plan": plan, "catalog": self.catalog, "gitRevision": "test"})
        for label in (None, "unknown"):
            records = self.records(plan)
            records[0]["_variant"] = label
            with self.assertRaisesRegex(ValueError, "missing or unknown configuration"):
                report.render(records, {"plan": plan, "catalog": self.catalog, "gitRevision": "test"})
        records = self.records(plan)
        for broken in (records[:-1], records + [records[0]]):
            with self.assertRaises(ValueError):
                report.render(broken, {"plan": plan, "catalog": self.catalog, "gitRevision": "test"})
        plan["variants"]["reuse"]["contract"] = report.resolve_configuration("wire", "fresh", "replace")["contract"]
        with self.assertRaisesRegex(ValueError, "Frozen configuration contract"):
            report.render(records, {"plan": plan, "catalog": self.catalog, "gitRevision": "test"})

    def test_variant_validation_rejects_ambiguous_or_unsupported_configuration(self):
        invalid = [(["name=same,engine=wire,usage=fresh", "name=same,engine=wire,usage=reuse"], "Duplicate variant name"),
                   (["name=Fast,engine=wire,usage=fresh", "name=fast,engine=wire,usage=reuse"], "Duplicate variant name"),
                   (["name=../escape,engine=wire,usage=fresh"], "Invalid --variant"), (["name=bad,engine=wire,usage=unknown"], "Invalid engine"),
                   (["name=bad,engine=unknown,usage=fresh"], "Invalid engine"), (["name=bad,engine=wire,usage=fresh,string-policy=unknown"], "Invalid engine"),
                   (["name=bad,engine=avro2s,usage=reuse"], "avro2s is outside"), (["bad:wire"], "Invalid --variant")]
        for variants, error in invalid:
            with self.subTest(variants=variants), self.assertRaisesRegex(ValueError, error):
                self.plan(variants)
        for kwargs in ({"engines": ["wire"]}, {"usage": "reuse"}, {"reference_engine": "wire"}):
            with self.assertRaisesRegex(ValueError, "cannot be combined"):
                self.plan(**kwargs)
        with self.assertRaisesRegex(ValueError, "Reference variant"):
            self.plan(reference_variant="missing")
        with self.assertRaisesRegex(ValueError, "requires --variant"):
            runner.make_plan(self.catalog, self.capabilities, "quick", reference_variant="fresh")
        with self.assertRaisesRegex(ValueError, "reuse-check fixes"):
            runner.make_plan(self.catalog, self.capabilities, "reuse-check", variants=["name=one,engine=wire,usage=reuse"])

    def test_mixed_capability_variants_keep_na_but_fail_globally_unsupported_operations(self):
        plan = runner.make_plan(self.catalog, self.capabilities, "quick", selections=["P03:encode", "L06:encode"],
                                variants=["name=native,engine=wire,usage=fresh", "name=specific,engine=java-specific,usage=fresh,java-api=factory"])
        self.assertEqual(plan["cells"], [["P03", "encode", "native"], ["P03", "encode", "specific"], ["L06", "encode", "native"]])
        self.assertIn("999 ms", plan["omitted"]["L06"]["encode"]["specific"])
        only_native = runner.make_plan(self.catalog, self.capabilities, "quick", selections=["L06:encode"],
                                      variants=["name=native,engine=wire,usage=fresh", "name=specific,engine=java-specific,usage=fresh,java-api=factory"])
        self.assertEqual(only_native["cells"], [["L06", "encode", "native"]])
        with self.assertRaisesRegex(ValueError, "no supported cells.*L06:encode"):
            runner.make_plan(self.catalog, self.capabilities, "quick", selections=["P03:encode", "L06:encode"],
                             variants=["name=specific,engine=java-specific,usage=fresh,java-api=factory", "name=generic,engine=java-generic,usage=fresh,java-api=factory"])
        with self.assertRaisesRegex(ValueError, "no supported benchmark cells"):
            runner.make_plan(self.catalog, self.capabilities, "quick", selections=["L06:encode"],
                             variants=["name=specific,engine=java-specific,usage=fresh,java-api=factory"])

    def test_command_uses_actual_parameters_but_unique_labelled_output_paths(self):
        plan = self.plan()
        outputs = set()
        for row in plan["schedule"]:
            variant = plan["variants"][row["engine"]]
            command, result = runner.jmh_command(Path("/jdk/bin/java"), ["/classes"], row, plan["timing"], Path("/results"),
                                                 variant["usage"], variant["wireStringPolicy"], actual_engine=variant["engine"])
            self.assertIn("engine=wire", command)
            self.assertIn(f"usage={variant['usage']}", command)
            self.assertIn("wireStringPolicy=replace", command)
            self.assertNotIn(f"engine={row['engine']}", command)
            outputs.add(result)
        self.assertEqual(len(outputs), plan["expectedJVMForks"])


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
