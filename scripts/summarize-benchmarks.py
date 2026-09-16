#!/usr/bin/env python3
"""Summarize JMH average-time JSON, including allocation and Avro2s comparisons."""

import argparse
import hashlib
import json
import math
from pathlib import Path


def number(value):
    return f"{value:,.1f}" if isinstance(value, (int, float)) and math.isfinite(value) else "—"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=Path, help="JMH JSON result file")
    parser.add_argument("--verify-sources", type=Path, help="Verify source hashes from an environment JSON file")
    args = parser.parse_args()
    if args.verify_sources:
        root = Path(__file__).resolve().parent.parent
        snapshot = json.loads(args.verify_sources.read_text())
        mismatches = []
        for relative, expected in snapshot["sourceSha256"].items():
            source = (root / relative).resolve()
            try:
                source.relative_to(root)
            except ValueError:
                raise SystemExit(f"Source hash path escapes the project: {relative}")
            if not source.is_file() or hashlib.sha256(source.read_bytes()).hexdigest() != expected:
                mismatches.append(relative)
        if mismatches:
            raise SystemExit("Sources differ from the measured version:\n" + "\n".join(mismatches))
        print(f"Verified {len(snapshot['sourceSha256'])} source/build hashes.\n")
    rows = json.loads(args.results.read_text())
    records = {}
    for row in rows:
        primary = row["primaryMetric"]
        if row["mode"] != "avgt" or primary["scoreUnit"] != "ns/op":
            raise SystemExit("Expected average-time benchmarks in ns/op")
        name = row["benchmark"].rsplit(".", 1)[-1]
        size = int(row["params"]["collectionSize"])
        key = (size, name)
        if key in records:
            raise SystemExit(f"Duplicate result: {key}")
        records[key] = row

    print("| Collection size | Benchmark | ns/op | ±99.9% CI | B/op |")
    print("| ---: | --- | ---: | ---: | ---: |")
    for (size, name), row in sorted(records.items()):
        primary = row["primaryMetric"]
        allocation = row.get("secondaryMetrics", {}).get("gc.alloc.rate.norm", {}).get("score")
        print(f"| {size} | {name} | {number(primary['score'])} | "
              f"{number(primary.get('scoreError'))} | {number(allocation)} |")

    print("\nRatios below divide avro2s time/allocation by the comparison baseline. "
          "Values above 1 mean avro2s took more time or allocated more bytes. "
          "These are ratios of means, not confidence intervals for the ratios.\n")
    print("| Size | Operation | Comparison | Time ratio | Allocation ratio |")
    print("| ---: | --- | --- | ---: | ---: |")
    for size in sorted({key[0] for key in records}):
        for operation in ("Read", "Write"):
            avro2s = records.get((size, f"avro2s{operation}"))
            if avro2s is None:
                continue
            for comparison in ("javaSpecific", "javaCustom", "native"):
                baseline = records.get((size, f"{comparison}{operation}"))
                if baseline is None:
                    continue
                time_ratio = avro2s["primaryMetric"]["score"] / baseline["primaryMetric"]["score"]
                avro_allocation = avro2s.get("secondaryMetrics", {}).get("gc.alloc.rate.norm", {}).get("score")
                base_allocation = baseline.get("secondaryMetrics", {}).get("gc.alloc.rate.norm", {}).get("score")
                allocation_ratio = (
                    avro_allocation / base_allocation
                    if avro_allocation is not None and base_allocation is not None and base_allocation >= 1.0
                    else None
                )
                print(f"| {size} | {operation.lower()} | {comparison} | "
                      f"{number(time_ratio)}× | {number(allocation_ratio)}{'×' if allocation_ratio is not None else ''} |")


if __name__ == "__main__":
    main()
