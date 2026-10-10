"""Deterministic host evidence/signal boundary tests. No ADB or device operations."""
import importlib.util
import pathlib
import unittest
import xml.etree.ElementTree as ET
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('fixture_host', pathlib.Path(__file__).with_name('verify-isolated-process-death.py'))
host = importlib.util.module_from_spec(spec)
spec.loader.exec_module(host)

class EvidenceTest(unittest.TestCase):
    nonce = 'a' * 32
    def test_ready_requires_current_run_and_case(self):
        self.assertEqual(42, host.ready_pid(f'{self.nonce} committed 42', self.nonce, 'committed'))
        for marker in ['42', f'{"b"*32} committed 42', f'{self.nonce} before 42', f'{self.nonce} committed -1']:
            with self.assertRaises(RuntimeError): host.ready_pid(marker, self.nonce, 'committed')
    def test_fresh_success_requires_exact_current_evidence(self):
        marker=f'{self.nonce} committed PASS 42 43'
        self.assertEqual(43,host.verified_pid(marker,'OK (1 test)',self.nonce,'committed',42))
        for candidate in [marker.replace(self.nonce,'b'*32), marker.replace('42 43','41 43'), marker.replace('42 43','42 42'), marker.replace('committed','before')]:
            with self.assertRaises(RuntimeError): host.verified_pid(candidate,'OK (1 test)',self.nonce,'committed',42)
    def test_skip_or_failure_cannot_be_success(self):
        for output in ['OK (0 tests)', 'FAILURES!!! OK (1 test)', 'INSTRUMENTATION_STATUS_CODE: -3 OK (1 test)', 'Process crashed']:
            with self.assertRaises(RuntimeError): host.verified_pid(f'{self.nonce} committed PASS 42 43',output,self.nonce,'committed',42)
    def test_pid_mismatch_sends_no_signal(self):
        with patch.object(host,'call',return_value='43') as call:
            with self.assertRaises(RuntimeError): host.kill_fixture(42)
            self.assertEqual(1,call.call_count)
    def test_wrong_package_sends_no_signal(self):
        with patch.object(host,'call',side_effect=['42','ph.edu.bsit.tcc.ojtdtr']) as call:
            with self.assertRaises(RuntimeError): host.kill_fixture(42)
            self.assertEqual(2,call.call_count)
    def test_lint_exception_is_only_the_unreachable_hardware_file(self):
        root=pathlib.Path(__file__).resolve().parents[1]
        config=ET.parse(root/'app/src/isolated/lint.xml').getroot()
        self.assertEqual(1,len(config))
        self.assertEqual({'id':'MissingPermission'},config[0].attrib)
        self.assertEqual(1,len(config[0]))
        self.assertEqual({'path':'**/src/main/java/ph/edu/bsit/tcc/ojtdtr/proof/ProofHardware.kt'},config[0][0].attrib)
    def test_signal_is_always_scoped_to_fixture_uid(self):
        with patch.object(host,'call',side_effect=['42',host.PACKAGE+'\x00','']) as call:
            host.kill_fixture(42)
            self.assertEqual(('shell','run-as',host.PACKAGE,'kill','-9','42'),call.call_args.args)

if __name__=='__main__': unittest.main()
