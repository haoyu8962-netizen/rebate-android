"""S2 / T1 / M24：原样产品与内存反例都走同一个守卫，绝不写产品文件。"""
from pathlib import Path
import re
import unittest

from login_17a_guard import assert_resend_bypasses_agreement

VM = Path(__file__).resolve().parents[2] / "feature/login/src/main/kotlin/com/couli/feature/login/LoginViewModel.kt"

SAMPLE = '''
class LoginViewModel {
    fun onResend() = requestCode(true)
    private fun requestCode(fromCodePage: Boolean) { sendCode() }
    private fun throughAgreement(next: () -> Unit) {
        agreement.tapLogin()
        if (agreement.showConfirm) {
            state.update { it.copy(dialog = LoginDialog.AGREE_CONFIRM) }
            return
        }
        if (agreement.consumeProceed()) next()
    }
}
'''


class Login17aGuardSelfTest(unittest.TestCase):
    def rewired(self, body, extra=""):
        self.assertEqual(1, SAMPLE.count("fun onResend() = requestCode(true)"))
        return SAMPLE.replace("fun onResend() = requestCode(true)", "fun onResend() " + body) + extra

    def assert_blocked(self, source, *chain):
        with self.assertRaises(AssertionError) as caught:
            assert_resend_bypasses_agreement(source)
        self.assertIn("重新获取调用链经过协议闸", str(caught.exception))
        self.assertIn(" -> ".join(chain), str(caught.exception))

    def test_safe_sample_and_actual_production_pass(self):
        assert_resend_bypasses_agreement(SAMPLE)
        assert_resend_bypasses_agreement(VM.read_text())

    def test_m24_renamed_actual_gate_reconnected_to_resend_is_rejected(self):
        source = VM.read_text()
        self.assertIn("private fun requireAgreement(", source)
        renamed = re.sub(r'\brequireAgreement\b', "throughAgreement", source)
        assert_resend_bypasses_agreement(renamed)  # 只改名不能误报。
        mutant, count = re.subn(
            r'fun onResend\(\)\s*=\s*requestCode\(fromCodePage = true\)',
            "fun onResend() = throughAgreement(LoginAgreementAction.GET_CODE) { requestCode(fromCodePage = true) }",
            renamed,
        )
        self.assertEqual(1, count, "生产重发入口变了，需调整反例注入点，不能跑一个未接回闸的空变异")
        self.assert_blocked(mutant, "onResend", "throughAgreement")

    def test_renamed_gate_in_block_body_is_rejected(self):
        source = self.rewired("{ throughAgreement { requestCode(true) } }")
        self.assert_blocked(source, "onResend", "throughAgreement")

    def test_arbitrary_depth_helpers_and_function_reference_are_rejected(self):
        source = self.rewired(
            "= relay()",
            '''
private fun relay() = later { this.dispatch() }
private fun dispatch() { val callback = this::throughAgreement; callback { requestCode(true) } }
''',
        )
        self.assert_blocked(source, "onResend", "relay", "dispatch", "throughAgreement")

    def test_split_protocol_advance_and_inline_advance_are_rejected(self):
        source = SAMPLE.replace("agreement.tapLogin()", "advanceProtocol()")
        source += "\nprivate fun advanceProtocol() = agreement.tapLogin()\n"
        source = source.replace("fun onResend() = requestCode(true)", "fun onResend() = advanceProtocol()")
        self.assert_blocked(source, "onResend", "advanceProtocol")
        self.assert_blocked(self.rewired("{ agreement.tapLogin(); requestCode(true) }"), "onResend")

    def test_inline_confirmation_write_and_permit_consumption_are_rejected(self):
        for body in (
            "{ state.update { it.copy(dialog = LoginDialog.AGREE_CONFIRM) } }",
            "{ if (agreement.consumeProceed()) requestCode(true) }",
            "{ agreement.showConfirm = true }",
        ):
            with self.subTest(body=body):
                self.assert_blocked(self.rewired(body), "onResend")

    def test_comments_literals_and_unreachable_gate_do_not_create_edges(self):
        source = self.rewired('''{
            // throughAgreement { requestCode(true) }
            /* outer { /* inner agreement.tapLogin() */ } */
            val example = "throughAgreement( } )"
            val multi = """agreement.tapLogin() { }"""
            val brace = '}'
            requestCode(true)
        }''')
        assert_resend_bypasses_agreement(source)

    def test_cycles_terminate_and_still_reject_a_reachable_gate(self):
        source = self.rewired("= relay()", "\nprivate fun relay() { onResend(); throughAgreement {} }\n")
        self.assert_blocked(source, "onResend", "relay", "throughAgreement")

    def test_missing_entry_or_unrecognised_protocol_fails_closed(self):
        for source in ("private fun gate() { agreement.tapLogin() }", "fun onResend() = requestCode(true)"):
            with self.subTest(source=source), self.assertRaises(AssertionError):
                assert_resend_bypasses_agreement(source)


if __name__ == "__main__":
    unittest.main(verbosity=2)
