package com.couli.feature.home

// lint 违反样例（scan-incomplete，F-X-07 fail-closed）：原始字符串没有闭合，扫描器无法确认之后的代码与字符串边界。
// 旧扫描器把剩下的内容都当字符串文字吞掉、只字不报；现在必须以非零退出（不只打印诊断）。
object UnclosedRawString {
    val text = """still open
    fun later() = SentryAndroid.init(context)
}
