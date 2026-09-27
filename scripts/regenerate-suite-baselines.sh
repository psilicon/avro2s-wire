#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 1 ]]; then
  printf 'Usage: %s /path/to/avro2s-checkout\n' "$0" >&2
  exit 2
fi

wire_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
avro2s_checkout=$(git -C "$1" rev-parse --show-toplevel)
expected_commit=861c816b63643edfaf012d2b84f1bc0ea8b4ce99
actual_commit=$(git -C "$avro2s_checkout" rev-parse HEAD)
if [[ "$actual_commit" != "$expected_commit" ]]; then
  printf 'Expected avro2s commit %s; found %s. Use a checkout at the pinned revision.\n' "$expected_commit" "$actual_commit" >&2
  exit 1
fi
if ! git -C "$avro2s_checkout" diff --quiet HEAD -- build.sbt project avro2s/src; then
  printf 'The avro2s build or sources have tracked changes; use a clean pinned checkout.\n' >&2
  exit 1
fi
if [[ -n $(git -C "$avro2s_checkout" ls-files --others --exclude-standard -- project avro2s/src/main) ]]; then
  printf 'The avro2s build or main sources contain untracked files; use a clean pinned checkout.\n' >&2
  exit 1
fi

export AVRO2S_WIRE_SUITE_ROOT="$wire_root"
export AVRO2S_WIRE_SUITE_COMMIT="$expected_commit"
export AVRO2S_WIRE_SUITE_GENERATOR="$wire_root/benchmarks/generator/GenerateSuiteBaselines.scala"

cd "$avro2s_checkout"
# Session-only source inclusion; neither the avro2s checkout nor its build is changed.
"${AVRO2S_WIRE_SBT:-sbt}" \
  'set LocalProject("avro2s3") / Compile / unmanagedSources += file(sys.env("AVRO2S_WIRE_SUITE_GENERATOR"))' \
  'avro2s3/runMain avro2s.wire.benchmarks.generator.GenerateSuiteBaselines'
