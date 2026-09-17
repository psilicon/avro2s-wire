#!/usr/bin/env python3
"""Audit explicit JMH result files; never mix pilots/confirmations into a run.

Pure Python standard library; writes only beneath --output. With no --after,
renders the fixed historical deficit inventory and a pending report skeleton.
"""
import argparse
import csv
import hashlib
import json
import math
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
CLASSES = ('ComparisonBenchmark', 'NestedComparisonBenchmark',
           'LogicalComparisonBenchmark', 'DecimalComparisonBenchmark')
SUFFIXES = ('Comparison', 'NestedComparison', 'LogicalComparison', 'DecimalComparison')
ENGINES = ('javaGeneric', 'javaSpecific', 'javaCustom')
PROFILE_ORDER = ('ints-small', 'ints-wide', 'longs-mixed', 'string-ascii',
                 'string-unicode', 'bytes', 'collections-empty', 'collections-full',
                 'enum-fixed', 'numerics', 'nested', 'logical', 'decimal')
EXPECTED_DEFICITS = {
    ('ints-small', 'Write'), ('ints-wide', 'Read'), ('ints-wide', 'Write'),
    ('longs-mixed', 'Read'), ('longs-mixed', 'Write'), ('string-ascii', 'Read'),
    ('string-ascii', 'Write'), ('string-unicode', 'Read'), ('string-unicode', 'Write'),
    ('collections-full', 'Read'), ('collections-full', 'Write'),
    ('numerics', 'Write'), ('logical', 'Read'),
}
PROTOCOL_KEYS = ('jmhVersion', 'jdkVersion', 'vmName', 'vmVersion', 'jvmArgs',
                 'mode', 'threads', 'forks', 'warmupIterations', 'warmupTime',
                 'measurementIterations', 'measurementTime')


def key(row):
    cls, method = row['benchmark'].rsplit('.', 2)[-2:]
    return cls, tuple(sorted(row.get('params', {}).items())), method


def profile(k):
    cls, params, _ = k
    return dict(params).get('profile', {
        'NestedComparisonBenchmark': 'nested',
        'LogicalComparisonBenchmark': 'logical',
        'DecimalComparisonBenchmark': 'decimal',
    }.get(cls, ', '.join(f'{a}={b}' for a, b in params) or 'default'))


def order(k):
    p = profile(k)
    return (PROFILE_ORDER.index(p) if p in PROFILE_ORDER else 99, p, k)


def metric(row, name='primaryMetric'):
    if row is None:
        return None
    return row.get(name) if name == 'primaryMetric' else row.get('secondaryMetrics', {}).get(name)


def score(row, name='primaryMetric'):
    m = metric(row, name)
    return m.get('score') if m else None


def finite(value):
    return isinstance(value, (float, int)) and math.isfinite(value)


def number(value, places=1):
    return f'{value:,.{places}f}' if finite(value) else 'pending'


def time_cell(row):
    m = metric(row)
    return f"{number(m['score'])} ± {number(m['scoreError'])}" if m else 'pending'


def alloc(row):
    return score(row, 'gc.alloc.rate.norm')


def ratio(a, b):
    return a / b if finite(a) and finite(b) and b > 0 else None


def interval_relation(a, b):
    """Descriptive interval overlap, not a two-sample significance test."""
    if a is None or b is None:
        return 'pending'
    ca, cb = (x['primaryMetric']['scoreConfidence'] for x in (a, b))
    if ca[1] < cb[0]:
        return 'lower, separate CIs'
    if ca[0] > cb[1]:
        return 'higher, separate CIs'
    return 'overlapping CIs'


def table(headers, rows):
    return '\n'.join(['| ' + ' | '.join(headers) + ' |',
                      '| ' + ' | '.join('---' for _ in headers) + ' |'] +
                     ['| ' + ' | '.join(str(x).replace('|', '\\|') for x in r) + ' |' for r in rows])


def load_group(paths, label, audit, warnings):
    rows = {}
    for path in paths:
        path = path.resolve()
        data = path.read_bytes()
        values = json.loads(data)
        if not isinstance(values, list) or not values:
            raise ValueError(f'{path}: not a nonempty JMH result array (a run may still be active)')
        protocols = Counter()
        for row in values:
            k = key(row)
            if k in rows:
                raise ValueError(f'{label}: duplicate result {k}; pass only one run per case')
            m = row['primaryMetric']
            if row['mode'] != 'avgt' or m['scoreUnit'] != 'ns/op':
                raise ValueError(f'{path}: unsupported units/mode for {k}')
            if not finite(m['score']) or m['score'] <= 0 or not finite(m['scoreError']):
                raise ValueError(f'{path}: invalid score/error for {k}')
            ci = m['scoreConfidence']
            if len(ci) != 2 or not all(map(finite, ci)) or not ci[0] <= m['score'] <= ci[1]:
                raise ValueError(f'{path}: invalid confidence bounds for {k}')
            raw = m.get('rawData', [])
            if len(raw) != row['forks'] or any(len(f) != row['measurementIterations'] for f in raw):
                raise ValueError(f'{path}: incomplete fork/iteration samples for {k}')
            if any(not finite(v) for f in raw for v in f):
                raise ValueError(f'{path}: nonfinite raw sample for {k}')
            if alloc(row) is None:
                warnings.append(f'{label}: {k} has no gc.alloc.rate.norm metric.')
            elif metric(row, 'gc.alloc.rate.norm').get('scoreUnit') != 'B/op':
                raise ValueError(f'{path}: unsupported allocation units for {k}')
            row = dict(row, _source=str(path))
            rows[k] = row
            p = {k: row.get(k) for k in PROTOCOL_KEYS}
            protocols[json.dumps(p, sort_keys=True)] += 1
        item = {'group': label, 'path': str(path), 'sha256': hashlib.sha256(data).hexdigest(),
                'cases': len(values), 'protocols': [dict(json.loads(p), cases=n) for p, n in protocols.items()]}
        environment = path.with_name(path.stem.rsplit('-', 1)[0] + '.environment.json')
        if environment.exists():
            metadata_bytes = environment.read_bytes()
            metadata = json.loads(metadata_bytes)
            item['environment'] = {
                'path': str(environment), 'sha256': hashlib.sha256(metadata_bytes).hexdigest(),
                **{field: metadata.get(field) for field in (
                    'status', 'gitRevision', 'sourceRoot', 'startedAt', 'finishedAt',
                    'sourcesUnchanged', 'platform', 'machine', 'javaVersion')},
            }
            implementation = {name: digest for name, digest in metadata.get('sourceSha256', {}).items()
                              if name.startswith(('runtime/src/main/', 'compiler/src/main/',
                                                  'java-interop/src/main/', 'resolution/src/main/'))}
            if implementation:
                item['environment']['implementationFiles'] = len(implementation)
                item['environment']['implementationSha256'] = hashlib.sha256(
                    json.dumps(implementation, sort_keys=True).encode()).hexdigest()
            if metadata.get('status') != 'complete' or metadata.get('sourcesUnchanged') is not True:
                warnings.append(f'{environment.name}: run is not recorded complete with unchanged source hashes.')
            matching = [run for run in metadata.get('runs', [])
                        if Path(run['result']).name == path.name]
            if len(matching) == 1 and matching[0].get('expectedCases') != len(values):
                raise ValueError(f'{path}: case count differs from recorded expectedCases')
        else:
            warnings.append(f'{path.name}: no adjacent environment manifest was found.')
        audit.append(item)
    return rows


def select(rows, k, method):
    return rows.get((k[0], k[1], method))


def best_java(rows, k, op):
    candidates = [(name, select(rows, k, name + op)) for name in ENGINES]
    candidates = [(name, row) for name, row in candidates if row is not None]
    return min(candidates, key=lambda item: score(item[1])) if candidates else (None, None)


def native_keys(rows):
    return sorted((k for k in rows if k[0] in CLASSES and k[2] in ('nativeRead', 'nativeWrite')), key=order)


def delta(before, after):
    r = ratio(score(after), score(before))
    return f'{(r - 1) * 100:+.1f}%' if r is not None else 'pending'


def comparison_rows(historical, before, after, keys):
    output = []
    for k in keys:
        op = k[2][6:]
        b, a = before.get(k), after.get(k)
        java_name, java = best_java(after, k, op)
        primitives = select(after, k, 'javaPrimitives' + op)
        output.append([profile(k), op.lower(), time_cell(b), time_cell(a), delta(b, a),
                       interval_relation(a, b), f'{number(alloc(b))} → {number(alloc(a))}',
                       java_name or 'pending', time_cell(java), number(ratio(score(a), score(java)), 3),
                       interval_relation(a, java), time_cell(primitives),
                       number(ratio(score(a), score(primitives)), 3)])
    return output


def flat_rows(groups):
    for label, rows in groups:
        for k, row in sorted(rows.items(), key=lambda x: order(x[0])):
            m = row['primaryMetric']
            am = metric(row, 'gc.alloc.rate.norm') or {}
            yield dict(group=label, benchmark=row['benchmark'], profile=profile(k),
                       params=json.dumps(dict(k[1]), sort_keys=True), method=k[2],
                       ns_per_op=m['score'], ns_error=m['scoreError'],
                       ns_ci_low=m['scoreConfidence'][0], ns_ci_high=m['scoreConfidence'][1],
                       bytes_per_op=am.get('score'), bytes_error=am.get('scoreError'),
                       bytes_ci_low=am.get('scoreConfidence', [None, None])[0],
                       bytes_ci_high=am.get('scoreConfidence', [None, None])[1],
                       forks=row['forks'], iterations=row['measurementIterations'], source=row['_source'])


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--historical-dir', type=Path, required=True,
                   help='Directory containing the four original comparison-*.json files.')
    p.add_argument('--before', nargs='+', type=Path, required=True,
                   help='Explicit fresh native-before comparison JSON files (26 cases).')
    p.add_argument('--after', nargs='+', type=Path, default=[],
                   help='Explicit final comparison JSON files; no pilot/confirmation mixing.')
    p.add_argument('--extra', nargs='+', type=Path, default=[],
                   help='Final decoded-String, string matrix, Trade, API and evolution runs.')
    p.add_argument('--before-extra', nargs='+', type=Path, default=[],
                   help='Earlier extra controls; protocol mismatches are flagged.')
    p.add_argument('--confirm-before', nargs='+', type=Path, default=[],
                   help='Separate matched-protocol before confirmations; never overwrite earlier pilots.')
    p.add_argument('--confirm-after', nargs='+', type=Path, default=[],
                   help='Separate longer final-implementation confirmations; never replace broad-run rows.')
    p.add_argument('--supplementary-control', nargs='+', type=Path, default=[],
                   help='Exploratory string-control run: show only new supplementary-* cases, '
                        'retain other cases in CSV without merging them into final measurements.')
    p.add_argument('--output', type=Path, default=HERE / 'rendered')
    p.add_argument('--report-name', default='report.md', help='Report filename within --output.')
    p.add_argument('--audit-name', default='audit.json', help='Audit filename within --output.')
    p.add_argument('--require-complete', action='store_true',
                   help='Require all 148 final comparison cases, exactly matching historical keys.')
    args = p.parse_args()
    for name in (args.report_name, args.audit_name):
        if Path(name).name != name or name in ('', '.', '..'):
            p.error('Report and audit names must be plain filenames within --output.')
    audit, warnings = [], []
    h = load_group([args.historical_dir / f'comparison-{s}.json' for s in SUFFIXES], 'historical', audit, warnings)
    b = load_group(args.before, 'fresh-before', audit, warnings)
    a = load_group(args.after, 'final-after', audit, warnings)
    e = load_group(args.extra, 'final-extra', audit, warnings)
    eb = load_group(args.before_extra, 'before-extra', audit, warnings)
    cb = load_group(args.confirm_before, 'confirmed-before', audit, warnings)
    ca = load_group(args.confirm_after, 'confirmed-after', audit, warnings)
    sc = load_group(args.supplementary_control, 'supplementary-control-pilot', audit, warnings)
    if any(k[0] not in CLASSES for k in a):
        raise ValueError('--after accepts comparison-family benchmarks only; use --extra for other suites')
    if set(a) & set(e):
        raise ValueError('Same result key appears in --after and --extra')
    keys = native_keys(h)
    if len(h) != 148 or len(keys) != 26 or set(b) != set(keys):
        raise ValueError('Historical/fresh-before coverage differs from the expected 148/26 original cases')
    if set(a) - set(h):
        raise ValueError('Final comparison has unexpected keys; verify workload labels')
    if args.require_complete and set(a) != set(h):
        raise ValueError(f'Final comparison incomplete: {len(a)}/148 cases')
    missing = sorted(set(h) - set(a))
    if missing:
        warnings.append(f'Final comparison is incomplete: {len(a)}/148 cases; missing results remain pending.')
    deficits = [k for k in keys if score(h[k]) > score(best_java(h, k, k[2][6:])[1])]
    if {(profile(k), k[2][6:]) for k in deficits} != EXPECTED_DEFICITS:
        raise ValueError('Historical deficit inventory changed; do not silently redefine the target set')
    for label, before, after in [('comparison', b, a), ('extra controls', eb, e),
                                 ('before confirmations', cb, e)]:
        mismatches = []
        for k in set(before) & set(after):
            diff = [field for field in PROTOCOL_KEYS if before[k].get(field) != after[k].get(field)]
            if diff:
                mismatches.append((k, diff))
        if mismatches:
            warnings.append(f'{label}: {len(mismatches)} paired cases have different JMH/JVM protocols; '
                            f'changed fields: {", ".join(sorted({f for _, fields in mismatches for f in fields}))}. '
                            'Treat changes as descriptive, not independently confirmed improvements.')

    lines = ['# Native performance comparison — measured report inputs', '',
             'This generated report keeps the original target set fixed, uses the fresh native before run '
             'for change estimates, and ranks **current** Java datum implementations only when the final '
             'comparison is supplied. Historical Java measurements are context, not a concurrent control.', '',
             f'Coverage: historical {len(h)}/148; fresh native before {len(b)}/26; '
             f'final comparison {len(a)}/148; final extra controls {len(e)}; separate before '
             f'confirmations {len(cb)}; separate after confirmations {len(ca)}; '
             f'supplementary-control pilot {len(sc)} recorded '
             f'({sum(profile(k).startswith("supplementary-") for k in sc)} new cases).', '',
             'Times are ns/op (lower is better); allocations are B/op. `±` is the error reported by JMH '
             '(its default 99.9% confidence interval). Ratios are ratios of means, without ratio confidence '
             'intervals. Native/Java greater than 1 means native is slower. Selecting the lowest Java mean '
             'does not prove that Java implementation is statistically faster than its alternatives. '
             'Separate/overlapping CIs are descriptive; they are not a paired significance test.', '']
    if warnings:
        lines += ['## Input and interpretation cautions', ''] + [f'- {w}' for w in warnings] + ['']
    lines += ['## Original 13 deficits (historical context only)', '',
              'Frozen from the original broad comparison. The best Java datum implementation is selected '
              'from generic, specific and generated custom coders where supported; JavaPrimitives is a '
              'separate same-generated-model control.', '']
    historical_rows = []
    for k in deficits:
        op = k[2][6:]
        name, java = best_java(h, k, op)
        jp = select(h, k, 'javaPrimitives' + op)
        historical_rows.append([profile(k), op.lower(), time_cell(h[k]), name, time_cell(java),
                                number(ratio(score(h[k]), score(java)), 3), time_cell(jp),
                                number(ratio(score(h[k]), score(jp)), 3),
                                f'{number(alloc(h[k]))} / {number(alloc(java))} / {number(alloc(jp))}'])
    lines += [table(['Workload', 'Op', 'Old native', 'Old fastest Java', 'Old Java', 'N/J',
                     'Old JavaPrimitives', 'N/JP', 'B/op native / Java / JP'], historical_rows), '',
              '## All 26 native before/after cases and current Java controls', '',
              'The before column uses the fresh before run, not the older historical run. This table '
              'includes previously winning cases so regressions cannot disappear from a targeted report.', '',
              table(['Workload', 'Op', 'Fresh before', 'Final native', 'Time Δ', 'After vs before CI',
                     'Native B/op before → after', 'Current fastest Java', 'Current Java', 'N/J',
                     'Native vs Java CI', 'Current JavaPrimitives', 'N/JP'],
                    comparison_rows(h, b, a, keys)), '',
              '## Original deficit status using current Java', '']
    status_rows = []
    closed, remain, uncertain = [], [], []
    for k in deficits:
        op = k[2][6:]
        name, java = best_java(a, k, op)
        current = a.get(k)
        r = ratio(score(current), score(java))
        state = 'pending' if r is None else ('native lower mean' if r <= 1 else 'native higher mean')
        relation = interval_relation(current, java)
        if r is not None:
            (closed if r <= 1 else remain).append([profile(k), op])
            if relation == 'overlapping CIs':
                uncertain.append([profile(k), op])
        status_rows.append([profile(k), op.lower(), state, name or 'pending', number(r, 3), relation,
                            f'{number(alloc(current))} / {number(alloc(java))}'])
    lines += [table(['Workload', 'Op', 'Mean comparison', 'Current Java', 'N/J', 'CI comparison',
                     'B/op native / Java'], status_rows), '',
              f'Available original targets: {len(closed)} native lower/equal means; {len(remain)} native '
              f'higher means; {len(uncertain)} have overlapping native/selected-Java confidence intervals. '
              f'{13 - len(closed) - len(remain)} remain pending. These counts are descriptive, not a claim '
              'that every mean difference is repeatable.', '']
    new_deficits = []
    for k in keys:
        if k in deficits:
            continue
        name, java = best_java(a, k, k[2][6:])
        if a.get(k) is not None and java is not None and score(a[k]) > score(java):
            new_deficits.append([profile(k), k[2][6:].lower(), name,
                                 number(ratio(score(a[k]), score(java)), 3), interval_relation(a[k], java)])
    lines += ['### Previously winning cases with a new higher native mean', '',
              table(['Workload', 'Op', 'Current Java', 'N/J', 'CI comparison'], new_deficits)
              if new_deficits else ('None among the available final cases.' if a else 'Pending final comparison.'), '']

    decoded = {k: row for k, row in e.items() if k[0] == 'DecodedStringBenchmark'}
    lines += ['## Equivalently materialized String controls', '',
              'Default Java datum reads can retain UTF-8 bytes in Utf8; native/JavaPrimitives/avro2s '
              'produce decoded java.lang.String. The very large default Unicode read difference therefore '
              '**is not evidence that Java decoded the same String that much faster**. These additional '
              'readers set avro.java.string=String recursively, including map keys, and correctness setup '
              'asserts that returned strings are materialized. They still differ in mutable Java versus '
              'immutable Scala records/collections and in input-validation policy.', '']
    decoded_rows = []
    for k in sorted((k for k in decoded if k[2] == 'nativeRead'), key=order):
        for method in ('javaGenericStringRead', 'javaSpecificStringRead', 'javaPrimitivesRead', 'avro2sRead'):
            other = select(decoded, k, method)
            decoded_rows.append([profile(k), time_cell(decoded[k]), method, time_cell(other),
                                 number(ratio(score(decoded[k]), score(other)), 3),
                                 interval_relation(decoded[k], other),
                                 f'{number(alloc(decoded[k]))} / {number(alloc(other))}'])
    lines += [table(['Workload', 'Native', 'Control', 'Control time', 'N/control', 'CI comparison',
                     'B/op native / control'], decoded_rows) if decoded_rows else 'Pending decoded-String controls.', '']

    lines += ['## Trade same-generated-model controls', '',
              'This final Trade run measures native and JavaPrimitives only. It does not remeasure '
              'the Java datum or avro2s implementations, so historical results from those methods '
              'must not be presented as current concurrent controls.', '']
    trade_rows = []
    for k in sorted((k for k in e if k[0] == 'TradeBenchmark' and k[2] in ('nativeRead', 'nativeWrite')),
                    key=lambda k: (int(dict(k[1])['collectionSize']), k[2])):
        control = select(e, k, 'javaPrimitives' + k[2][6:])
        trade_rows.append([dict(k[1])['collectionSize'], k[2][6:].lower(), time_cell(e[k]),
                           time_cell(control), number(ratio(score(e[k]), score(control)), 3),
                           interval_relation(e[k], control),
                           f'{number(alloc(e[k]))} / {number(alloc(control))}'])
    lines += [table(['Size', 'Operation', 'Native', 'JavaPrimitives', 'N/JP', 'CI comparison',
                     'B/op native / JP'], trade_rows) if trade_rows else 'Pending Trade controls.', '',
              '## Matched public API before/after controls', '',
              'These encode/decode methods include the public convenience-API costs. Encode allocates '
              'an output buffer and produces an owned result array; decode checks trailing input. '
              'Compare the same method before and after, not these times against direct read/write.', '']
    api_rows = []
    for k in sorted({k for k in e.keys() | eb.keys() if k[2] in ('nativeEncode', 'nativeDecode')}):
        previous, current = eb.get(k), e.get(k)
        api_rows.append([k[0], profile(k), k[2], time_cell(previous), time_cell(current),
                         delta(previous, current), interval_relation(current, previous),
                         f'{number(alloc(previous))} → {number(alloc(current))}'])
    lines += [table(['Suite', 'Parameters', 'Method', 'Before', 'After', 'Time Δ', 'After vs before CI',
                     'B/op before → after'], api_rows) if api_rows else 'Pending matched API controls.', '',
              '## Schema-evolution controls', '',
              'Warm resolving reads reuse the compiled resolution plan and create fresh values. '
              'Plan construction is timed separately. Same-schema reads produce the old model '
              'including its discarded byte field, while resolved reads skip that field and produce '
              'the new model; those two operations are not equivalent results.', '']
    evolution_rows = [[profile(k), k[2], time_cell(row), number(alloc(row))]
                      for k, row in sorted(e.items()) if k[0] == 'EvolutionBenchmark']
    lines += [table(['Discarded-byte parameter', 'Method', 'ns/op ± CI', 'B/op'], evolution_rows)
              if evolution_rows else 'Pending evolution controls.', '']
    for k in sorted(k for k in e if k[0] == 'EvolutionBenchmark' and k[2] == 'nativeResolved'):
        java = select(e, k, 'javaResolved')
        lines += [f'{profile(k)}: native resolved/Java resolved '
                  f'{number(ratio(score(e[k]), score(java)), 3)}×; '
                  f'{interval_relation(e[k], java)}. Java returns GenericRecord/Utf8 and native '
                  'returns the immutable generated model.', '']
    lines += [
              '## Separate final-implementation confirmations', '',
              'Longer confirmations are reported alongside the broad run. They never replace its '
              'rows, alter the original target set or mix samples from different runs.', '']
    confirm_after_rows = []
    for k, row in sorted(ca.items(), key=lambda item: order(item[0])):
        main = a.get(k, e.get(k))
        confirm_after_rows.append([profile(k), k[2], time_cell(main), time_cell(row),
                                   f'{number(alloc(main))} / {number(alloc(row))}'])
    lines += [table(['Workload', 'Method', 'Broad final run', 'Longer confirmation',
                     'B/op broad / confirmation'], confirm_after_rows)
              if confirm_after_rows else 'Pending separate final confirmations.', '']
    for k in sorted((k for k in ca if k[2] in ('nativeRead', 'nativeWrite')), key=order):
        name, java = best_java(ca, k, k[2][6:])
        if java is not None:
            confirmation_ratio = ratio(score(ca[k]), score(java))
            lines += [f'Confirmation {profile(k)} {k[2][6:].lower()}: native/{name} '
                      f'{number(confirmation_ratio, 3)}×; '
                      f'{interval_relation(ca[k], java)}.', '']
            broad_native = a.get(k, e.get(k))
            broad_java = select(a, k, name + k[2][6:]) or select(e, k, name + k[2][6:])
            broad_ratio = ratio(score(broad_native), score(broad_java))
            if broad_ratio is not None and (broad_ratio > 1) != (confirmation_ratio > 1):
                lines += [f'**Ranking changes across runs for {profile(k)} {k[2][6:].lower()}.** '
                          f'The broad-run native/{name} ratio is {number(broad_ratio, 3)}×, '
                          f'while the separate confirmation ratio is {number(confirmation_ratio, 3)}×. '
                          'The broad-run target counts remain descriptive of that run; this workload\'s '
                          'cross-run ranking is unresolved. Retain both observations rather than '
                          'selecting the favorable run. Narrow within-run confidence intervals do '
                          'not account for this cross-run variation.', '']

    lines += ['## Additional final controls', '',
              'String matrix, Trade, public convenience APIs and evolution are distinct experiments. '
              'Never compare encode/decode (new output buffers, final owned copy or trailing-data check) '
              'with reused-buffer write/direct read as though they measured the same operation. '
              'Before-extra changes are descriptive when protocol differs, particularly the one-fork '
              '300 ms before-string pilot.', '']
    extra_rows = []
    for k, row in sorted(e.items(), key=lambda item: item[0]):
        if k[0] == 'DecodedStringBenchmark':
            continue
        previous = eb.get(k)
        extra_rows.append([k[0], profile(k), k[2], time_cell(previous) if previous else 'not measured',
                           time_cell(row), delta(previous, row) if previous else '—', number(alloc(row))])
    lines += [table(['Suite', 'Parameters', 'Method', 'Earlier control', 'Final', 'Time Δ', 'Final B/op'], extra_rows)
              if extra_rows else 'Pending additional controls.', '',
              '### Matched-protocol before confirmations', '',
              'These are separate measurements of the earlier implementation. They do not replace '
              'the pilot above. Comparing a confirmed before run with the final after run supports '
              'a more credible assessment of the pure-emoji write tradeoff.', '']
    confirmed_rows = []
    for k, old in sorted(cb.items()):
        current, pilot = e.get(k), eb.get(k)
        confirmed_rows.append([profile(k), k[2], time_cell(pilot), time_cell(old), time_cell(current),
                               delta(old, current), interval_relation(current, old),
                               f'{number(alloc(old))} / {number(alloc(current))}'])
    lines += [table(['Workload', 'Method', 'Old pilot', 'Confirmed old', 'Final', 'Confirmed time Δ',
                     'Final vs confirmed old CI', 'B/op old / final'], confirmed_rows)
              if confirmed_rows else 'No separate before confirmations supplied.', '',
              '### New supplementary-string exploratory controls', '',
              'Only the four newly introduced supplementary-* workloads are shown here. The control '
              'run also repeated existing string workloads, but those measurements are not merged '
              'with or substituted for the final string run. These are one-fork exploratory means; '
              'there is no original implementation baseline for the new workloads. The attempted '
              'direct supplementary encoder was rejected after it slowed mixed-string workloads; '
              'the retained implementation continues using the JDK path.', '']
    supplementary_rows = [[profile(k), k[2], time_cell(row), time_cell(e.get(k)),
                           number(alloc(row)), number(alloc(e.get(k))), row['forks'],
                           row['measurementIterations']]
                          for k, row in sorted(sc.items()) if profile(k).startswith('supplementary-')]
    lines += [table(['Workload', 'Method', 'Pilot ns/op ± CI', 'Final control ns/op ± CI',
                     'Pilot B/op', 'Final control B/op', 'Pilot forks', 'Pilot iterations'], supplementary_rows)
              if supplementary_rows else 'No supplementary-string control supplied.', '',
              '## All final comparison means, uncertainty and allocations', '',
              table(['Workload', 'Method', 'ns/op ± CI', 'Time CI bounds', 'B/op ± CI'],
                    [[profile(k), k[2], time_cell(row),
                      f"[{number(metric(row)['scoreConfidence'][0])}, {number(metric(row)['scoreConfidence'][1])}]",
                      f"{number(alloc(row))} ± {number((metric(row, 'gc.alloc.rate.norm') or {}).get('scoreError'))}"]
                     for k, row in sorted(a.items(), key=lambda item: order(item[0]))])
              if a else 'Pending all 148 final comparison cases.', '',
              '## Model and measurement caveats', '',
              '- Read results and decoders are fresh across timed paths. No Java record-reuse baseline is '
              'conflated with an immutable native read. Writers reuse buffers/encoders across paths; Java '
              'flush is timed and all write methods return size without a final array copy.',
              '- JavaPrimitives uses the same generated immutable Scala models and codec with Java '
              'primitive I/O. It controls model construction, but not identical UTF-8 validation, limits '
              'or intermediate byte-copy policy.',
              '- Generic/specific/custom Java use mutable Java records and collections; native uses '
              'immutable Scala records, Vector, Map and owned Bytes. avro2s uses the genuine generated '
              'Scala SpecificRecord representation, including List conversions. Allocation differences '
              'therefore include representation choices as well as I/O.',
              '- Java String fields and map keys are prepared as java.lang.String for all writers '
              'outside timing. The default Java read representation is Utf8; decoded-String controls '
              'are reported separately.',
              '- Standard Java, generic Java and avro2s enable the default fast reader. Generated custom '
              'coders explicitly enable custom coders and disable the fast reader so customDecode is '
              'actually called; untimed probes check dispatch. Not every schema supports custom coders.',
              '- Native rejects malformed UTF-8/varints and enforces resource limits. The timed workloads '
              'are valid data. Faster results do not justify weakening those checks.',
              '- ints-small deliberately uses boxed-Integer cache values; ints-wide and Trade values '
              'are outside that cache. Interpret allocation numbers per workload rather than assuming '
              'all integer collections allocate alike.',
              '- Logical baselines construct equivalent semantic values; decimals retain exact '
              'precision/scale. Native Scala BigDecimal and Java BigDecimal still have representation '
              'differences. Very wide CIs (including a noisy historical decimal adapter result) warrant '
              'caution rather than a headline ratio.',
              '- JMH intervals describe these forks and iterations on one host. They do not establish '
              'performance across CPUs, JVM versions, schema shapes or concurrent applications.', '',
              '## Input provenance and actual protocols', '']
    for item in audit:
        lines += [f"### {item['group']}: {Path(item['path']).name}", '',
                  f"Source: `{item['path']}`; SHA-256 `{item['sha256']}`; {item['cases']} cases.", '']
        if item.get('environment'):
            lines += ['Environment manifest:', '', '```json',
                      json.dumps(item['environment'], indent=2), '```', '']
        for protocol in item['protocols']:
            lines += ['```json', json.dumps(protocol, indent=2), '```', '']
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / args.report_name).write_text('\n'.join(lines))
    data = list(flat_rows([('historical', h), ('fresh-before', b), ('final-after', a),
                           ('before-extra', eb), ('confirmed-before', cb),
                           ('confirmed-after', ca), ('final-extra', e), ('supplementary-control-pilot', sc)]))
    with (args.output / 'measurements.csv').open('w', newline='') as file:
        writer = csv.DictWriter(file, fieldnames=list(data[0]), lineterminator='\n')
        writer.writeheader()
        writer.writerows(data)
    (args.output / args.audit_name).write_text(json.dumps(dict(inputs=audit, warnings=warnings,
        coverage=dict(historical=len(h), freshBefore=len(b), finalComparison=len(a), finalExtra=len(e),
                      confirmedBefore=len(cb), confirmedAfter=len(ca), supplementaryControlPilot=len(sc)),
        originalDeficits=[dict(profile=profile(k), operation=k[2][6:]) for k in deficits],
        currentMeanLower=closed, currentMeanHigher=remain, overlappingCIs=uncertain,
        missingFinalKeys=missing), indent=2) + '\n')
    print(f'Wrote {args.output}/{args.report_name}, measurements.csv and {args.audit_name}')


if __name__ == '__main__':
    main()
