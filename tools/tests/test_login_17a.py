"""APP-17a 可独立运行的共享向量与删除项守卫；产品行为仍由 Kotlin 集成测试负责。"""
import hashlib
import json
from pathlib import Path
import re
import unittest

from login_17a_guard import assert_resend_bypasses_agreement

ROOT = Path(__file__).resolve().parents[2]
VECTORS = ROOT / "specs/login-vectors.json"


def raw_section(source, name):
    match = re.search(r'^  "' + name + r'": ', source, re.M)
    if match is None:
        raise AssertionError(f"缺共享段 {name}")
    _, length = json.JSONDecoder().raw_decode(source[match.end():])
    return source[match.start():match.end() + length]


class Login17aContractTest(unittest.TestCase):
    def test_shared_sections_are_byte_identical_to_harmony_main(self):
        # 来自 rebate-harmony main 原始段，不重排、不重新序列化 JSON。
        expected = SHARED_SHA256
        source = VECTORS.read_text()
        data = json.loads(source)
        for name, (count, digest) in expected.items():
            with self.subTest(name=name):
                self.assertEqual(count, len(data[name]))
                self.assertEqual(digest, hashlib.sha256(raw_section(source, name).encode()).hexdigest())

    def test_sequence_manifest_is_complete_and_named_uniquely(self):
        rows = json.loads(VECTORS.read_text())["codeAutoSubmit"]
        names = [row["name"] for row in rows]
        self.assertEqual(len(names), len(set(names)))
        allowed = {"check", "uncheck", "getCode", "continueCode", "resend", "back", "confirmAgree",
                   "cancelConfirm", "input", "respond", "advance", "field"}
        for row in rows:
            for step in row["steps"]:
                self.assertIn(step["op"], allowed)
            self.assertIn("requests", row["expect"])
            self.assertIn("sends", row["expect"])

    def test_resend_call_chain_does_not_enter_the_phone_agreement_gate(self):
        source = (ROOT / "feature/login/src/main/kotlin/com/couli/feature/login/LoginViewModel.kt").read_text()
        assert_resend_bypasses_agreement(source)

    def test_removed_keys_are_absent_from_product_literals(self):
        retired = re.compile(
            r'"(?:login\.(?:settings|submit|invite\.[^"\n]*|agree\.(?:prefix|and|suffix|hint)|'
            r'agree_confirm\.(?:ok|cancel|before|after))|clipboard\.login_invite\.[^"\n]*|invite\.notice_inviter)"'
        )
        paths = list((ROOT / "feature/login/src/main").rglob("*.kt"))
        paths += list((ROOT / "app/src/main/kotlin/com/couli/app/login").glob("*.kt"))
        paths += [ROOT / "app/src/main/kotlin/com/couli/app/clipboard/ClipboardDraftTexts.kt"]
        hits = [f"{p.relative_to(ROOT)}:{n}:{line.strip()}" for p in paths
                for n, line in enumerate(p.read_text().splitlines(), 1)
                if retired.search(line) and not line.lstrip().startswith(("//", "*"))]
        self.assertEqual([], hits, "废弃文案键与告知句不能残留在产品")

    def test_removed_manual_invite_api_and_submit_entry_are_gone(self):
        paths = list((ROOT / "feature/login/src/main").rglob("*.kt"))
        paths += list((ROOT / "app/src/main/kotlin/com/couli/app/login").glob("*.kt"))
        paths += [ROOT / "app/src/main/kotlin/com/couli/app/AppModule.kt"]
        retired = re.compile(r'\b(?:inviteRaw|InvitePasteSource|onPasteInvite|onExpandInvite|onInviteChange)\b')
        hits = [f"{p.relative_to(ROOT)}:{n}" for p in paths
                for n, line in enumerate(p.read_text().splitlines(), 1)
                if retired.search(line) and not line.lstrip().startswith(("//", "*"))]
        vm = ROOT / "feature/login/src/main/kotlin/com/couli/feature/login/LoginViewModel.kt"
        self.assertFalse(re.search(r'fun\s+onLogin\s*\(', vm.read_text()), '验证码页手动 onLogin 入口仍存在')
        self.assertEqual([], hits)


SHARED_SHA256 = {'agreementConfirm': (4, '640ee594f3b4b5922e6dfc3248d906b02d76d79244c4ab672761959dd07c5dd9'), 'codeAutoSubmit': (14, 'fd0d120637be5177fa055cc0d8b6d2001e0f9f6e047389760c175bc6c61f0727'), 'invite': (14, '542b6f5444140560d0e6ddf5cf29a891b3b777b19bb50ab92301a0279d75b681'), 'gaps': (21, 'ed2d397337c6bc7911ddfbaf4c29ec3a9bc785d0897d4b0f74896a7ebc7dd9d3')}  # 由测试作者一次性从指定鸿蒙 main 取原始段指纹。

if __name__ == "__main__":
    unittest.main(verbosity=2)
