#!/usr/bin/env python3
"""Render one explicitly named benchmark campaign from its completed manifests.

No build or source edits. Partial renders require --allow-pending; unfinished
bundles are omitted as a whole and listed, never silently sampled or merged.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--historical-dir', type=Path, required=True)
    parser.add_argument('--results-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--allow-pending', action='store_true')
    args = parser.parse_args()
    argv = [sys.executable, str(Path(__file__).with_name('analyze.py')),
            '--historical-dir', str(args.historical_dir.resolve()),
            '--output', str(args.output.resolve()), '--report-name', 'tables.md',
            '--audit-name', 'analysis-audit.json', '--require-complete']
    pending = []

    def bundle(label, required=False):
        manifest = args.results_dir / f'{label}.environment.json'
        if not manifest.exists():
            if required or not args.allow_pending:
                raise ValueError(f'Missing campaign manifest: {manifest}')
            pending.append(label)
            return []
        metadata = json.loads(manifest.read_text())
        if metadata.get('status') != 'complete' or metadata.get('sourcesUnchanged') is not True:
            if required or not args.allow_pending:
                raise ValueError(f'Incomplete campaign or changed sources: {manifest}')
            pending.append(label)
            return []
        paths = [args.results_dir / Path(run['result']).name for run in metadata['runs']]
        if not paths or not all(path.is_file() and path.stat().st_size for path in paths):
            raise ValueError(f'Completed manifest has missing or empty results: {manifest}')
        return [str(path.resolve()) for path in paths]

    for option, labels, required in [
        ('--before', ['before'], True),
        ('--after', ['after'], True),
        ('--extra', ['after-strings', 'decoded-strings', 'after-trade', 'after-api',
                     'after-evolution', 'after-supplementary'], False),
        ('--before-extra', ['before-strings', 'before-api'], False),
        ('--confirm-before', ['before-emoji-confirm'], False),
        ('--confirm-after', ['confirm-small-ints'], False),
        ('--supplementary-control', ['supplementary-control'], False),
    ]:
        paths = [path for label in labels for path in bundle(label, required)]
        if paths:
            argv.extend([option, *paths])
    subprocess.run(argv, check=True)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'render-invocation.json').write_text(json.dumps(
        {'argv': argv, 'pendingRunBundles': pending}, indent=2) + '\n')
    audit_path = args.output / 'analysis-audit.json'
    audit = json.loads(audit_path.read_text())
    audit['pendingRunBundles'] = pending
    audit_path.write_text(json.dumps(audit, indent=2) + '\n')
    if pending:
        report = args.output / 'tables.md'
        report.write_text('> Additional measurements pending: ' + ', '.join(pending) + '.\n\n' + report.read_text())
        print('Pending bundles: ' + ', '.join(pending))


if __name__ == '__main__':
    main()
