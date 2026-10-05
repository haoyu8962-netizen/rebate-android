package com.couli.feature.home

// lint 违反样例（no-cjk-string-in-feature，BR-TEXT-12）：中文藏在字符串模板 ${…} 里的嵌套字符串字面量中。
// 外层字符串本身没有中文；tools/ci/verify.sh 证明扫描器递归进插值后仍会拦下。
object ChineseInInterpolation {
    fun label(ok: Boolean): String = "${if (ok) "成功" else "失败"}"
}
