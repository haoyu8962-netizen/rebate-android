package com.couli.feature.home

// lint 违反样例（no-cjk-string-in-feature，BR-TEXT-12）：feature 模块的字符串字面量里写了中文。
// tools/ci/verify.sh 用 `./gradlew couliRules -Pcouli.rules.root=<本目录的上两级>` 证明它会被拦。
// 注释里的中文不算违反。
object HardcodedChinese {
    const val TITLE = "首页"
}
