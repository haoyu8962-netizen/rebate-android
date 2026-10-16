package com.couli.feature.home

// lint 违反样例（no-cjk-string-in-feature，FU-AND-0717 F-X-07 补充二）：用 Unicode 转义写的中文，编译后就是中文。
// 旧扫描器只看字面原文（\u4e2d 是 ASCII），放过；现在按编译器还原转义后再查，字符字面量同样算。
object EscapedCjk {
    val title = "\u4e2d\u6587"
    val mark = '\u4e2d'
}
