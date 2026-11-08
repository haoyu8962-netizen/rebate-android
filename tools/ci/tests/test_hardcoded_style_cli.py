#!/usr/bin/env python3
"""LINT-STYLE v1：真实 couliRules CLI 样例与基线生成；不另写扫描器。

运行：python3 tools/ci/tests/test_hardcoded_style_cli.py
待实现 CLI 契约：现有 couliRules 增加 -Pcouli.rules.writeBaseline=true，
在 couli.rules.root 下生成 tools/ci/hardcoded-style-baseline.json。
只写临时树；不更新仓库正式基线。所有 Gradle 调用强制离线。
"""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
FIXTURES = ROOT / 'tools/ci/lint-fixtures'
BASELINE = Path('tools/ci/hardcoded-style-baseline.json')
PAGE = 'feature/demo/src/main/kotlin/demo/Page.kt'
APP_PAGE = 'app/src/main/kotlin/demo/Page.kt'


class HardcodedStyleCliTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='couli-style-cli-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.write(BASELINE, '{}\n')

    def write(self, relative, text):
        target = self.root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding='utf-8')
        return target

    def run_rules(self, *, generate=False):
        env = os.environ.copy()
        jbr = Path('/Applications/Android Studio.app/Contents/jbr/Contents/Home')
        if 'JAVA_HOME' not in env and jbr.is_dir():
            env['JAVA_HOME'] = str(jbr)
        command = [str(ROOT / 'gradlew'), '--offline', '--no-daemon', '--console=plain',
                   'couliRules', '-Pcouli.rules.root=' + str(self.root)]
        if generate:
            command.append('-Pcouli.rules.writeBaseline=true')
        return subprocess.run(command, cwd=ROOT, env=env, text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              timeout=300, check=False)

    def copy_fixture(self, name):
        source = FIXTURES / name
        self.assertTrue(source.is_dir(), str(source))
        files = sorted(source.rglob('*.kt'))
        self.assertTrue(files, str(source))
        for path in files:
            self.write(path.relative_to(source), path.read_text(encoding='utf-8'))
        return files

    def test_each_violation_fixture_fails_with_its_rule_and_location(self):
        cases = {
            'style-no-hex-color': 'no-hex-color',
            'style-no-numeric-spacing': 'no-numeric-style',
            'style-no-numeric-radius': 'no-numeric-style',
            'style-no-numeric-text': 'no-numeric-style',
            'style-exemption-reason': 'exemption-reason',
        }
        for fixture, rule in cases.items():
            with self.subTest(fixture=fixture):
                files = self.copy_fixture(fixture)
                result = self.run_rules()
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertRegex(result.stdout, rf'{re.escape(files[0].name)}:\d+: \[{rule}\]')
                for path in files:
                    (self.root / path.relative_to(FIXTURES / fixture)).unlink()

    def test_legal_fixture_passes_without_style_diagnostics(self):
        self.copy_fixture('style-legal')
        result = self.run_rules()
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertNotRegex(result.stdout, r'\[(no-hex-color|no-numeric-style|exemption-reason)\]')

    def test_system_buttons_do_not_warn_at_the_real_task_boundary(self):
        # 编排 D10-830（couli #295 ui-rules/android.md §8）：三个 M3 按钮不进 G1 名单，直接用不产生 design-system-required 警告
        self.copy_fixture('style-system-buttons')
        result = self.run_rules()
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertNotRegex(result.stdout, r'SystemButtons.kt:\d+: \[design-system-required\]')

    def test_baseline_generation_is_exact_stable_and_used_by_the_task(self):
        # 故意先写 feature，再写字典序更早的 app；规则和文件都必须排序。
        self.write(PAGE, 'val a = Color(0xFF123456)\nval b = Color(0xFF234567)\n'
                         'fun sample() { Modifier.padding(8.dp) }\n'
                         'val c = RoundedCornerShape(8)\nval d = 14.sp\n')
        self.write(APP_PAGE, 'val a = Color(0xFF345678)\nval b = 16.sp\n')
        self.write('app/src/staging/kotlin/demo/Ignored.kt', 'val a = 18.sp\n')
        baseline = self.root / BASELINE
        baseline.unlink()
        expected = {
            'couli-style/no-hex-color': {APP_PAGE: 1, PAGE: 2},
            'couli-style/no-numeric-style': {APP_PAGE: 1, PAGE: 3},
        }
        generated = self.run_rules(generate=True)
        self.assertEqual(0, generated.returncode, generated.stdout)
        self.assertTrue(baseline.is_file(), '生成任务必须实际写出基线，不能忽略属性并成功退出')
        first = baseline.read_bytes()
        self.assertEqual(expected, json.loads(first))
        self.assertEqual((json.dumps(expected, ensure_ascii=False, sort_keys=True, indent=2) + '\n').encode(), first)
        baseline.unlink()
        repeated = self.run_rules(generate=True)
        self.assertEqual(0, repeated.returncode, repeated.stdout)
        self.assertEqual(first, baseline.read_bytes(), '同一棵树两次独立生成必须逐字节相同')
        equal = self.run_rules()
        self.assertEqual(0, equal.returncode, equal.stdout)
        self.write(PAGE, 'val a = Color(0xFF123456)\nval b = Color(0xFF234567)\n'
                         'fun sample() { Modifier.padding(8.dp) }\n'
                         'val c = RoundedCornerShape(8)\nval d = 14.sp\nval e = 18.sp\n')
        increased = self.run_rules()
        self.assertNotEqual(0, increased.returncode, increased.stdout)
        self.assertRegex(increased.stdout, r'Page.kt.*\[no-numeric-style\]')
        self.assertEqual(first, baseline.read_bytes(), '普通检查不得自动接受新增违规')
        self.write(PAGE, 'val legal = 1\n')
        decreased = self.run_rules()
        self.assertNotEqual(0, decreased.returncode, decreased.stdout)
        self.assertRegex(decreased.stdout, r'Page.kt.*\[no-numeric-style\]')
        self.assertEqual(first, baseline.read_bytes(), '普通检查不得静默重写过期基线')


    # ------------------------------------------------------------ LINT-STYLE v2（编排 2026-11-07 04:2x 缺口）

    def test_v2_equivalent_forms_fixture_fails_and_generates_exact_counts(self):
        self.copy_fixture('style-v2-equivalent-forms')
        result = self.run_rules()
        self.assertNotEqual(0, result.returncode, result.stdout)
        for rule in ('no-hex-color', 'no-numeric-style'):
            self.assertRegex(result.stdout, rf'EquivalentForms.kt:\d+: \[{rule}\]')
        (self.root / BASELINE).unlink()
        generated = self.run_rules(generate=True)
        self.assertEqual(0, generated.returncode, generated.stdout)
        self.assertTrue((self.root / BASELINE).is_file(), '生成任务必须实际写出基线')
        forms = 'feature/demo/src/main/kotlin/demo/EquivalentForms.kt'
        self.assertEqual({'couli-style/no-hex-color': {forms: 4}, 'couli-style/no-numeric-style': {forms: 9}},
                         json.loads((self.root / BASELINE).read_text(encoding='utf-8')))

    def test_v2_equivalent_legal_fixture_passes_without_style_diagnostics(self):
        self.copy_fixture('style-v2-equivalent-legal')
        result = self.run_rules()
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertNotRegex(result.stdout, r'\[(no-hex-color|no-numeric-style|exemption-reason)\]')

    def test_v2_generation_covers_hidden_dirs_main_java_and_release(self):
        counted = {
            'feature/demo/src/main/kotlin/demo/build/Hidden.kt': ('no-hex-color', 'val a = Color(0xFF123456)\n'),
            'feature/demo/src/main/kotlin/demo/.kotlin/Hidden.kt': ('no-numeric-style', 'val a = 14.sp\n'),
            'feature/demo/src/main/java/demo/JavaDir.kt': ('no-hex-color', 'val a = Color(0xFF123456L)\n'),
            'app/src/release/kotlin/demo/Release.kt': ('no-numeric-style', 'val a = TextUnit(14f, TextUnitType.Sp)\n'),
        }
        ignored = [
            'feature/demo/build/generated/source/kapt/main/demo/Gen.kt',
            'feature/demo/build/intermediates/src/main/kotlin/demo/Gen.kt',
            'app/build/generated/ksp/release/kotlin/Gen.kt',
            'app/src/staging/kotlin/demo/Staging.kt',
            'app/src/debugFeatures/kotlin/demo/Debug.kt',
        ]
        for path, (_, text) in counted.items():
            self.write(path, text)
        for path in ignored:
            self.write(path, 'val a = Color(0xFF123456)\nval b = 14.sp\n')
        plain = self.run_rules()
        self.assertNotEqual(0, plain.returncode, plain.stdout)
        for path in counted:
            self.assertIn(path + ':1:', plain.stdout)
        for path in ignored:
            self.assertNotIn(path, plain.stdout)
        (self.root / BASELINE).unlink()
        generated = self.run_rules(generate=True)
        self.assertEqual(0, generated.returncode, generated.stdout)
        self.assertTrue((self.root / BASELINE).is_file(), '生成任务必须实际写出基线')
        expected = {}
        for path, (rule, _) in counted.items():
            expected.setdefault('couli-style/' + rule, {})[path] = 1
        self.assertEqual(expected, json.loads((self.root / BASELINE).read_text(encoding='utf-8')))
        equal = self.run_rules()
        self.assertEqual(0, equal.returncode, equal.stdout)

if __name__ == '__main__':
    unittest.main(verbosity=2)
