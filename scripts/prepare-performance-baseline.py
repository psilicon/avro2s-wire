#!/usr/bin/env python3
"""Export old production sources with today's benchmark harness for paired measurements."""
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile

DEFAULT_REVISION = "c73469839df8caf895042c36e20432fa89355945"
FIXTURES = ["PerfInts", "PerfLongs", "PerfString", "PerfBytes", "PerfCollections", "PerfNested"]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def migrate_historical_namespace(destination, root):
    """Keep pre-rename production code usable with the current benchmark imports.

    This changes package names only, records every changed source hash, and leaves
    the historical algorithm implementations in place.
    """
    old = "avro" + "gen"
    new = "avro2s.wire"
    if not (root / "runtime/src/main/scala/avro2s/wire").is_dir():
        return {}
    changes = {}
    for path in sorted(destination.rglob("*")):
        if not path.is_file() or path.suffix not in {".scala", ".java", ".avsc", ".sbt"}:
            continue
        original = path.read_bytes()
        updated = (original.decode().replace(old + ".", new + ".")
                   .replace(old + "/", new.replace(".", "/") + "/")
                   .replace("private[" + old + "]", "private[wire]").encode())
        if updated != original:
            path.write_bytes(updated)
            changes[str(path.relative_to(destination))] = {"before": digest(original), "after": digest(updated)}
    for language in ("scala", "java"):
        for kind in ("main", "test"):
            for directory in sorted(destination.glob(f"*/src/{kind}/{language}/{old}")):
                target = directory.parent / "avro2s" / "wire"
                target.parent.mkdir(parents=True, exist_ok=True)
                directory.rename(target)
    return changes


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path, help="New directory; existing paths are rejected")
    parser.add_argument("--revision", default=DEFAULT_REVISION, help="Production-source Git revision")
    parser.add_argument("--repository", type=Path, default=root, help="Git repository to export; overlays come from this script's project")
    args = parser.parse_args()
    # Do not resolve away an existing dangling symlink at the destination.
    destination = Path(os.path.abspath(args.destination.expanduser()))
    if os.path.lexists(destination):
        parser.error("destination already exists; choose a new directory")
    repository = args.repository.resolve()
    resolved = subprocess.run(["git", "rev-parse", "--verify", args.revision + "^{commit}"],
                              cwd=repository, check=True, capture_output=True, text=True).stdout.strip()
    archive = subprocess.run(["git", "archive", "--format=tar", resolved], cwd=repository,
                             check=True, capture_output=True).stdout
    overlays = sorted(path for path in (root / "benchmarks/src").rglob("*") if path.is_file())
    if not overlays:
        parser.error("current benchmarks/src must contain source files")
    overlays += [root / f"fixtures/src/main/resources/avro/{name}.avsc" for name in FIXTURES]
    overlays += sorted((root / "fixtures/src/main/resources/avro/comparison").glob("*.avsc"))
    overlays.append(root / "scripts/run-performance.py")
    if not overlays or any(not path.is_file() or path.is_symlink() for path in overlays):
        parser.error("current benchmark sources, workload schemas and runner must exist as regular files")
    # Read overlays before exporting, giving the snapshot a fixed set of bytes.
    contents = {str(path.relative_to(root)): (path.read_bytes(), path.stat().st_mode & 0o777) for path in overlays}
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:") as tar:
        members = tar.getmembers()
        for member in members:
            relative = PurePosixPath(member.name)
            if relative.is_absolute() or ".." in relative.parts or not (member.isdir() or member.isfile()):
                parser.error(f"unsupported archive entry: {member.name!r}; only regular files/directories are accepted")
        destination.mkdir(parents=True, exist_ok=False)
        try:
            for member in members:
                target = destination / member.name
                if member.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with tar.extractfile(member) as source, target.open("xb") as output:
                        shutil.copyfileobj(source, output)
                    target.chmod(member.mode & 0o777)
            namespace_migration = migrate_historical_namespace(destination, root)
            # Replace the benchmark source tree, so files removed from the current
            # harness cannot survive from the historical source version.
            benchmark_sources = destination / "benchmarks/src"
            if benchmark_sources.exists():
                shutil.rmtree(benchmark_sources)
            for relative, (data, mode) in contents.items():
                target = destination / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
                target.chmod(mode)
            provenance = dict(createdAt=datetime.now(timezone.utc).isoformat(), productionRevision=resolved,
                              sourceRepository=str(repository), harnessSourceRoot=str(root),
                              gitArchiveSha256=digest(archive),
                              overlaySha256={relative: digest(data) for relative, (data, _) in contents.items()},
                              namespaceMigration=namespace_migration,
                              productionPolicy="Production algorithms come from the exported Git commit. Pre-rename namespaces are migrated when needed, with source hashes retained; benchmarks/src, workload schemas and the runner are overlaid.")
            (destination / "performance-baseline-provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
        except BaseException:
            # This directory was created by this invocation; never remove a pre-existing path.
            shutil.rmtree(destination)
            raise
    print(f"Prepared {destination}\nProduction revision: {resolved}\nOverlaid files: {len(contents)}")
    print("Provenance: " + str(destination / "performance-baseline-provenance.json"))


if __name__ == "__main__":
    main()
