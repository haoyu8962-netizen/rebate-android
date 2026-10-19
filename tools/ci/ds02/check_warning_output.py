#!/usr/bin/env python3
"""G1 CLI 断言；检查真实 couliRules 输出，不自行实现生产守卫。"""
import re

CONTROLS = (
    'Checkbox', 'Switch', 'RadioButton', 'TextField', 'OutlinedTextField',
    'AlertDialog', 'ModalBottomSheet', 'TopAppBar', 'CenterAlignedTopAppBar',
    'NavigationBar', 'FilterChip', 'SegmentedButton', 'BasicTextField',
)
MARKER = '[design-system-required]'


def assert_warning_result(returncode, output):
    assert returncode == 0, f'G1 应只警告，退出码却为 {returncode}:\n{output}'
    lines = [line for line in output.splitlines() if MARKER in line]
    for control in CONTROLS:
        assert any(re.search(rf'\b{control}\b', line) and 'SystemControls.kt:' in line for line in lines), \
            f'缺少 {control} 的带位置警告；不能只打印总计或 BUILD SUCCESSFUL:\n{output}'
