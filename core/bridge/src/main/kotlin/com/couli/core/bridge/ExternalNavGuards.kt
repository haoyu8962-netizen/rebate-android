package com.couli.core.bridge

/**
 * 授权页隔离（03 §5.1「授权页不进容器」；APP-04 三端共性第 7 条）：初始地址与之后每一次导航都先过这里，命中即拦下。
 * 授权页登记表还没有进契约（TODO(N-22)），这里保守地内置常见登录授权域（Apple、微信 / QQ、华为、淘宝 / 支付宝、京东、
 * 抖音、微博、百度、Google），并对任何主机上的 OAuth 授权路径一律拦下：宁可多拦，不能因为「没命中联盟规则」就放行授权页。
 */
object AuthPages {
    /** 整域（含子域）命中即视为授权页。 */
    val HOSTS: Set<String> =
        setOf(
            "appleid.apple.com",
            "idmsa.apple.com",
            "open.weixin.qq.com",
            "graph.qq.com",
            "xui.ptlogin2.qq.com",
            "oauth-login.cloud.huawei.com",
            "id1.cloud.huawei.com",
            "oauth.taobao.com",
            "oauth.m.taobao.com",
            "login.taobao.com",
            "login.m.taobao.com",
            "auth.alipay.com",
            "openauth.alipay.com",
            "oauth.jd.com",
            "open-oauth.jd.com",
            "passport.jd.com",
            "plogin.m.jd.com",
            "open.douyin.com",
            "api.weibo.com",
            "passport.baidu.com",
            "openapi.baidu.com",
            "accounts.google.com",
        )

    /** 路径里出现这些段（不分大小写）即按授权页处理。 */
    private val PATH_SEGMENTS: Set<String> = setOf("oauth", "oauth2", "authorize", "oauth2.0")

    private val WEB_WITH_AUTHORITY = Regex("^https?:[/\\\\]{2}[^/\\\\?#]+", RegexOption.IGNORE_CASE)

    /**
     * 先用与链接形态表同一套规范化（[NormalizedUrl]：浏览器式预处理、标准解析、消点段、按 BR-ATTR-29 百分号解码一次；APP-04 三端
     * 共性第 17、35 条）再匹配主机与路径段，`/%6fauth2/%61uthorize` 一类的编码变体绕不过去。有主机却规范化不了的（不合法的百分号
     * 编码、带用户信息等）宁可按授权页拦下；没有主机（about:、data:、javascript:、解析不了的）不算。
     */
    @Suppress("ReturnCount") // 规范化不了、主机命中、路径命中
    fun isAuthPage(url: String): Boolean {
        val normalized = NormalizedUrl.parse(url, allowHttp = true)
        // http(s) 且带主机部分、却规范化不了（标准解析器拒收的编码、带用户信息等）：宁可按授权页拦下
        if (normalized == null) return WEB_WITH_AUTHORITY.containsMatchIn(url.trim())
        val host = normalized.host
        if (HOSTS.any { host == it || host.endsWith(".$it") }) return true
        return normalized.path
            .lowercase()
            .split('/')
            .any { it in PATH_SEGMENTS }
    }
}

/**
 * external_page_nav_host 抽样埋点（BR-ATTR-29 细则「覆盖限制与待定事项」；APP-04 三端共性第 12 条）：只记注册域、框架类型与是否命中
 * 规则，不记路径与参数。采样率是 /v1/config external_page.nav_host_sample_bp（万分比；缺省 1000，显式 0 = 不报，
 * BR-ATTR-29、共性第 39 条）。
 */
object NavHostSample {
    private const val BASIS = 10_000
    private const val SUFFIXED_LABELS = 3

    /** 二级公共后缀（常见国家域下的 com、net、org 等）。TODO(N-22)：没有公共后缀表，按这组常见情形近似。 */
    private val SECOND_LEVEL: Set<String> = setOf("com", "net", "org", "gov", "edu", "co", "ac")

    /** [roll] 取 0 until 10000 的均匀随机数。 */
    fun sampled(
        bp: Int,
        roll: Int,
    ): Boolean = roll < bp.coerceIn(0, BASIS)

    /** 主机的注册域（eTLD+1 的近似）：小写、去掉末尾的点；IP 地址原样；取不到时为 null。 */
    fun registeredDomain(host: String?): String? {
        val clean = host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return null
        val labels = clean.split('.')
        val isIp = labels.all { label -> label.isNotEmpty() && label.all(Char::isDigit) }
        return when {
            isIp || labels.size <= 2 -> clean
            labels[labels.size - 2] in SECOND_LEVEL && labels.last().length == 2 -> labels.takeLast(SUFFIXED_LABELS).joinToString(".")
            else -> labels.takeLast(2).joinToString(".")
        }
    }

    const val ROLL_BOUND: Int = BASIS
}

/** 商品回流（APP-04 三端共性第 8 条）：交给 Router 的商品意图带「去掉片段」的 URL。 */
object ProductIntentUrl {
    fun withoutFragment(url: String): String = url.substringBefore('#')
}
