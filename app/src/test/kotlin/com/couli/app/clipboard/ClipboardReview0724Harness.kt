package com.couli.app.clipboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.couli.app.routing.AppRouting
import com.couli.core.clipboard.ClipRead
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.core.router.PrivacyState
import com.couli.feature.login.PendingInviteCode
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonObject

/** 只控制工作队列，不复制协调器；逐条执行用于锁定「返回处理已入队、规则应用尚未运行」。 */
internal class ClipboardReviewQueue0724 : CoroutineDispatcher() {
    private val tasks = ConcurrentLinkedQueue<Runnable>()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        tasks.add(block)
    }

    val size: Int get() = tasks.size

    fun runOne() {
        checkNotNull(tasks.poll()) { "没有排队工作" }.run()
    }

    fun drain() {
        repeat(1_000) { if (tasks.isEmpty()) return else runOne() }
        error("队列未在有限步内结算")
    }
}

internal class ClipboardReview0724Harness(
    prefs: ClipboardPreferences,
    val queue: ClipboardReviewQueue0724 = ClipboardReviewQueue0724(),
    config: () -> JsonObject = { Clipboard0724Fixture.json(CONFIG) },
) : AutoCloseable {
    val requests = mutableListOf<ApiCall>()
    val system = CountingClipboard0724()
    var reply: CompletableDeferred<ApiResult>? = null
    private val job = Job()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val routing =
        AppRouting(
            context,
            EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() },
            clock = { NOW },
        )
    val board =
        AppClipboard(
            system = system,
            api =
                object : PageApi {
                    override suspend fun send(call: ApiCall): ApiResult {
                        requests += call // 必须在端口入口、挂起之前记请求。
                        return reply?.await() ?: Clipboard0724Fixture.success()
                    }

                    override fun epochs() = CallEpochs(0, 0)

                    override fun cancelOwned(owner: CallOwnership) = Unit
                },
            routing = routing,
            privacy = { PrivacyState.CONSENTED },
            promptShowing = { false },
            configData = config,
            prefs = prefs,
            clock = { NOW },
            workScope = CoroutineScope(job + queue),
            pendingInvite = PendingInviteCode(),
        )

    init {
        board.onShellAttached()
        board.onShell("Home", false)
        board.onBackStack(null, null, emptySet())
        board.onWindowFocus(true)
    }

    fun foreground() {
        board.onForeground(false, true)
        queue.drain()
    }

    override fun close() {
        job.cancel()
        queue.drain()
    }

    companion object {
        const val NOW = 1_800_000_000_000L
        const val CONFIG = """{"clipboard":{"consent_version":"1"},"features":{"flags":{}}}"""
    }
}
