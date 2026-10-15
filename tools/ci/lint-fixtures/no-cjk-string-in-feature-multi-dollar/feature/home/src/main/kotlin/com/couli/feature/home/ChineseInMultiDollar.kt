package com.couli.feature.home

// lint 违反样例（no-cjk-string-in-feature，F-AND-02）：多美元符号插值字符串（Kotlin 2.x）里，单个 ${…} 是字面文字，不是模板。
// 旧扫描器把 ${ 中文 } 当成插值里的代码，中文漏检；现在按前缀的 $ 个数判断，整段都是字符串文字、必须被拦。
object ChineseInMultiDollar {
    val price = $$"""price ${ 中文 } $${amount}"""
}
