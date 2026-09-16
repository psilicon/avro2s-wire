#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 1 ]]; then
  printf 'Usage: %s /path/to/avro2s-checkout\n' "$0" >&2
  exit 2
fi

avrogen_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
avro2s_checkout=$(git -C "$1" rev-parse --show-toplevel)
expected_commit=8342d5bd467aca16265b1f083b8b6b667929ee37
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

export AVROGEN_BASELINE_ROOT="$avrogen_root"
export AVROGEN_BASELINE_COMMIT="$expected_commit"
export AVROGEN_BASELINE_GENERATOR="$avrogen_root/benchmarks/generator/GenerateBaselines.scala"

cd "$avro2s_checkout"
# These are in-memory sbt session settings; no avro2s source or build file is edited.
"${AVROGEN_SBT:-sbt}" \
  'set LocalProject("avro2s3") / Compile / unmanagedSources += file(sys.env("AVROGEN_BASELINE_GENERATOR"))' \
  'avro2s3/runMain avrogen.benchmarks.generator.GenerateBaselines'
