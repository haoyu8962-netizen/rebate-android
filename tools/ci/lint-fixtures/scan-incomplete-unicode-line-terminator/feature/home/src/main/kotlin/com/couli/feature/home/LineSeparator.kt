package com.couli.feature.home

// lint 违反样例（scan-incomplete，FU-AND-0717 F-X-07 补充二）：下一行的行注释里有 U+2028（LINE SEPARATOR）。编译器不当换行（后面仍是注释），
// 编辑器、评审 diff 与正则会当换行，人看到的边界与编译器不一致——扫描器一律按扫不完整、非零退出。
object LineSeparator {
    // note val title = "中文"
    val ok = 1
}
