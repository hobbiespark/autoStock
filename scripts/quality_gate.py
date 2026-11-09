"""autoStock quality gate (VALUNIVO 0-7 S11, D336): the platform gate's output for this app's existing checks.

    py -3.13 scripts/quality_gate.py                 all areas (tests with DB integration tests required, frontend types)
    py -3.13 scripts/quality_gate.py --area be       one area
    py -3.13 scripts/quality_gate.py --no-db         tests without DB integration tests (said so in the output)
    py -3.13 scripts/quality_gate.py --staged        pre-commit (.githooks/pre-commit): only the areas the staged files touch, fast checks

Output is the same as VALUNIVO scripts/dev/quality_gate.py and writingApp scripts/quality_gate.py so the gate results of
the apps read the same way: '[gate] areas: ...', one '[gate] <check>: ok' or 'FAIL (exit n)' line per check with the tail
of a failure, '[gate]   <module>: tests N, failed F, skipped S' after the tests, and a last '[gate] PASS' or '[gate] FAIL'.
Exit code 0 when everything passed, otherwise 1. It keeps autoStock's own rules (no new formatter or linter, S11).

DB integration tests use the production image through Testcontainers when Docker is up, or AUTOSTOCK_TEST_DB_URL
(decision D-18, support.PostgresTestDatabase). The gate sets AUTOSTOCK_TEST_DB_REQUIRED=true so a missing DB fails
instead of skipping silently; --no-db sets AUTOSTOCK_TEST_DB_DISABLED=true so they are skipped on purpose. A test DB
URL that points at a production port is refused.

--staged compiles the main and test code and runs the existing layer rules (ArchitectureRulesTest) instead of all tests,
type-checks the frontend when a frontend file is staged and runs the gate's own unit tests when the gate changes. The full gate (no --staged) stays the check before a merge."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from datetime import UTC, datetime
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
AREAS = ('be', 'fe', 'py')
GATE_FILES = ('scripts/quality_gate.py', 'scripts/test_quality_gate.py')
MODULES = ('common', 'app')
# Production database ports (VALUNIVO D153/D155): the platform, writing and autoStock production clusters.
PRODUCTION_PORTS = ('56432', '56433', '56434')
DB_ENV = ('AUTOSTOCK_TEST_DB_URL', 'AUTOSTOCK_TEST_DB_USER', 'AUTOSTOCK_TEST_DB_PASSWORD')
LAYER_RULES = 'com.autostock.ArchitectureRulesTest'


# ---------- pure logic (test_quality_gate.py) ----------


def areas_for(paths: list[str]) -> list[str]:
    """Which areas a set of changed paths touches."""
    found = set()
    for path in paths:
        p = path.replace('\\', '/')
        if p.startswith(('common/', 'app/', 'gradle/')) or p in ('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties'):
            found.add('be')
        if p.startswith('frontend/'):
            found.add('fe')
        if p in GATE_FILES:
            found.add('py')
    return [a for a in AREAS if a in found]


def production_url(url: str | None) -> bool:
    """True when a test DB URL points at a production port (the gate refuses it)."""
    if not url:
        return False
    match = re.search(r':(\d+)(/|$|\?)', url.split('//', 1)[-1])
    return bool(match) and match.group(1) in PRODUCTION_PORTS


def db_env(env: dict[str, str], no_db: bool) -> dict[str, str]:
    """Child environment for the tests: DB tests required, or hidden on purpose with --no-db."""
    out = dict(env)
    if no_db:
        for key in (*DB_ENV, 'AUTOSTOCK_TEST_DB_REQUIRED'):
            out.pop(key, None)
        # PostgresAvailableCondition skips the DB tests on purpose (Docker alone cannot be hidden: ~/.testcontainers.properties pins the pipe).
        out['AUTOSTOCK_TEST_DB_DISABLED'] = 'true'
    else:
        out.pop('AUTOSTOCK_TEST_DB_DISABLED', None)
        out['AUTOSTOCK_TEST_DB_REQUIRED'] = 'true'
    return out


def summarize_results(suites: list[dict], started: float) -> dict[str, int]:
    """Totals of JUnit result files; suites written before this run started count as stale."""
    total = {'tests': 0, 'failed': 0, 'skipped': 0, 'stale': 0}
    for suite in suites:
        total['tests'] += suite['tests']
        total['failed'] += suite['failures'] + suite['errors']
        total['skipped'] += suite['skipped']
        total['stale'] += 1 if suite['timestamp'] < started else 0
    return total


def results_line(module: str, total: dict[str, int]) -> str:
    note = f", stale {total['stale']} files from an earlier run" if total['stale'] else ''
    return f"[gate]   {module}: tests {total['tests']}, failed {total['failed']}, skipped {total['skipped']}{note}"


# ---------- shell ----------


TOOLS = Path(os.environ.get('VALUNIVO_DEV_TOOLS', 'C:/ai/dev-tools'))


def toolchain() -> dict:
    try:
        return json.loads((TOOLS / 'toolchain.json').read_text(encoding='utf-8-sig'))
    except (OSError, ValueError):
        return {}


def node_dir() -> Path | None:
    directory = toolchain().get('Node', {}).get('Directory')
    return TOOLS / directory if directory else None


def tool_env() -> dict[str, str]:
    """JAVA_HOME and PATH: AUTOSTOCK_JAVA_HOME (as scripts/run_tests.bat) or the shared JDK, and the shared Node."""
    env = dict(os.environ)
    jdk = toolchain().get('Jdk', {}).get('Directory')
    home = env.get('AUTOSTOCK_JAVA_HOME') or (str(TOOLS / jdk) if jdk else None)
    extra = [str(Path(home) / 'bin')] if home else []
    if home:
        env['JAVA_HOME'] = home
    if node_dir():
        extra.append(str(node_dir()))
    env['PATH'] = os.pathsep.join([*extra, env.get('PATH', '')])
    return env


def gradle(*tasks: str) -> list[str]:
    # Full path: shells with NoDefaultCurrentDirectoryInExePath=1 do not find gradlew.bat in the current folder.
    wrapper = ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    return ['cmd', '/c', str(wrapper), *tasks, '-q', '--console=plain'] if os.name == 'nt' else [str(wrapper), *tasks, '-q']


def npm(*args: str) -> list[str]:
    # Full path to the shared Node's npm (not on the user PATH here, RM-53).
    folder = node_dir()
    if os.name == 'nt':
        return ['cmd', '/c', str(folder / 'npm.cmd') if folder else 'npm', *args]
    return [str(folder / 'bin' / 'npm') if folder else 'npm', *args]


def staged_paths() -> list[str]:
    out = subprocess.run(['git', 'diff', '--cached', '--name-only', '--diff-filter=ACMR'], check=False, cwd=ROOT, capture_output=True, text=True, encoding='utf-8')
    return [line for line in out.stdout.splitlines() if line]


def run(label: str, command: list[str], env: dict[str, str], cwd: Path = ROOT) -> bool:
    print(f'[gate] {label}: ', end='', flush=True)
    result = subprocess.run(command, check=False, cwd=cwd, env=env, capture_output=True, text=True, encoding='utf-8', errors='replace')
    ok = result.returncode == 0
    print('ok' if ok else f'FAIL (exit {result.returncode})')
    if not ok:
        tail = (result.stdout + result.stderr).strip().splitlines()[-25:]
        print('\n'.join('    ' + line for line in tail))
    return ok


def junit_suites(module: str) -> list[dict]:
    suites = []
    for file in sorted((ROOT / module / 'build' / 'test-results' / 'test').glob('*.xml')):
        try:
            node = ElementTree.parse(file).getroot()
            stamp = datetime.fromisoformat(node.get('timestamp', ''))
            if stamp.tzinfo is None:
                stamp = stamp.replace(tzinfo=UTC)
        except (ElementTree.ParseError, ValueError):
            continue
        suites.append({key: int(node.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')} | {'timestamp': stamp.timestamp()})
    return suites


def gate(areas: list[str], no_db: bool, staged: bool = False) -> bool:
    env, ok = tool_env(), True
    if 'be' in areas and staged:
        ok &= run('be compile + layer rules', gradle(':common:compileTestJava', ':app:test', '--tests', LAYER_RULES), env)
    elif 'be' in areas:
        if production_url(env.get('AUTOSTOCK_TEST_DB_URL')) and not no_db:
            print('[gate] be test: FAIL (AUTOSTOCK_TEST_DB_URL points at a production port; unset it to use Testcontainers)')
            ok = False
        else:
            if no_db:
                print('[gate] be DB integration tests: skipped on purpose (--no-db)')
            started = time.time()
            ok &= run('be test', gradle(':common:test', '--rerun', ':app:test', '--rerun', '--continue'), db_env(env, no_db))
            for module in MODULES:
                print(results_line(module, summarize_results(junit_suites(module), started)))
    if 'fe' in areas:
        front = ROOT / 'frontend'
        if not (front / 'node_modules').is_dir():
            ok &= run('fe install (npm ci)', npm('ci', '--no-audit', '--no-fund'), env, front)
        ok &= run('fe typecheck', npm('exec', '--', 'tsc', '--noEmit'), env, front)
    if 'py' in areas:
        ok &= run('py gate unit tests', [sys.executable, '-m', 'unittest', 'discover', '-s', str(ROOT / 'scripts'), '-p', 'test_quality_gate.py', '-q'], env)
    return ok


def main(argv: list[str] | None = None) -> int:
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(errors='replace')
    parser = argparse.ArgumentParser(description='autoStock quality gate (VALUNIVO 0-7 S11, D336)')
    parser.add_argument('--area', action='append', choices=AREAS)
    parser.add_argument('--no-db', action='store_true', help='run the tests without DB integration tests (they are skipped, and the gate says so)')
    parser.add_argument('--staged', action='store_true', help='pre-commit: only the areas the staged files touch, fast checks')
    args = parser.parse_args(argv)
    areas = args.area or (areas_for(staged_paths()) if args.staged else list(AREAS))
    if not areas:
        print('[gate] nothing to check in the staged files' if args.staged else '[gate] no areas')
        return 0
    print('[gate] areas: ' + ', '.join(areas) + (' (staged)' if args.staged else ''))
    ok = gate(areas, args.no_db, args.staged)
    print('[gate] ' + ('PASS' if ok else 'FAIL'))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
