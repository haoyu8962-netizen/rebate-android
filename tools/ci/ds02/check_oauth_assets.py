#!/usr/bin/env python3
"""素材交付闸：未到位可见挂起；微信 / 华为登录原件到位却未全核准则失败。无网络请求。"""
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
APPROVAL = ROOT / 'core/designsystem/src/test/resources/ds02-approved-oauth-assets.properties'


def status(approval, directory):
    if not directory.is_dir():
        raise AssertionError("官方素材目录不存在；请纠正 COULI_DIR，不能当作尚未到位")
    values = dict(line.split('=', 1) for line in approval.read_text().splitlines()
                  if line.strip() and not line.lstrip().startswith('#'))
    complete = all(values.get(f'{p}.{k}', '').strip() for p in ('WECHAT', 'HUAWEI')
                   for k in ('resource', 'sha256', 'source'))
    supplied = directory.exists() and any(p.is_file() and p.name.lower().startswith(('wechat', 'huawei')) and p.suffix.lower() in
        {'.svg', '.png', '.webp', '.pdf', '.xml', '.jpg', '.jpeg', '.zip', '.ai', '.eps'} for p in directory.rglob('*'))
    partial = any(v.strip() for v in values.values())
    if (supplied or partial) and not complete:
        raise AssertionError('官方素材已到位或核准表已开始填写，必须补齐两平台 resource / sha256 / source 并执行严格比对')
    return '[DS-02 官方素材] 已核准；严格资源与渲染比对必须通过' if complete else '[DS-02 挂起] 待官方素材；当前执行非通用占位检查及目录标注检查，素材交付后自动切严格比对'


def platform_directory():
    if os.environ.get('COULI_DIR'):
        return Path(os.environ['COULI_DIR']) / 'design/icons/platform'
    for parent in ROOT.parents:
        candidate = parent / 'couli/design/icons/platform'
        if candidate.is_dir():
            return candidate
    raise AssertionError('找不到规划仓库素材目录，请设置 COULI_DIR；不能把路径不存在伪装成素材未到位')


if __name__ == '__main__':
    print(status(APPROVAL, platform_directory()))
