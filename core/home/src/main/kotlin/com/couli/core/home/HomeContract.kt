package com.couli.core.home

import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.ResultSignals
import com.couli.core.wallet.SensitiveOutcome
import com.couli.generated.DefaultTexts

/**
 * 首页控制器的固定身份（brief §0「身份构造时一次性取、之后只比较」，APP-09 第 63、65 条，APP-12 第 6、13、18 条）：身份档 + 账号 + 登录世代 + 隐私 / 环境世代 +
 * 环境名。控制器在构造时取得一次，拉取、写 LKG、写关闭记录、写弹窗计数、渲染回调都只用这一份；读「当前身份」只用于比较。
 */
data class HomeIdentity(
    val level: String,
    val account: String?,
    val loginEpoch: Long,
    val epochs: CallEpochs,
    val env: String,
    /** 与固定身份来自同一快照的持久化登录世代；未知为 null。 */
    val partition: String? = null,
) {
    /** LKG 分区（G16 ②）。 */
    val scope: String get() = HomeLkgScope.of(level, account, env)

    val basic: Boolean get() = level == HomeLevel.BASIC
}

/**
 * 待定 operation 的一次调用（G02、G03）：[anonymous] = 基本模式（不带 Authorization、X-Device-Id、签名，BR-ID-11）；[expectedAccount] 是发起时固定的账号；
 * [allowed] 是执行层的发送前闸门（42901 禁用期、归属），实现方在每次实际「发送」前调用，为 false 返回本地拒绝 call_cancelled；[stillOwned] 是归属。
 */
class HomeCall(
    val anonymous: Boolean,
    val expectedAccount: String?,
    val allowed: () -> Boolean,
    val stillOwned: () -> Boolean,
    /** 本次请求所属首页固定身份的持久化登录世代（与写入票据同源）；未知为 null。只读观察。 */
    val loginEpoch: String? = null,
)

/**
 * 首页的待定 operation（G02 `GET /v1/pages/{page_key}`、G03 `GET /v1/pages/{page_key}/sections/{section_id}/items`）：锁定契约里没有，不进生成的 ApiOperations；
 * **只由 debug / staging 的本机假服务实现**（且要求当前环境允许 MOCK_DATA，F-X-02），Release 为 null——首页只显示 LKG / 包内默认，商品池与物料卡隐藏。
 * TODO(N-35) 契约补上后改为真实接口，三级兜底的代码路径不变。
 */
interface HomeContractPort {
    /** data：`{page_key, version, ttl_sec?, sections}`。 */
    suspend fun getPage(
        pageKey: String,
        call: HomeCall,
    ): ApiResult

    /**
     * data：`{items: ProductCard[], next_cursor}`（游标字段名代拟，与 ArticleListData 同名）。请求只带页面键、section id 与游标，**不带频道、排序参数**
     * （AC-S1-90 ③）。
     */
    suspend fun listSectionItems(
        pageKey: String,
        sectionId: String,
        cursor: String?,
        call: HomeCall,
    ): ApiResult
}

/** 首页文案（BR-TEXT-12：契约字典 → 草稿表）。 */
fun interface HomeTexts {
    fun text(key: String): String
}

/**
 * 首页的统一错误消息（APP-06a 第 6、12 条，APP-07 第 9 条，APP-08 第 8、16 条）：5xxxx 由这里独立追加 trace_id 末 6 位，不依赖文案里有 {trace6} 占位符；
 * 未收录的 5xxxx 回落 error.50001；没有 trace_id 时用不带编号的 fallback。
 */
object HomeErrors {
    const val GENERIC = "error.50001"
    const val RATE_LIMITED = "error.42901"

    /** 失败结果 → 文案键（42901、5xxxx、其余一律 error.50001）。 */
    fun keyOf(result: ApiResult): String =
        when (result) {
            is ApiResult.Failure -> {
                when {
                    result.code == ResultSignals.RATE_LIMITED -> RATE_LIMITED
                    result.code / SERVER_DIVISOR == SERVER_CLASS -> SensitiveOutcome.serverKey(result.code)
                    else -> "error.${result.code}".takeIf { DefaultTexts.text(it) != null } ?: GENERIC
                }
            }

            else -> {
                GENERIC
            }
        }

    /**
     * 一次失败结果 → [HomeFailure]（R2-04，共性第 35 条）：保留实际错误码与 trace_id，文案键按 [keyOf]；42901 不带编号。[gated] = 本请求被发送前限流拦下
     * （本地拒绝、没有发出）：按 42901 处理。下拉刷新、信息流、商品池、公告、StateLoadFailed 都经这里，再经 [message] 出文案。
     */
    fun failure(
        result: ApiResult?,
        gated: Boolean = false,
    ): HomeFailure {
        if (gated) return HomeFailure(ResultSignals.RATE_LIMITED, RATE_LIMITED, null)
        val failed = result as? ApiResult.Failure
        val key = result?.let(::keyOf) ?: GENERIC
        return HomeFailure(failed?.code, key, failed?.traceId?.takeIf { key != RATE_LIMITED })
    }

    /** 文案 + trace6（统一错误消息函数）。 */
    fun message(
        key: String,
        traceId: String?,
    ): String {
        val trace = ResultSignals.trace6(traceId)
        if (trace.isEmpty()) {
            return DefaultTexts.fallback(key) ?: DefaultTexts.text(key)?.replace(TRACE_SLOT, "").orEmpty()
        }
        val text = (DefaultTexts.text(key) ?: DefaultTexts.fallback(key).orEmpty()).replace(TRACE_PLACEHOLDER, trace)
        return ResultSignals.withTrace6(text, traceId)
    }

    private const val SERVER_DIVISOR = 10_000
    private const val SERVER_CLASS = 5
    private const val TRACE_PLACEHOLDER = "{trace6}"
    private const val TRACE_SLOT = "（{trace6}）"
}

/** 首页的一次局部失败（R2-04）：实际错误码（本地失败为 null）、文案键与 trace_id；展示文案只经 [HomeErrors.message]。 */
data class HomeFailure(
    val code: Int?,
    val key: String,
    val traceId: String?,
) {
    val message: String get() = HomeErrors.message(key, traceId)

    val rateLimited: Boolean get() = key == HomeErrors.RATE_LIMITED
}

/**
 * 首页文案草稿表（G28，TODO(N-35)：契约 texts.default.json 没有任何 home.* 键）：逐字照 Main / MainPopup 稿或标「代拟」。已在契约字典里的键（error.*、
 * external_page.retry、privacy.basic_mode.*）一律取契约，不在这里重复建。HomePreview 的文字只在调试源码集，不进这里（G24 ⑧）。
 * 整张表都是「待定稿，不得提审」：契约补键后删除本表、改读 DefaultTexts。
 */
object HomeDraftTexts : HomeTexts {
    const val NOTICE_CLOSE = "home.notice.close"
    const val NOTICE_ICON = "home.notice.icon"
    const val POPUP_CLOSE = "home.popup.close"
    const val POPUP_NAME = "home.popup.name"
    const val BANNER_IMAGE = "home.banner.image"
    const val BANNER_POSITION = "home.banner.position"
    const val SCROLL_MORE = "home.scroll.more"
    const val ASK_AI = "home.search.ask_ai"
    const val LOAD_FAILED_TITLE = "home.load_failed.title"
    const val ICON_IMAGE = "home.icon.image"
    const val ACTIVITY_IMAGE = "home.activity.image"
    const val RETRY = "external_page.retry"

    /** 首页 / 基本模式首页的搜索入口占位（G-V09：三端同键；08 未登记，并入 CT-16f 回写）。搜索页输入框占位不用这个键。 */
    const val SEARCH_PLACEHOLDER = "search.placeholder"

    /** 游客价值卡（BR-TEXT-14 表 C，TODO(CT-16f) 契约补键后改读 DefaultTexts）。 */
    const val GUEST_VALUE_DESC = "home.guest_value.desc"
    const val GUEST_VALUE_LOGIN = "home.guest_value.login"

    /** 读屏拼接多段文字的分隔（中文逗号，代拟）。 */
    const val SEPARATOR = "，"

    val DRAFTS: Map<String, String> =
        mapOf(
            // 照 Main 稿（公告条关闭按钮读屏「关闭公告」）
            NOTICE_CLOSE to "关闭公告",
            // 代拟：公告图标的读屏（装饰，读屏跳过时不用）
            NOTICE_ICON to "公告",
            // 代拟（G28）：弹窗关闭按钮、弹窗读屏名、banner 无 alt 时的读屏名
            POPUP_CLOSE to "关闭",
            POPUP_NAME to "活动弹窗",
            BANNER_IMAGE to "活动图片",
            // 代拟：轮播的位置读屏
            BANNER_POSITION to "第 {index} 张，共 {count} 张",
            // 照 Main 稿「更多 ›」（箭头是图形，不是文字）
            SCROLL_MORE to "更多",
            // 照 Main 稿搜索框右侧【问 AI】（G26 固定区）
            ASK_AI to "问 AI",
            // 照 StateLoadFailed 稿标题（正文用 error.50001，按钮用 external_page.retry）
            LOAD_FAILED_TITLE to "加载失败",
            // 代拟：金刚区图标加载失败时的占位读屏（整格读格名，图标本身不单独读）
            ICON_IMAGE to "图标",
            // 代拟（G28，R1-08）：活动入口只有图片、没有标题时，整个入口的读屏名
            ACTIVITY_IMAGE to "活动入口",
            // 照 Main-v3 / HomeGuest-v3 搜索入口占位（G-V09）
            SEARCH_PLACEHOLDER to "搜商品名，或粘贴商品链接",
            // 照 HomeGuest-v3 游客价值卡（08 表 C home.guest_value.desc / .login；TODO(CT-16f)）
            GUEST_VALUE_DESC to "下单前先在凑狸查返利",
            GUEST_VALUE_LOGIN to "登录领返利",
        )

    override fun text(key: String): String = DRAFTS[key] ?: DefaultTexts.text(key) ?: DefaultTexts.fallback(key).orEmpty()

    /** 带 {变量} 的文案。 */
    fun format(
        key: String,
        vars: Map<String, String>,
    ): String = vars.entries.fold(text(key)) { acc, (name, value) -> acc.replace("{$name}", value) }
}
