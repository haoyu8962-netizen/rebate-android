#!/usr/bin/env python3
"""清单自身与 Release 隔离设施检查；不把静态检查当成 UI 已通过。"""
import argparse
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
MATRIX = ROOT / 'app/src/test/resources/ds02-inventory.tsv'
MARKER = 'COULI_COMPONENT_CATALOG_DS01_6f2c9a4e81b7d350'


def rows():
    return [line.split('|') for line in MATRIX.read_text().splitlines() if line and not line.startswith('#')]


def cases():
    return [(r[0], v, s) for r in rows() for v in r[3].split(',') for s in r[4].split(',')]


class InventoryTest(unittest.TestCase):
    def test_matrix_is_nonempty_unique_and_has_both_theme_runner(self):
        self.assertGreaterEqual(len({r[0] for r in rows()}), 60)
        self.assertEqual(len(cases()), len(set(cases())))
        self.assertTrue(all(len(r) == 5 and r[2] in ('系统控件', '自绘') for r in rows()))
        snapshot = (ROOT / 'app/src/testDebug/kotlin/com/couli/app/catalog/Ds02CatalogSnapshotTest.kt').read_text()
        self.assertIn('listOf(false, true)', snapshot)
        self.assertIn('recording || file.isFile', snapshot)

    def test_catalog_marker_stays_in_debug_source_set(self):
        expected = ROOT / 'app/src/debugFeatures/kotlin/com/couli/app/catalog/ComponentCatalog.kt'
        self.assertIn(MARKER, expected.read_text())
        for source_set in ('main', 'release'):
            for p in (ROOT / 'app/src' / source_set).rglob('*.kt'):
                self.assertNotIn(MARKER, p.read_text(), str(p))
                self.assertNotRegex(p.read_text(), r'fun\s+(ComponentCatalog|CatalogComponentSample)\s*\(', str(p))
        build_types = (ROOT / 'build-logic/convention/src/main/kotlin/com/couli/buildlogic/CouliBuildTypes.kt').read_text()
        self.assertRegex(build_types, r'withDebugFeatures.*setOf\(DEBUG, STAGING\)')
        sources = (ROOT / 'build-logic/convention/src/main/kotlin/com/couli/buildlogic/AndroidCommon.kt').read_text()
        self.assertIn('if (buildType !in CouliBuildTypes.withDebugFeatures) return', sources)
        scanner = (ROOT / 'tools/ci/release-scan.sh').read_text()
        self.assertIn('component_catalog', scanner)
        self.assertIn('COMPONENT_CATALOG_MARKER', scanner)


def check_source_inventory(path):
    text = Path(path).read_text()
    section = text.split('## 3. 基础控件表', 1)[1].split('## 5.', 1)[0]
    names = set()
    for line in section.splitlines():
        if line.startswith('| `'):
            names.update(re.findall(r'`([A-Z][A-Za-z]+)`', line.split('|')[1]))
    aliases = {'CouliDialog': 'Dialog', 'CouliSheet': 'Sheet'}
    names = {aliases.get(name, name) for name in names}
    actual = {r[0] for r in rows()}
    assert names <= actual, f'清单组件漏项：{names - actual}'
    print(f'全文 §3/§4 名称核对通过；矩阵 {len(actual)} 组件、{len(cases())} 状态组合、{len(cases()) * 2} 两态基准')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--inventory')
    parser.add_argument('--check-baselines', action='store_true')
    args = parser.parse_args()
    if args.inventory:
        check_source_inventory(args.inventory)
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(InventoryTest))
    if args.check_baselines:
        expected = [ROOT / f'app/src/test/snapshots/ds02/{c}/{v}-{s}-{theme}.png'
                    for c, v, s in cases() for theme in ('light', 'dark')]
        missing = [str(p.relative_to(ROOT)) for p in expected if not p.is_file()]
        if missing:
            print(f'FAIL：缺 {len(missing)}/{len(expected)} 张基准；实现后核图再录制，不能静默跳过。')
            print('\n'.join(missing[:8]))
            raise SystemExit(1)
    raise SystemExit(0 if result.wasSuccessful() else 1)
