"""FU-X-STAGING-HOSTS-android：release-scan.sh 对 staging 真实主机的规则（brief-android §1）。

静态核对 tools/ci/release-scan.sh（本文件不改脚本，脚本由实现方改）：
- R1：扫描模式表里至少一条模式命中五个 staging 主机 api-stg / stream-stg / h5-stg / l-stg / s-stg.couliapp.cn；
- R2：local / test 的 .invalid 占位仍被拦；
- R3：不误伤 prod 主域与 prod 占位（couliapp.com、s.couliapp.cn、l.couliapp.com、api.prod.couli.invalid）；
- R4：--self-test 要求 staging 制品命中这条规则的标签（staging 包里一定有这些地址），release 仍须 0 命中；
- R5（有制品才跑）：以 release 制品为底合成带五个主机的制品，扫描必须以 1 退出并报出该标签。
制品层面的最终证据是 tools/ci/verify.sh 里的 release-scan.sh <release apk> 与 --self-test（需要 Gradle 构建产物）。
"""
from pathlib import Path
import os
import re
import shutil
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / 'tools/ci/release-scan.sh'
STAGING_HOSTS = ['api-stg.couliapp.cn', 'stream-stg.couliapp.cn', 'h5-stg.couliapp.cn', 'l-stg.couliapp.cn', 's-stg.couliapp.cn']
SAMPLES = ['https://' + h for h in STAGING_HOSTS] + ['"' + h + '"' for h in STAGING_HOSTS]
PROD_LIKE = ['https://api.couliapp.com', 'https://s.couliapp.cn/i/K7M2QX', 'https://l.couliapp.com/r/Home',
             'https://api.prod.couli.invalid', 'https://h5.prod.couli.invalid', 'couliapp.cn']


def source():
    return SCRIPT.read_text(encoding='utf-8')


def literal_patterns(text):
    """scan_apk 里 `for pattern in ...; do` 的字面量模式（单引号）；变量模式（标记、回环）不在此列。"""
    match = re.search(r"^\s*for pattern in (.+?); do$", text, flags=re.M)
    if not match:
        raise AssertionError('找不到 scan_apk 的 for pattern 模式表')
    return re.findall(r"'([^']*)'", match.group(1))


def ere(pattern):
    # 模式表里的扩展正则只用到 . \ | ( ) [ ] ? + *，与 Python re 语义一致
    return re.compile(pattern)


def label_of(text, pattern):
    """照脚本的 case 分支求标签：有专门分支用分支名，否则 nonprod_url:${pattern%%\\*}。"""
    case = re.search(r'case "\$pattern" in(.+?)\n\s*esac', text, flags=re.S)
    if case:
        for arm in re.finditer(r"^\s*(.+?)\)\s*label=\"([^\"]+)\"", case.group(1), flags=re.M):
            keys = [k.strip().strip("'\"") for k in arm.group(1).split('|')]
            if pattern in keys:
                return arm.group(2)
    return 'nonprod_url:' + pattern.split('\\', 1)[0]


def matching(text, sample):
    return [p for p in literal_patterns(text) if ere(p).search(sample)]


def self_test_block(text):
    match = re.search(r'if \[ "\$\{1:-\}" = "--self-test" \]; then(.+?)\nfi\n', text, flags=re.S)
    if not match:
        raise AssertionError('找不到 --self-test 段')
    return match.group(1)


class ReleaseScanStagingHostsTest(unittest.TestCase):
    def test_r1_every_staging_host_is_caught(self):
        text = source()
        missing = [s for s in SAMPLES if not matching(text, s)]
        self.assertEqual([], missing, '这些 staging 地址没有任何扫描模式命中')

    def test_r2_local_and_test_placeholders_are_still_caught(self):
        text = source()
        for sample in ['https://api.local.couli.invalid', 'https://h5.test.couli.invalid']:
            self.assertTrue(matching(text, sample), sample)

    def test_r3_prod_hosts_are_not_flagged(self):
        text = source()
        for sample in PROD_LIKE:
            self.assertEqual([], matching(text, sample), '不得误伤：' + sample)

    def test_r4_self_test_requires_staging_artifact_to_hit_the_rule(self):
        text = source()
        labels = {label_of(text, p) for s in SAMPLES for p in matching(text, s)}
        self.assertTrue(labels, '先要有命中 staging 主机的模式（R1）')
        block = self_test_block(text)
        staging = block.split('release-scan --self-test：staging 制品必须命中', 1)
        self.assertEqual(2, len(staging), '--self-test 里找不到 staging 段')
        staging_part = staging[1].split('release-scan --self-test：release 制品必须不命中', 1)[0]
        required = set(re.findall(r'require_label "staging" "([^"]+)"', staging_part))
        self.assertTrue(labels & required, f'staging 段须 require_label 其中之一：{sorted(labels)}；现有 {sorted(required)}')
        forbidden = set(re.findall(r'forbid_label "staging" "([^"]+)"', staging_part))
        self.assertFalse(labels & forbidden, 'staging 段不得 forbid 这条规则')

    def test_r4b_release_part_still_requires_zero_hits(self):
        block = self_test_block(source())
        release_part = block.split('release-scan --self-test：release 制品必须不命中', 1)
        self.assertEqual(2, len(release_part))
        self.assertIn('if [ "$SCAN_HITS" -ne 0 ]; then', release_part[1])

    def test_r5_synthetic_release_with_staging_hosts_is_rejected(self):
        release = ROOT / 'app/build/outputs/apk/release/app-release-unsigned.apk'
        sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
        aapt2 = os.environ.get('AAPT2') or shutil.which('aapt2') or str(sdk / 'build-tools/36.0.0/aapt2')
        if not release.is_file() or not os.access(aapt2, os.X_OK):
            self.skipTest('没有 release 制品或 aapt2（要 Gradle assembleRelease 之后）；制品层面由 verify 的 release-scan 守')
        text = source()
        labels = {label_of(text, p) for s in SAMPLES for p in matching(text, s)}
        with tempfile.TemporaryDirectory() as tmp:
            synthetic = Path(tmp) / 'synthetic.apk'
            shutil.copy(release, synthetic)
            with zipfile.ZipFile(synthetic, 'a') as z:
                z.writestr('assets/staging-hosts.txt', '\n'.join(SAMPLES) + '\n')
            result = subprocess.run(['bash', str(SCRIPT), str(synthetic)], capture_output=True, text=True)
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertTrue(any(label in result.stdout for label in labels), result.stdout)


if __name__ == '__main__':
    unittest.main()
