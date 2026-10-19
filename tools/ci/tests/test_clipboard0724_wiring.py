"""FU-AND-0724 接线守卫；只核验薄入口和生产绑定来源，行为由 Kotlin 测试负责。"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
APP = ROOT / 'app/src/main/kotlin/com/couli/app'


def uncomment(text):
    # 只用于这里读取的不含 URL 字面量的函数段。
    return re.sub(r'/\*.*?\*/|//[^\n]*', '', text, flags=re.S)


def focus_forwarder_is_exact(body):
    compact = re.sub(r'\s+|;', '', uncomment(body))
    return compact == 'super.onWindowFocusChanged(hasFocus)clipboard.onWindowFocus(hasFocus)'


def block_at(source, opening):
    depth = 0
    for index in range(opening, len(source)):
        if source[index] == '{':
            depth += 1
        elif source[index] == '}':
            depth -= 1
            if depth == 0:
                return source[opening + 1:index], index + 1
    raise AssertionError('unclosed production block')


def foreground_notice_has_one_emitter(source):
    source = uncomment(source)
    ports = list(re.finditer(r'override\s+fun\s+showNotice\s*\([^)]*\)[^{]*\{', source))
    if len(ports) != 1:
        return False
    port = ports[0]
    body, end = block_at(source, port.end() - 1)
    if len(re.findall(r'\bSOURCE_FOREGROUND\b', body)) != 1:
        return False
    remaining = source[:port.start()] + source[end:]
    # 常量定义和只读来源判断合法；任何其他构造/传递来源的路径都不能绕过协调器。
    remaining = re.sub(r'const\s+val\s+SOURCE_FOREGROUND\s*=\s*"foreground"', '', remaining)
    remaining = re.sub(r'(?:ClipboardConsentNotice\.)?SOURCE_FOREGROUND\s*->', '', remaining)
    remaining = re.sub(r'\.source\s*==\s*(?:ClipboardConsentNotice\.)?SOURCE_FOREGROUND', '', remaining)
    return not re.search(r'\bSOURCE_FOREGROUND\b', remaining)


def modal_sources_are_connected(source):
    """只守生产绑定函数调用；工厂内真实服务到协调器的行为由 Kotlin 测试锁定。

    不推断文件里第一个同类型变量；允许已构造的 .also 别名、可空服务、Provider。
    """
    source = re.sub(r'\s+', '', uncomment(source))
    for call in re.finditer(r'\b\w+\??\.connectModalSources\(', source):
        start = call.end()
        depth = 1
        end = start
        while end < len(source) and depth:
            depth += (source[end] == '(') - (source[end] == ')')
            end += 1
        if depth:
            continue
        parts = [p for p in source[start:end - 1].split(',') if p]
        if len(parts) != 3:
            continue
        if all('=' in p for p in parts):
            arguments = dict(p.split('=', 1) for p in parts)
            if set(arguments) != {'trade', 'login', 'update'}:
                continue
            parts = list(arguments.values())
        # 只接受传实例/可空实例/Provider.get，不接受常量 null、替身流或无用构造。
        if all(p != 'null' and re.fullmatch(r'\w+(?:\??\.get\(\))?', p) for p in parts):
            return True
    return False


class ForwarderCheckerSelfTest(unittest.TestCase):
    def test_real_argument_forwarding_accepts_whitespace_and_comments(self):
        self.assertTrue(focus_forwarder_is_exact('super.onWindowFocusChanged(hasFocus)\n// pass current value\nclipboard.onWindowFocus(hasFocus)'))

    def test_five_miswirings_are_rejected(self):
        prefix = 'super.onWindowFocusChanged(hasFocus)\n'
        for name, body in {
            'constant': 'clipboard.onWindowFocus(true)',
            'dead_code': 'if (false) { clipboard.onWindowFocus(hasFocus) }',
            'second_read': 'clipboard.onWindowFocus(hasWindowFocus())',
            'overwritten': 'clipboard.onWindowFocus(hasFocus); clipboard.onWindowFocus(false)',
            'wrong_receiver': 'otherClipboard.onWindowFocus(hasFocus)',
        }.items():
            with self.subTest(name=name):
                self.assertFalse(focus_forwarder_is_exact(prefix + body))


class ProductionWiringTest(unittest.TestCase):
    def test_activity_focus_passes_the_observed_argument_once(self):
        source = (APP / 'MainActivity.kt').read_text()
        match = re.search(r'override fun onWindowFocusChanged\(hasFocus: Boolean\)\s*\{(.*?)\n    }', source, re.S)
        self.assertIsNotNone(match)
        self.assertTrue(focus_forwarder_is_exact(match.group(1)))

    def test_singleton_provider_binds_the_real_process_lifecycle(self):
        source = (APP / 'AppModule.kt').read_text()
        match = re.search(r'@Provides\s+@Singleton\s+(?:@Suppress\([^\n]*\)[^\n]*\n\s*)?fun appClipboard\(.*?(?=\n    /\*\*|\Z)', source, re.S)
        self.assertIsNotNone(match, 'AppClipboard 必须保持进程单例')
        provider = match.group(0)
        alias = re.search(r'\)\s*\.also\s*\{\s*(\w+)\s*->', provider)
        self.assertIsNotNone(alias, '绑定必须针对刚创建的 AppClipboard；局部别名不限')
        receiver = re.escape(alias.group(1))
        self.assertEqual(1, len(re.findall(receiver + r'\.bindProcessLifecycle\(\s*ProcessLifecycleOwner\.get\(\)\.lifecycle\s*\)', provider)),
                         '真实 provider 必须把 ProcessLifecycleOwner 绑定到返回的同一个实例')

    def test_activity_does_not_create_foreground_rounds(self):
        source = uncomment((APP / 'MainActivity.kt').read_text())
        self.assertFalse(bool(re.search(r'clipboard\.on(?:Foreground|Background)\(', source)), 'Activity 不应创建进程轮次')
        self.assertTrue(bool(re.search(r'clipboard\.onActivityResumed\(\)', source)), 'onResume 转发缺失')

    def test_retired_watcher_and_second_notice_source_are_absent(self):
        source = (APP / 'clipboard/AppClipboard.kt').read_text()
        self.assertFalse(bool(re.search(r'\bClipboardWatcher\s*\(', source)), '旧 Watcher 尚未退役')
        self.assertFalse((ROOT / 'core/clipboard/src/main/kotlin/com/couli/core/clipboard/ClipboardWatcher.kt').exists())
        self.assertFalse((ROOT / 'core/clipboard/src/main/kotlin/com/couli/core/clipboard/ClipboardReadPolicy.kt').exists())

    def test_foreground_notice_is_emitted_only_by_coordinator_port(self):
        self.assertTrue(foreground_notice_has_one_emitter((APP / 'clipboard/AppClipboard.kt').read_text()),
                        '回前台用途弹层只能从协调器 showNotice 端口创建')

    def test_modal_sources_are_bound_from_real_producers(self):
        candidates = [(APP / 'AppModule.kt').read_text(), (APP / 'shell/AppShell.kt').read_text()]
        self.assertTrue(any(modal_sources_are_connected(source) for source in candidates),
                        '真实服务实例必须交给已被行为测试覆盖的 connectModalSources；不要求在 appClipboard provider 里取 login')


class SourceCheckerSelfTest(unittest.TestCase):
    def test_notice_allows_manual_predicate_but_rejects_second_emitter(self):
        valid = """
        const val SOURCE_FOREGROUND = "foreground"
        fun noticeEligible() = manualNoticeNeeded()
        override fun showNotice(epoch: Long) { showConsentNotice(ClipboardConsentNotice.SOURCE_FOREGROUND) }
        """
        self.assertTrue(foreground_notice_has_one_emitter(valid))
        for name in ['resumeDeferredNotice', 'foregroundNoticeReady', 'anyOtherName']:
            self.assertFalse(foreground_notice_has_one_emitter(valid +
                'fun ' + name + '() { showConsentNotice(ClipboardConsentNotice.SOURCE_FOREGROUND) }'))
        self.assertFalse(foreground_notice_has_one_emitter(valid +
            'fun extra() { val second = ClipboardConsentNotice(ClipboardConsentNotice.SOURCE_FOREGROUND) }'))

    def test_modal_checker_accepts_created_nullable_and_provider_instances(self):
        for valid in [
            'clipboard.connectModalSources(trade=shop,login=created,update=updater)',
            'fun wire(login: AppLogin?, update: AppUpdate?) { clipboard?.connectModalSources(shop, login, update) }',
            'AppLogin(...).also { created -> clip.connectModalSources(trade=t.get(),login=created,update=u.get()) }',
            'clip.connectModalSources(update=u,trade=t,login=l,)',
        ]:
            self.assertTrue(modal_sources_are_connected(valid), valid)
        for invalid in [
            'clip.connectModalSources(trade=t,login=null,update=u)',
            'clip.connectModalSources(trade=t,login=MutableStateFlow(false),update=u)',
            'clip.connectModalSources(trade=t,update=u)',
            '// clip.connectModalSources(trade=t,login=l,update=u)',
            'val unused = ClipboardModalSources(...)',
        ]:
            self.assertFalse(modal_sources_are_connected(invalid), invalid)


if __name__ == '__main__':
    unittest.main(verbosity=2)
