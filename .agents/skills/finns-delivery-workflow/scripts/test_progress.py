import hashlib
import tempfile
import unittest
from pathlib import Path
from check_progress import inspect


class ProgressTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / 'decision.md').write_text('approved revision A')
        self.gate = {'id': 'G1', 'status': 'pass', 'next_action': 'review',
                     'user_action': 'confirm revision', 'evidence': [{'path': 'decision.md',
                     'sha256': hashlib.sha256((self.root / 'decision.md').read_bytes()).hexdigest()}]}

    def result(self, gates=None):
        return inspect(self.root, {'schema_version': 1, 'tasks': [
            {'id': 'feature', 'version': '1.0.0', 'gates': gates or [self.gate]}]})[0]

    def test_valid_evidence(self):
        self.assertIsNone(self.result()['next'])

    def test_changed_evidence(self):
        (self.root / 'decision.md').write_text('new unapproved revision')
        self.assertEqual(self.result()['next']['stage'], 'G1')

    def test_missing_evidence(self):
        (self.root / 'decision.md').unlink()
        self.assertFalse(self.result()['registered_gates_satisfied'])

    def test_pass_without_evidence(self):
        self.gate['evidence'] = []
        self.assertIsNotNone(self.result()['next'])

    def test_na_without_reason(self):
        self.gate['status'] = 'na'
        self.assertIsNotNone(self.result()['next'])

    def test_pending_does_not_inherit_later_pass(self):
        pending = dict(self.gate, id='G0', status='pending')
        self.assertEqual(self.result([pending, self.gate])['next']['stage'], 'G0')

    def test_path_escape(self):
        self.gate['evidence'][0]['path'] = '../outside.md'
        with self.assertRaises(ValueError):
            self.result()

    def test_duplicate_gate(self):
        with self.assertRaises(ValueError):
            self.result([self.gate, self.gate])


if __name__ == '__main__':
    unittest.main()
