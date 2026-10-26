#!/usr/bin/env python3
"""Compare archived V1 and built current version without changing this checkout or Git config.

Prerequisites: ./gradlew classes, cached Kotlin 2.4.20 compiler, matching local SDK.
Example: python3 tools/benchmark_blame.py --ide '/path/IDE.app/Contents' --java-home '/path/jbr/Contents/Home'
Results are observations, not a wall-clock CI threshold.
"""
import argparse
import glob
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ide', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--baseline', default='14fd064')
    parser.add_argument('--runs', type=int, default=3)
    parser.add_argument('--label', default='V1.2')
    parser.add_argument('--report', default='v12-benchmark.json')
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    build = root / 'build'
    build.mkdir(exist_ok=True)
    env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
    java, javac = [str(args.java_home / 'bin' / name) for name in ('java', 'javac')]

    def cached(group, artifact, version):
        matches = glob.glob(str(Path.home() / '.gradle/caches/modules-2/files-2.1' / group / artifact / version / '*' / f'{artifact}-{version}.jar'))
        if not matches:
            raise SystemExit(f'Missing cached compiler dependency: {artifact}:{version}')
        return matches[0]

    stdlib = cached('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.20')
    compiler = [cached('org.jetbrains.kotlin', name, '2.4.20') for name in ('kotlin-compiler-embeddable', 'kotlin-script-runtime')]
    compiler += [stdlib, cached('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
                 cached('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
                 cached('org.jetbrains', 'annotations', '13.0')]
    main_classes = build / 'classes/kotlin/main'
    if not (main_classes / 'com/github/ten951/gitplus/blame/BlameProvider.class').exists():
        raise SystemExit('Run ./gradlew classes first')
    # The exact same dependency classpath is used for both implementations.
    deps = os.pathsep.join([stdlib] + sorted(str(p) for p in (args.ide / 'lib').glob('*.jar')))
    git = shutil.which('git')
    results = {'baseline': args.baseline, 'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
               'java': subprocess.check_output([java, '-version'], stderr=subprocess.STDOUT, text=True).strip(),
               'inputLines': 40000, 'heap': '512m', 'runs': []}
    with tempfile.TemporaryDirectory(prefix='blame-benchmark-', dir=build) as directory:
        work = Path(directory)
        old_src = work / 'BlameProvider.kt'
        old_src.write_bytes(subprocess.check_output(['git', 'show', f'{args.baseline}:src/main/kotlin/com/github/ten951/gitplus/blame/BlameProvider.kt'], cwd=root))
        old_classes, probe = work / 'old', work / 'probe'
        old_classes.mkdir()
        probe.mkdir()
        subprocess.run([java, '-cp', os.pathsep.join(compiler), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                        '-no-stdlib', '-no-reflect', '-jvm-target', '21', '-classpath', str(main_classes) + os.pathsep + deps,
                        '-d', str(old_classes), str(old_src)], check=True)
        subprocess.run([javac, '-cp', str(main_classes) + os.pathsep + deps, '-d', str(probe), str(root / 'tools/BlameBenchmark.java')], check=True)
        for run in range(args.runs):
            for label, implementation in [('V1', old_classes), (args.label, main_classes)]:
                repo = work / f'repo-{run}-{label}'
                repo.mkdir()
                subprocess.run([git, 'init', '-q', str(repo)], check=True, env=env)
                file = repo / 'sample.txt'
                file.write_text('line\n' * 40000)
                subprocess.run([git, '-C', str(repo), '-c', 'core.autocrlf=false', 'add', '.'], check=True, env=env)
                subprocess.run([git, '-C', str(repo), '-c', 'user.name=probe', '-c', 'user.email=probe@example.invalid',
                                'commit', '-qm', 'benchmark'], check=True, env=env)
                counter, wrapper = repo / 'count', repo / 'git-probe'
                counter.write_text('')
                wrapper.write_text(f'#!/bin/sh\nprintf . >> {shlex.quote(str(counter))}\nexec {shlex.quote(git)} "$@"\n')
                wrapper.chmod(0o700)
                cp = os.pathsep.join([str(implementation), str(main_classes), str(probe), deps])
                output = subprocess.check_output([java, '-Xmx512m', '-cp', cp, 'BlameBenchmark', str(file), str(wrapper), str(counter)], text=True, env=env)
                sample = dict(json.loads(output), revision=label, run=run + 1)
                results['runs'].append(sample)
                print(json.dumps(sample), flush=True)
    report = build / 'reports' / args.report
    report.parent.mkdir(exist_ok=True)
    report.write_text(json.dumps(results, indent=2) + '\n')
    print(f'Report: {report}')


if __name__ == '__main__':
    main()
