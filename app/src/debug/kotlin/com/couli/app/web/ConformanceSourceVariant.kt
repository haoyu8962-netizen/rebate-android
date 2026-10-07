package com.couli.app.web

/**
 * 本机一致性测试源（03 §5.5；APP-04 任务书 §2.3「Debug 本机 loopback 例外」）：只在 debug 构建的 src/debug 里。staging 的同名文件
 * 给 null，Release 没有一致性页；release-scan 检查 staging 与 release 制品里都没有本机地址（loopback_origin）。
 * 编排者在本机以 rebate-platform 9cd1e6f 的 apps/h5/dist/web 为根起静态服务（127.0.0.1:8787），模拟器经 10.0.2.2 访问；
 * 明文只对这两个地址放开（src/debug/res/xml/network_security_config_debug.xml）。
 */
internal val CONFORMANCE_SOURCE: ConformanceSource? =
    ConformanceSource(
        url = "http://10.0.2.2:8787/conformance/",
        loopbackOrigins = setOf("http://10.0.2.2:8787", "http://127.0.0.1:8787"),
    )
