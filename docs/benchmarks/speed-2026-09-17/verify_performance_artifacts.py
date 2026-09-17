#!/usr/bin/env python3
"""Verify completed JMH campaigns and their base-relative source patches.

Uses only Python's standard library and Git. Writes fresh source exports and
reports beneath --output; never compiles or executes benchmark/application code.
Example:
  python3 verify_performance_artifacts.py --repository /path/to/repository \
    --results /path/to/results --output /tmp/fresh-audit \
    --campaign unboxed-pilot=/path/to/unboxed-pilot.patch
Repeat --campaign to audit more completed campaigns.
For an unchanged committed snapshot, use --campaign LABEL without a patch.
To reconstruct an unavailable temporary commit, use --base-revision REV and
repeat --patch LABEL=PATCH for its ordered source patches. Recorded source hashes
remain authoritative; the report distinguishes recorded and reconstruction bases.
"""
import argparse
import gzip
import hashlib
import io
import itertools
import json
import math
from pathlib import Path
import re
import shlex
import subprocess
import tarfile


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def finite(value):
    return isinstance(value, (float, int)) and not isinstance(value, bool) and math.isfinite(value)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f'Duplicate JSON object key: {key}')
        result[key] = value
    return result


def load(path):
    return json.loads(path.read_text(), object_pairs_hook=unique_object)


def expected_keys(command):
    args = shlex.split(command[1])
    regex = args[-1]
    require(regex.startswith('^(?:') and regex.endswith(')$'), 'Unexpected benchmark selection regex')
    methods = [method.replace('[.]', '.') for method in regex[4:-2].split('|')]
    parameters = {}
    for index, value in enumerate(args[:-1]):
        if value == '-p':
            name, choices = args[index + 1].split('=', 1)
            parameters[name] = choices.split(',')
    names = sorted(parameters)
    params = [tuple(zip(names, values)) for values in itertools.product(*(parameters[name] for name in names))]
    return {(method, values) for method in methods for values in params}, args


def metric_observations(metric, forks, iterations, name, uncertainty=False, unit=None, event_minima=None):
    if unit is not None:
        require(metric['scoreUnit'] == unit, f'{name}: unexpected unit')
    require(finite(metric['score']) and metric['score'] >= 0, f'{name}: invalid score')
    if uncertainty:
        require(finite(metric['scoreError']) and metric['scoreError'] >= 0, f'{name}: invalid error')
        interval = metric['scoreConfidence']
        require(len(interval) == 2 and all(finite(v) for v in interval), f'{name}: invalid interval')
        require(interval[0] <= metric['score'] <= interval[1], f'{name}: interval omits score')
    raw = metric['rawData']
    require(len(raw) == forks, f'{name}: missing forks')
    if event_minima is None:
        require(all(len(row) == iterations for row in raw), f'{name}: missing observations')
    else:
        # JMH GCProfiler emits gc.time only when either GC count or time changes.
        # https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/profile/GCProfiler.java#L132
        require(all(minimum <= len(row) <= iterations for minimum, row in zip(event_minima, raw)),
                f'{name}: invalid event observation count')
    require(all(finite(v) and v >= 0 for row in raw for v in row), f'{name}: invalid observation')
    return sum(len(row) for row in raw)


def audit_results(results, manifest):
    runs = []
    totals = {}
    known_warnings = {
        '[error] SLF4J(W): No SLF4J providers were found.',
        '[error] SLF4J(W): Defaulting to no-operation (NOP) logger implementation',
        '[error] SLF4J(W): See https://www.slf4j.org/codes.html#noProviders for further details.',
    }
    for run in manifest['runs']:
        result_path = results / Path(run['result']).name
        log_path = results / Path(run['log']).name
        data = load(result_path)
        require(isinstance(data, list), f'{result_path}: expected result array')
        keys = [(item['benchmark'], tuple(sorted(item.get('params', {}).items()))) for item in data]
        expected, args = expected_keys(run['command'])
        require(len(keys) == len(set(keys)), f'{result_path}: duplicate cases')
        require(set(keys) == expected, f'{result_path}: missing or unexpected cases')
        require(len(keys) == run['expectedCases'], f'{result_path}: expectedCases mismatch')
        timing = manifest['timing']
        observed = {}
        for item in data:
            name = item['benchmark'] + str(item.get('params', {}))
            require(item['mode'] == 'avgt' and item['threads'] == 1, f'{name}: mode/threads mismatch')
            require(item['jvm'] == manifest['java'], f'{name}: JVM mismatch')
            require(item['jmhVersion'] == '1.37', f'{name}: JMH version mismatch')
            require(item['jvmArgs'] == shlex.split(args[args.index('-jvmArgs') + 1]), f'{name}: JVM arguments mismatch')
            for field in ['forks', 'warmupIterations', 'measurementIterations']:
                require(item[field] == timing[field], f'{name}: {field} mismatch')
            for field in ['warmupTime', 'measurementTime']:
                require(item[field].replace(' ', '') == timing[field], f'{name}: {field} mismatch')
            count = metric_observations(item['primaryMetric'], item['forks'], item['measurementIterations'], name, True, 'ns/op')
            observed['primary'] = observed.get('primary', 0) + count
            require({'gc.alloc.rate.norm', 'gc.alloc.rate', 'gc.count'}.issubset(item['secondaryMetrics']),
                    f'{name}: missing GC/allocation metric')
            events = [sum(value > 0 for value in row) for row in item['secondaryMetrics']['gc.count']['rawData']]
            require(not any(events) or 'gc.time' in item['secondaryMetrics'], f'{name}: missing GC event times')
            for key, metric in item['secondaryMetrics'].items():
                count = metric_observations(metric, item['forks'], item['measurementIterations'], name + ':' + key,
                                            key == 'gc.alloc.rate.norm', 'B/op' if key == 'gc.alloc.rate.norm' else None,
                                            events if key == 'gc.time' else None)
                observed[key] = observed.get(key, 0) + count
        log_artifact = log_path if log_path.is_file() else log_path.with_name(log_path.name + '.gz')
        compressed = log_artifact != log_path
        artifact_bytes = log_artifact.read_bytes()
        log_bytes = gzip.decompress(artifact_bytes) if compressed else artifact_bytes
        log = log_bytes.decode('utf-8')
        error_lines = [line for line in log.splitlines() if '[error]' in line]
        require(set(error_lines).issubset(known_warnings), f'{log_path}: unexpected error output')
        require(not re.search(r'<failure>|<forked VM failed', log, re.I), f'{log_path}: failure marker')
        require('Run complete.' in log and '[success] Total time:' in log, f'{log_path}: no completion marker')
        for key, count in observed.items():
            totals[key] = totals.get(key, 0) + count
        runs.append({'name': run['name'], 'cases': len(keys), 'observations': observed,
                     'resultSha256': sha(result_path),
                     'logSha256': hashlib.sha256(log_bytes).hexdigest(),
                     'logArtifact': {'name': log_artifact.name, 'compression': 'gzip' if compressed else 'none',
                                     'sha256': hashlib.sha256(artifact_bytes).hexdigest()},
                     'knownSlf4jWarningLines': len(error_lines)})
    return {'cases': sum(run['cases'] for run in runs), 'observations': totals, 'runs': runs}


def audit_campaign(repository, results, output, label, patches, base_revision=None):
    manifest_path = results / f'{label}.environment.json'
    manifest = load(manifest_path)
    require(manifest['status'] == 'complete', f'{label}: campaign is not complete')
    require(manifest['sourcesUnchanged'] is True, f'{label}: changed sources')
    require(manifest['sourceSha256'] == manifest['sourceSha256After'], f'{label}: before/after source hashes differ')
    destination = output / label
    destination.mkdir()
    source = destination / 'source'
    source.mkdir()
    revision = base_revision or manifest['gitRevision']
    archive = subprocess.check_output(['git', '-C', str(repository), 'archive', revision])
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        members = tar.getmembers()
        require(all(not member.name.startswith('/') and '..' not in Path(member.name).parts
                    and not member.issym() and not member.islnk() for member in members), 'Unsafe archive entry')
        tar.extractall(source)
    for patch in patches:
        subprocess.run(['git', 'apply', '--check', str(patch)], cwd=source, check=True)
        subprocess.run(['git', 'apply', str(patch)], cwd=source, check=True)
    mismatches = []
    for relative, expected in manifest['sourceSha256'].items():
        path = source / relative
        actual = sha(path) if path.is_file() else None
        if actual != expected:
            mismatches.append({'path': relative, 'expected': expected, 'actual': actual})
    require(not mismatches, json.dumps(mismatches, indent=2))
    report = {'campaign': label, 'baseRevision': revision, 'recordedRevision': manifest['gitRevision'],
              'manifestSha256': sha(manifest_path),
              'patchSha256': sha(patches[0]) if len(patches) == 1 else None,
              'patches': [{'name': patch.name, 'sha256': sha(patch)} for patch in patches],
              'sourceSnapshot': 'commit-plus-patch' if patches else 'commit',
              'verifiedSourceFiles': len(manifest['sourceSha256']),
              'sourceMismatches': mismatches, 'sourcesUnchanged': True,
              'results': audit_results(results, manifest)}
    (destination / 'audit.json').write_text(json.dumps(report, indent=2) + '\n')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', type=Path, required=True)
    parser.add_argument('--results', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True, help='Fresh directory for source exports and audits')
    parser.add_argument('--campaign', action='append', required=True, metavar='LABEL[=PATCH]')
    parser.add_argument('--base-revision', help='Optional reconstruction base instead of the recorded temporary commit')
    parser.add_argument('--patch', action='append', default=[], metavar='LABEL=PATCH',
                        help='Append an ordered patch to one selected campaign; repeatable')
    args = parser.parse_args()
    require(not args.output.exists(), 'Choose a fresh output directory')
    args.output.mkdir(parents=True)
    extra_patches = {}
    for value in args.patch:
        label, separator, patch = value.partition('=')
        require(separator and patch, '--patch expects LABEL=PATCH')
        extra_patches.setdefault(label, []).append(Path(patch).resolve())
    selected_labels = {value.partition('=')[0] for value in args.campaign}
    require(set(extra_patches).issubset(selected_labels), '--patch refers to an unselected campaign')
    reports = []
    for campaign in args.campaign:
        label, separator, patch = campaign.partition('=')
        require(label and Path(label).name == label, 'Campaign labels must be plain filenames')
        require(not separator or patch, 'Use LABEL without = for an unchanged committed snapshot')
        patches = ([Path(patch).resolve()] if separator else []) + extra_patches.get(label, [])
        reports.append(audit_campaign(args.repository.resolve(), args.results.resolve(), args.output.resolve(), label,
                                      patches, args.base_revision))
    summary = {report['campaign']: {'sourceFiles': report['verifiedSourceFiles'], 'cases': report['results']['cases'],
                                  'observations': report['results']['observations'], 'patchSha256': report['patchSha256']}
               for report in reports}
    (args.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
