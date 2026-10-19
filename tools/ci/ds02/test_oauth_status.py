#!/usr/bin/env python3
import tempfile
import unittest
from pathlib import Path
from check_oauth_assets import status


class OAuthStatusTest(unittest.TestCase):
    def test_shopping_platforms_do_not_count_as_login_assets(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            approval = root / 'approval.properties'
            approval.write_text('WECHAT.resource=\nHUAWEI.resource=\n')
            for name in ('taobao.png', 'jd.svg', 'pinduoduo.webp', 'tmall.xml', 'alipay.pdf'):
                (root / name).write_text('shopping-platform fixture')
            (root / 'wechat-readme.md').write_text('登录素材仍未交付')
            self.assertIn('待官方素材', status(approval, root))

    def test_either_login_prefix_in_nested_directory_triggers_approval(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            approval = root / 'approval.properties'
            approval.write_text('WECHAT.resource=\nHUAWEI.resource=\n')
            nested = root / 'delivery'
            nested.mkdir()
            for name in ('wechat.svg', 'WeChat-mark.PNG', 'huawei-login.xml', 'HUAWEI-official.ZIP'):
                with self.subTest(name=name):
                    asset = nested / name
                    asset.write_text('login fixture')
                    with self.assertRaises(AssertionError):
                        status(approval, root)
                    asset.unlink()

    def test_pending_is_visible_and_supplied_or_partial_cannot_silently_skip(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            approval = root / 'approval.properties'
            approval.write_text('WECHAT.resource=\n')
            self.assertIn('待官方素材', status(approval, root))
            with self.assertRaises(AssertionError):
                status(approval, root / 'missing-directory')
            asset = root / 'wechat.svg'
            asset.write_text('<svg/>')
            with self.assertRaises(AssertionError):
                status(approval, root)
            asset.unlink()
            approval.write_text('WECHAT.resource=wechat\n')
            with self.assertRaises(AssertionError):
                status(approval, root)
            approval.write_text('\n'.join(f'{p}.{k}=approved' for p in ('WECHAT', 'HUAWEI')
                                          for k in ('resource', 'sha256', 'source')))
            self.assertIn('严格', status(approval, root))


if __name__ == '__main__':
    unittest.main()
