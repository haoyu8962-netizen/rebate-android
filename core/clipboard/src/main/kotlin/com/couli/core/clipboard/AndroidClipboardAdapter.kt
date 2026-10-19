package com.couli.core.clipboard

/**
 * Android 的剪贴板读取适配器（FU-AND-0724；BR-ID-16「三端读取方式」、细则「三端读取方式小结」「系统拒绝的终态」；03 §4.6「三端适配器 Android」；
 * R18 / G16）：每次调用只读一次系统剪贴板的主剪贴项文本（经 [SystemClipboard.read]：先看 MIME，只取第一项的 text，不解析 content URI、
 * 不读图片、不用 coerceToText），结果映射为 [ImportReadResult]：
 * - 读到非空文本且不超过 [maxTextCodePoints] 个码点 → text；超过上限整项按 unavailable（不截断：截断可能变成另一个候选）；
 * - 空字符串 → empty；非文本、拿不到（窗口未获焦等）、任何异常 → unavailable（Android 没有 denied；异常内容可能带剪贴板信息，不记录）。
 * 读到空或异常都是本轮终态，由协调器收束：不重试、不轮询、不跨轮节流；不写系统剪贴板，不申请权限。
 *
 * [maxTextCodePoints] 默认沿用 parse 请求的文本上限（[ParseInputService.MAX_TEXT]），不是新的业务数值。
 */
class AndroidClipboardAdapter(
    private val system: SystemClipboard,
    private val maxTextCodePoints: Int = ParseInputService.MAX_TEXT,
) {
    // 系统剪贴板与厂商实现可能抛任何异常，一律按不可用（异常内容不记）；异常、非文本、文本三个出口
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount")
    fun read(): ImportReadResult {
        val read =
            try {
                system.read()
            } catch (error: Exception) {
                return ImportReadResult(ImportReadResult.KIND_UNAVAILABLE)
            }
        val text = (read as? ClipRead.Text)?.value ?: return ImportReadResult(ImportReadResult.KIND_UNAVAILABLE)
        return when {
            text.isEmpty() -> ImportReadResult(ImportReadResult.KIND_EMPTY)
            text.codePointCount(0, text.length) > maxTextCodePoints -> ImportReadResult(ImportReadResult.KIND_UNAVAILABLE)
            else -> ImportReadResult(ImportReadResult.KIND_TEXT, text)
        }
    }
}
