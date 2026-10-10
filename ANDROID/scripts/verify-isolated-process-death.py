"""Samsung fixture only. Each invocation creates new case namespaces; never clears data."""
import json
import pathlib
import re
import subprocess
import time
import uuid

ADB = '/home/lazarus/Android/Sdk/platform-tools/adb'
SERIAL = 'R8YY411LE3T'
PACKAGE = 'ph.edu.bsit.tcc.ojtdtr.recoveryfixture'
CASES = ('committed', 'before', 'migration', 'blocked', 'mismatch', 'corrupt', 'keyloss')
TEST = 'ph.edu.bsit.tcc.ojtdtr.recovery.ProcessDeathTest'


def call(*args, check=True):
    result = subprocess.run([ADB, '-s', SERIAL, *args], capture_output=True, text=True, timeout=40)
    if check and result.returncode:
        raise RuntimeError('ADB fixture operation failed')
    return result.stdout.strip()


def ready_pid(marker, run, case):
    match = re.fullmatch(r'([0-9a-f]{32}) ([a-z]+) ([1-9][0-9]*)', marker)
    if not match or match.group(1, 2) != (run, case):
        raise RuntimeError('Missing or stale fixture readiness evidence')
    return int(match[3])


def verified_pid(marker, output, run, case, old):
    match = re.fullmatch(r'([0-9a-f]{32}) ([a-z]+) PASS ([1-9][0-9]*) ([1-9][0-9]*)', marker)
    if (not match or match.group(1, 2) != (run, case) or int(match[3]) != old
            or int(match[4]) == old or 'OK (1 test)' not in output
            or 'FAILURES!!!' in output or 'INSTRUMENTATION_STATUS_CODE: -3' in output):
        raise RuntimeError('Invalid, skipped or stale fresh-process verification')
    return int(match[4])


def kill_fixture(old):
    # All signals execute as fixture UID: PID reuse can never signal the production UID.
    if call('shell', 'pidof', PACKAGE) != str(old):
        raise RuntimeError('Unexpected fixture PID; no signal sent')
    command = call('shell', 'run-as', PACKAGE, 'cat', f'/proc/{old}/cmdline').rstrip('\x00')
    if command != PACKAGE:
        raise RuntimeError('Unexpected process identity; no signal sent')
    call('shell', 'run-as', PACKAGE, 'kill', '-9', str(old))


def instrumentation(run, case, method):
    return [ADB, '-s', SERIAL, 'shell', 'am', 'instrument', '-w', '-r',
            '-e', 'run', run, '-e', 'case', case, '-e', 'class', f'{TEST}#{method}',
            f'{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner']


def main():
    # Validate device/package boundary before instrumentation (which may restart only fixture).
    if call('shell', 'getprop', 'ro.product.model') != 'SM-A065F':
        raise RuntimeError('Unexpected device')
    fixture_uid = call('shell', 'run-as', PACKAGE, 'id', '-u')
    production = call('shell', 'cmd', 'package', 'list', 'packages', '-U', 'ph.edu.bsit.tcc.ojtdtr')
    match = re.search(r'^package:ph\.edu\.bsit\.tcc\.ojtdtr uid:([0-9]+)$', production, re.M)
    if not fixture_uid.isdigit() or not match or fixture_uid == match[1]:
        raise RuntimeError('Package UID isolation not proven')
    run = uuid.uuid4().hex
    root = pathlib.Path('/tmp') / f'u73g-review-{run}'
    root.mkdir()
    results = []
    for case in CASES:
        with (root / f'{case}-seed.log').open('w') as log:
            seed = subprocess.Popen(instrumentation(run, case, 'seedAndAwaitKill'),
                                    stdout=log, stderr=subprocess.STDOUT)
            marker = ''
            for _ in range(100):
                marker = call('shell', 'run-as', PACKAGE, 'cat', f'files/{run}-{case}-ready', check=False)
                if marker:
                    break
                if seed.poll() is not None:
                    raise RuntimeError('Seed failed before readiness; no signal sent')
                time.sleep(.2)
            old = ready_pid(marker, run, case)
            if seed.poll() is not None:
                raise RuntimeError('Seed ended before controlled termination')
            kill_fixture(old)
            seed.wait(timeout=10)
        time.sleep(.3)
        if call('shell', 'pidof', PACKAGE, check=False):
            raise RuntimeError('Fixture remains or automatically restarted; STOP')
        # Explicit shell probe supplies a status string; do not infer absence from empty stdout.
        gone = call('shell', 'sh', '-c', f'"test ! -d /proc/{old} && echo gone"', check=False)
        if gone != 'gone':
            raise RuntimeError('Old PID still exists or was reused; STOP')
        verify = subprocess.run(instrumentation(run, case, 'freshProcessVerifiesEvidence'),
                                capture_output=True, text=True, timeout=40)
        output = verify.stdout
        (root / f'{case}-verify.log').write_text(output)
        if verify.returncode:
            raise RuntimeError('Fresh runner failed')
        marker = call('shell', 'run-as', PACKAGE, 'cat', f'files/{run}-{case}-verified')
        new = verified_pid(marker, output, run, case, old)
        results.append({'case': case, 'run': run, 'old_pid': old, 'new_pid': new,
                        'fixture_uid': fixture_uid, 'production_uid': match[1], 'result': 'PASS'})
        (root / 'results.json').write_text(json.dumps(results, indent=2))
        print(f'{case}: PASS {old} -> {new}', flush=True)
    print(f'Evidence: {root}', flush=True)


if __name__ == '__main__':
    main()
