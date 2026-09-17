#!/usr/bin/env bash
set -euo pipefail

# Keep the same minor-version release cycle as avro2s.
current_version=$(sed -n 's/^ThisBuild \/ version := "\(.*\)"$/\1/p' build.sbt)
if [[ ! "$current_version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  echo "Expected a stable major.minor.patch version, got: $current_version" >&2
  exit 1
fi
new_version="${BASH_REMATCH[1]}.$((10#${BASH_REMATCH[2]} + 1)).0-SNAPSHOT"
temporary_file=$(mktemp build.sbt.XXXXXX)
trap 'rm -f "$temporary_file"' EXIT
sed "s/^ThisBuild \/ version := \".*\"$/ThisBuild \/ version := \"$new_version\"/" build.sbt > "$temporary_file"
cat "$temporary_file" > build.sbt
printf 'Updated version to %s\n' "$new_version"
