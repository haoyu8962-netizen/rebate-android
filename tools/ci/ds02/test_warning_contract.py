#!/usr/bin/env python3
"""不需 Gradle 的断言器强度自测；不冒充生产守卫执行。"""
import unittest
from check_warning_output import CONTROLS, MARKER, assert_warning_result


class WarningContractTest(unittest.TestCase):
    def output(self, controls=CONTROLS):
        return '\n'.join(f'feature/demo/src/main/kotlin/demo/SystemControls.kt:20: {MARKER} {c}' for c in controls)

    def test_legal_warning_only_output(self):
        assert_warning_result(0, self.output())

    def test_silent_success_is_rejected(self):
        with self.assertRaises(AssertionError):
            assert_warning_result(0, 'BUILD SUCCESSFUL')

    def test_hard_failure_is_rejected(self):
        with self.assertRaises(AssertionError):
            assert_warning_result(1, self.output())

    def test_each_omitted_control_is_rejected(self):
        for control in CONTROLS:
            with self.subTest(control=control), self.assertRaises(AssertionError):
                assert_warning_result(0, self.output(tuple(c for c in CONTROLS if c != control)))

    def test_substring_is_not_a_control_match(self):
        with self.assertRaises(AssertionError):
            assert_warning_result(0, self.output().replace(f'{MARKER} TextField', f'{MARKER} OutlinedTextField'))

    def test_warning_without_location_is_rejected(self):
        with self.assertRaises(AssertionError):
            assert_warning_result(0, self.output().replace('SystemControls.kt:', 'summary:'))


if __name__ == '__main__':
    unittest.main(verbosity=2)
