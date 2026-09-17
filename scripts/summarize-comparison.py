#!/usr/bin/env python3
"""Summarize broader JMH comparisons without merging distinct workloads or confidence intervals."""
import argparse
import json
import math
from pathlib import Path


def number(value):
    return f"{value:,.2f}" if isinstance(value, (int, float)) and math.isfinite(value) else "—"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", nargs="+", type=Path)
    args = parser.parse_args()
    groups = {}
    for path in args.results:
        for row in json.loads(path.read_text()):
            metric = row["primaryMetric"]
            if row["mode"] != "avgt" or metric["scoreUnit"] != "ns/op":
                parser.error(f"Expected average time in ns/op: {path}")
            benchmark, method = row["benchmark"].rsplit(".", 1)
            group = (benchmark.rsplit(".", 1)[-1], tuple(sorted(row.get("params", {}).items())))
            records = groups.setdefault(group, {})
            if method in records:
                parser.error(f"Duplicate {group}, {method}; summarize repeated experiments separately")
            records[method] = row
    for (benchmark, params), records in sorted(groups.items()):
        label = ", ".join(f"{k}={v}" for k, v in params) or "single workload"
        print(f"\n### {benchmark}: {label}\n")
        print("| Method | ns/op | ±99.9% CI | B/op |")
        print("| --- | ---: | ---: | ---: |")
        for method, row in sorted(records.items()):
            metric = row["primaryMetric"]
            allocation = row.get("secondaryMetrics", {}).get("gc.alloc.rate.norm", {}).get("score")
            print(f"| {method} | {number(metric['score'])} | {number(metric.get('scoreError'))} | {number(allocation)} |")
        if "nativeRead" in records:
            print("\nRatios are comparison time / native time; above 1 means native took less time. "
                  "They are ratios of means, not confidence intervals for speedups.\n")
            print("| Comparison | Read ratio | Write ratio | Read CIs overlap? | Write CIs overlap? |")
            print("| --- | ---: | ---: | :---: | :---: |")
            engines = sorted({name[:-4] for name in records if name.endswith("Read") and name != "nativeRead"})
            for engine in engines:
                ratios, overlaps = [], []
                for operation in ["Read", "Write"]:
                    native, other = records.get("native" + operation), records.get(engine + operation)
                    if native is None or other is None:
                        ratios.append("—"); overlaps.append("—"); continue
                    a, b = native["primaryMetric"], other["primaryMetric"]
                    ratios.append(number(b["score"] / a["score"]) + "×")
                    lower = max(a["scoreConfidence"][0], b["scoreConfidence"][0])
                    upper = min(a["scoreConfidence"][1], b["scoreConfidence"][1])
                    overlaps.append("yes" if lower <= upper else "no")
                print(f"| {engine} | {ratios[0]} | {ratios[1]} | {overlaps[0]} | {overlaps[1]} |")


if __name__ == "__main__":
    main()
