package com.couli.feature.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.couli.core.bridge.AuthPages
import com.couli.core.bridge.BridgeHost
import com.couli.core.bridge.TrustedAuthDocument
import com.couli.core.bridge.TrustedAuthSync
import kotlinx.serialization.json.JsonObject

/**
 * 一个可信页面槽位（F-X-25 v2，D10-309）：跨多次切号的同一个页，页里的 WebView 与控制器每次 rebuild 都换新的一份。
 * - WebView 只经 [webViews]（[TrustedWebViewFactory]）建，建好即在当时的活动存储上（P：setProfile 在首次使用前），控制器只在这里建；
 *   每份新 WebView + 控制器建好后、首次加载之前交给 [onWebView]（外层挂视图树）。[host] 每次新建一个宿主（新桥绑定）。
 * - 切号协调器 [authSync]：构造时登记（被盖住、退出组合不注销，[destroy] 才注销）。身份未比对或有待清理时登记即隔离：这时不建 WebView，
 *   [load] 只记下入口、返回 false；协调器在存储就绪后 rebuild 时才在当前存储上新建并加载。
 * - 隔离（任何线程、同步）：当前控制器拦下桥、导航与全部请求；补清 / 比对失败时经 [TrustedWebCallbacks.onLoadFailed] 显示错误层，
 *   【重试】走 [retryAuth]（不直接加载）。
 * - 拆（retire）：当前控制器 about:blank 提交 → 下树 → 传输层 dispose → destroy，确认后 [webView] 为 null；拆除期间槽位强持有它。
 * - 建（rebuild）：在协调器给的存储上新建 WebView 与新控制器，加载入口，新文档令牌绑定后回 true；协调器确认后解除隔离、收起错误层。
 * 只在主线程使用（[TrustedAuthDocument.isolate] 除外）。
 */
@Suppress("TooManyFunctions", "LongParameterList") // 加载、重试、出栈与协调器的各个入口逐项；构造参数逐个注入
class TrustedWebPage(
    private val context: Context,
    private val webViews: TrustedWebViewFactory,
    private val config: TrustedWebConfig,
    private val host: () -> BridgeHost?,
    private val callbacks: TrustedWebCallbacks,
    private val authSync: TrustedAuthSync?,
    private val onWebView: (WebView) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())

    /** 切号隔离中（任何线程写）。 */
    @Volatile private var isolated = false

    /**
     * 隔离序号（评审 v2 r1 S1-1）：每次 [Document.isolate] 加一。新建控制器在主线程发布（volatile 写 [controller]）之后再读它：
     * 与他线程的「先写隔离位 / 序号、再读 [controller]」成对，二者必有一方看到对方——要么隔离方看到新控制器并隔离它，要么新建方看到
     * 新的序号并补隔离，不会留下一份漏隔离的文档。
     */
    @Volatile private var isolations = 0L

    /** 协调器锁内选定本页为 rebuild 目标时取的隔离序号（[TrustedAuthDocument.beginRebuild]）；用后清空。 */
    @Volatile private var rebuildBaseline: Long? = null
    private var destroyed = false

    /** 出栈中：控制器已交出销毁、WebView 还没真正释放（等关闭本容器的那次桥调用结算，共性第 53 条）；释放后才注销（评审 v2 r1 S2-03）。 */
    private var releasing = false
    private val afterRelease = mutableListOf<(Boolean) -> Unit>()

    /** 入口地址：最近一次经 [load] 交来的可信地址（隔离中被拦下的也记下，补清后由协调器在新 WebView 上加载）。 */
    private var entryUrl: String? = null

    /** 当前这份 WebView（拆后到建前为 null）。只在主线程写；volatile 供隔离线程读。 */
    @Volatile var webView: WebView? = null
        private set

    /** 当前这份控制器（与 [webView] 同生同灭）。只在主线程写；volatile 供隔离线程读（S1-1）。 */
    @Volatile var controller: TrustedWebViewController? = null
        private set

    /** 建过几份 WebView（每次新建加一）。 */
    var generation: Int = 0
        private set

    /** 拆除了当前 WebView（协调器 retire 确认）时回调，外层据此摘下视图。 */
    var onRetired: () -> Unit = {}

    /** 切号隔离中：外层【重试】要走 [retryAuth]，不能直接加载。 */
    val authBlocked: Boolean get() = isolated

    private val document = Document()
    private val registration: AutoCloseable? = authSync?.attach(document)

    /**
     * 加载 [url]：只接受可信源，授权页不进容器；隔离中只记下入口、不加载并返回 false。可信 WebView 建不出来（P 路径配置文件挂不上、
     * 系统 WebView 不可用）时 fail-closed：显示错误层、不加载（评审 v2 r1 S2-04，不再让 App 崩溃）。主线程。
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught", "SwallowedException") // 不可信、隔离中、建不出、照常加载逐项；工厂抛任何运行时异常都按建不出（错误层）
    fun load(url: String): Boolean {
        if (destroyed) return false
        // 比较基准在读隔离位之前取（评审 v2 r2 S2-1）：此后任何判定都会让序号与它不等
        val seen = isolations
        val loadable = config.allows(url) && !AuthPages.isAuthPage(url)
        // 隔离中被拦下的加载只在还没有入口地址时记下（冷启动 / 事务中新开的页）；已有入口的不被改写
        if (loadable && (!isolated || entryUrl == null)) entryUrl = url
        if (isolated) return false
        // 地址不可信：照旧建好（空白）WebView 挂在容器里、不加载，错误层由外层显示（APP-04 R3-03 既有行为）
        val current =
            controller ?: try {
                build(webViews.current(), seen).second
            } catch (error: RuntimeException) {
                callbacks.onLoadFailed()
                return false
            }
        // 建好之后再看一次：建的过程中他线程判定了隔离——补隔离（build 已按序号补过，这里对已有控制器同样兜底），不加载
        if (isolated) {
            current.isolate()
            return false
        }
        if (!loadable) return false
        return current.load(url)
    }

    /** 切号补清 / 比对失败后的【重试】：交给协调器重跑（成功后由它新建并加载），不在这里直接加载。 */
    fun retryAuth() {
        val sync = authSync ?: return
        sync.retry()
        watchSettlement()
    }

    /** 路由出栈的第一步（立即）：作废当前控制器的在途调用、停止加载。 */
    fun invalidateCalls() {
        controller?.invalidateCalls()
    }

    /**
     * 出栈：先隔离当前文档（之后的判定不必再等它），交出销毁；WebView 真正释放（关闭本容器的那次桥调用结算之后）才注销——期间协调器的
     * retire 等到释放才确认（评审 v2 r1 S2-03：不让「已注销、未销毁」的文档漏在事务之外）。只做一次。
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        isolated = true
        val current = controller
        controller = null
        webView = null
        if (current == null) {
            registration?.close()
            return
        }
        current.isolate()
        releasing = true
        current.destroy {
            releasing = false
            registration?.close()
            afterRelease.toList().forEach { it(true) }
            afterRelease.clear()
        }
    }

    /**
     * 在存储 [store] 上建一份新的 WebView 与控制器，交给外层（首次加载之前）。[seen] 是调用方开始时读到的隔离序号：发布新控制器之后
     * 序号变了（他线程在此期间判定了隔离，它可能没看到这份新控制器），立即补隔离（S1-1）。
     */
    private fun build(
        store: String,
        seen: Long,
    ): Pair<WebView, TrustedWebViewController> {
        val web = webViews.create(context, store)
        val created = TrustedWebViewController(web, config, host(), callbacks)
        webView = web
        controller = created
        generation += 1
        if (isolations != seen) created.isolate()
        onWebView(web)
        return web to created
    }

    /** 协调器结算失败而本页仍隔离：显示错误层（【重试】走 [retryAuth]）。 */
    private fun watchSettlement() {
        val sync = authSync ?: return
        main.post {
            sync.whenSettled { settled ->
                if (!settled) onMain { if (isolated && !destroyed) callbacks.onLoadFailed() }
            }
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    /** 协调器看到的本页（F-X-25 [TrustedAuthDocument]）。 */
    private inner class Document : TrustedAuthDocument {
        override val currentTrusted: Boolean get() = controller?.currentTrusted == true

        override val tokenDelivered: Boolean get() = controller?.tokenDelivered == true

        override val destroyed: Boolean get() = this@TrustedWebPage.destroyed

        override fun isolate() {
            // 先写隔离位与序号、再读控制器（与 build 的「先发布控制器、再读序号」成对，S1-1）
            isolated = true
            isolations += 1
            controller?.isolate()
            watchSettlement()
        }

        override fun notifyAuthChanged(
            data: JsonObject,
            completed: (Boolean) -> Unit,
        ) = onMain {
            val current = controller
            if (this@TrustedWebPage.destroyed || current == null) completed(false) else current.notifyAuthChanged(data, completed)
        }

        override fun retire(completed: (Boolean) -> Unit) =
            onMain {
                val current = controller
                if (current == null) {
                    // 出栈中、WebView 还没真正释放：等释放再确认（S2-03）；否则没有 WebView，立即确认
                    if (releasing) afterRelease += completed else completed(true)
                    return@onMain
                }
                // 强持有当前这份直到拆除确认（被外层丢掉也不会提前释放，iOS r2 S1-R2-01 同类）
                current.retire { done ->
                    if (done && controller === current) {
                        controller = null
                        webView = null
                        onRetired()
                    }
                    completed(done)
                }
            }

        override fun beginRebuild() {
            // 协调器在锁内选定本页为 rebuild 目标的同一时刻取比较基准（评审 v2 r2 S1-01）：之后的任何判定都会让序号与它不等
            rebuildBaseline = isolations
        }

        override fun rebuild(
            store: String,
            completed: (Boolean) -> Unit,
        ) {
            // 优先用协调器锁内取的基准；没有（未经 beginRebuild 的调用方）才在此刻读
            val seen = rebuildBaseline ?: isolations
            rebuildBaseline = null
            rebuildOnMain(store, seen, completed)
        }

        private fun rebuildOnMain(
            store: String,
            seen: Long,
            completed: (Boolean) -> Unit,
        ) = onMain {
            val url = entryUrl?.takeIf { config.allows(it) && !AuthPages.isAuthPage(it) }
            when {
                // 已在关闭（出栈、等释放）或从没有过可信入口（地址不可信，错误层由外层【重试】换配置）：不需要新文档，不建页，按完成处理
                // （评审 v2 r2 S2-2 / S2-5：不让这一页拖垮整轮切号）
                this@TrustedWebPage.destroyed || url == null -> {
                    completed(true)
                }

                isolations != seen -> {
                    // 选定目标之后又有判定：不建（新判定会重新安排），按失败
                    completed(false)
                }

                else -> {
                    // 新文档只建在新 WebView 上：上一次新建没成的半成品（同一存储、未换号）先销毁，不复用
                    controller?.let { stale ->
                        controller = null
                        webView = null
                        stale.destroy()
                        onRetired()
                    }
                    val (_, created) = build(store, seen)
                    // 新文档还在加载时本页被关闭：不再需要它，按完成处理（S2-2）
                    if (isolations != seen) {
                        completed(false)
                    } else {
                        created.loadDocument(url) { ok -> completed(ok || this@TrustedWebPage.destroyed) }
                    }
                }
            }
        }

        override fun releaseAfterRebuild() {
            // 协调器在锁内、与隔离互斥地调用：同步放下隔离位，界面回调转主线程；没有新文档（无可信入口 / 已关闭）的页不收起错误层
            isolated = false
            if (controller != null) onMain { callbacks.onAuthRecovered() }
        }
    }
}
