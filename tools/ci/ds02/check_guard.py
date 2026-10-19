#!/usr/bin/env python3
import os
from pathlib import Path
import subprocess
from check_warning_output import assert_warning_result

root = Path(__file__).resolve().parents[3]
env = os.environ.copy()
jbr = '/Applications/Android Studio.app/Contents/jbr/Contents/Home'
if 'JAVA_HOME' not in env and Path(jbr).is_dir():
    env['JAVA_HOME'] = jbr
result = subprocess.run(
    [str(root / 'gradlew'), '--offline', '--no-daemon', '--console=plain', 'couliRules',
     '-Pcouli.rules.root=' + str(root / 'tools/ci/lint-fixtures/ds02-system-controls')],
    cwd=root, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
)
print(result.stdout)
assert_warning_result(result.returncode, result.stdout)
print('DS-02 G1：13 类控件均警告，任务成功退出')
