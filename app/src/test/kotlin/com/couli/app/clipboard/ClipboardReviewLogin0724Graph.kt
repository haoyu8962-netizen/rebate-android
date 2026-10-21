@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.clipboard

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import com.couli.app.AppModule
import com.couli.app.clientcore.AppConfig
import com.couli.app.login.AppLogin
import com.couli.app.orders.AppOrders
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.trade.AppTrade
import com.couli.app.update.AppUpdate
import com.couli.app.update.StoreAccess
import com.couli.app.wallet.AppWallet
import com.couli.core.clientcore.ConfigReply
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clientcore.TrackerQueue
import com.couli.core.config.CouliPreferenceNamespaces
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.config.EnvironmentSwitchCleanerRegistry
import com.couli.core.config.SharedPreferencesNamespaceCleaner
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiClientPageApi
import com.couli.core.network.DeviceIdReader
import com.couli.core.network.DeviceRegistry
import com.couli.core.network.InFlightCalls
import com.couli.core.network.MemoryDeviceCredentialStore
import com.couli.core.network.MemorySessionTokens
import com.couli.core.privacy.CredentialCleanerRegistry
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.privacy.RetryScheduler
import com.couli.core.router.PrivacyState
import com.couli.core.update.MemoryUpdateStore
import com.couli.core.update.UpdateCheckOutcome
import com.couli.core.update.UpdateGate
import com.couli.feature.login.LoginOutcome
import com.couli.feature.wallet.ApiClientWalletApi
import java.io.File
import java.util.UUID
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/** 真正经过 AppModule.apiClient/appClipboard/appLogin，测试不复制 onSessionStored 回调。 */
internal class ClipboardReviewLogin0724Graph(
    var configJson: String = ClipboardReview0724Harness.CONFIG,
) : AutoCloseable {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val queue = ClipboardReviewQueue0724()
    private val job = Job()
    private val scope = CoroutineScope(job + queue)
    val tokens = MemorySessionTokens()
    private val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
    val cleaners = EnvironmentSwitchCleanerRegistry()
    private val inFlight = InFlightCalls()
    val credentialCleaners = CredentialCleanerRegistry()
    val lastLoginMethod = AppModule.lastLoginMethodStore(context, credentialCleaners)
    val privacy =
        AppPrivacy(
            PrivacyGate(ConsentedStore(), { NOW }, "1").apply {
                withdrawalCleanup = { credentialCleaners.cleanAll() }
                retryScheduler = RetryScheduler { _, _ -> }
            },
            { true },
            { NOW },
        ).also { it.coldStart() }
    val pendingInvite = AppModule.pendingInviteCode(privacy, cleaners)
    val routing = AppRouting(context, environment, clock = { NOW }, initialPrivacy = PrivacyState.CONSENTED)
    private val update = updateService()
    val config =
        AppConfig(
            ConfigStore({ null }, {}, { _, done -> done(ConfigReply(ConfigReply.OK, configJson, null)) }, { NOW }, configJson),
            { true },
        )
    val client: ApiClient
    var board: AppClipboard
        private set
    val login: AppLogin
    private val trade: AppTrade
    private val orders by lazy { ordersService() }
    private val wallet by lazy { walletService() }
    private var restartPrefs: String? = null

    init {
        Dispatchers.setMain(queue)
        lastLoginMethod.clear()
        cleaners.register(SharedPreferencesNamespaceCleaner(context))
        context
            .getSharedPreferences(CouliPreferenceNamespaces.CLIPBOARD, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        ClipboardReviewLogin0724Wire.calls.clear()
        ClipboardReviewLogin0724Wire.account = ""
        Robolectric.setupContentProvider(InitializationProvider::class.java, context.packageName + ".androidx-startup")
        val deviceStore = MemoryDeviceCredentialStore().also { it.save("fixture-device", "fixture-secret") }
        val device =
            DeviceRegistry(
                "android",
                DeviceIdReader { error("已有设备凭据，不读标识") },
                deviceStore,
                { "consented" },
                { client },
                { inFlight.epochs() },
            )
        client =
            AppModule.apiClient(
                context,
                environment,
                cleaners,
                privacy,
                update,
                tokens,
                SampleCipher,
                inFlight,
                device,
                Provider { login },
                Provider { orders },
                Provider { wallet },
            )
        board = newBoard()
        val tracker =
            TrackerQueue(
                { null },
                {},
                { _: List<String> -> true },
                "full",
                { tokens.userId },
                "fixture-device",
                "android",
                "1.0.0",
                "fixture-session",
                { NOW },
                canUpload = { false },
            )
        trade = AppModule.appTrade(context, client, config, routing, Provider { tracker }, Provider { privacy }, board)
        login =
            AppModule.appLogin(
                context,
                client,
                tokens,
                routing,
                update,
                privacy,
                Provider { board },
                Provider { trade },
                pendingInvite = pendingInvite,
                lastLoginMethod = lastLoginMethod,
            )
        settle()
    }

    private fun newBoard(storageContext: Context = context): AppClipboard =
        AppModule.appClipboard(
            storageContext,
            client,
            routing,
            privacy,
            config,
            tokens,
            cleaners,
            Provider { trade },
            Provider { login },
            Provider { update },
            pendingInvite = pendingInvite,
        )

    fun settle() {
        val main = shadowOf(Looper.getMainLooper())
        do {
            main.idle()
            queue.drain()
        } while (queue.size > 0 || !main.isIdle)
    }

    fun logout() {
        val task = scope.async { login.logout() }
        until("登出结算") { task.isCompleted }
        assertTrue(task.getCompleted())
    }

    fun until(
        label: String,
        condition: () -> Boolean,
    ) {
        val deadline =
            System.nanoTime() +
                java.util.concurrent.TimeUnit.SECONDS
                    .toNanos(10)
        do {
            settle()
            Thread.yield()
        } while (!condition() && System.nanoTime() < deadline)
        assertTrue(label, condition())
    }

    fun loginAs(phone: String) {
        val task = scope.async { login.service.login(phone, "123456", NOW, false) }
        // 完成屏障：等真实 IO 返回及生产 Handler 通知，不用固定 sleep 后宣称结算。
        val deadline =
            System.nanoTime() +
                java.util.concurrent.TimeUnit.SECONDS
                    .toNanos(10)
        while (!task.isCompleted && System.nanoTime() < deadline) {
            settle()
            Thread.yield()
        }
        assertTrue("真实登录请求应完成", task.isCompleted)
        assertEquals(LoginOutcome.LOGGED_IN, task.getCompleted().stateRaw)
        settle()
    }

    fun recognize(text: String = Clipboard0724Fixture.LINK) {
        val system = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        system.setPrimaryClip(ClipData.newPlainText("fixture", text))
        board.onShellAttached()
        board.onShell("Home", false)
        board.onBackStack(null, null, emptySet())
        board.onForeground(false, true)
        board.onWindowFocus(true)
        val deadline =
            System.nanoTime() +
                java.util.concurrent.TimeUnit.SECONDS
                    .toNanos(10)
        do {
            settle()
            Thread.yield()
        } while (board.busy.value && System.nanoTime() < deadline)
        assertTrue("识别完成屏障", !board.busy.value)
        settle()
    }

    fun restartClipboardBeforeNextRead() {
        board.onBackground()
        settle()
        // 从 commit 写出的 XML 复制到新命名空间，强制创建全新 SharedPreferences；绝不从旧进程缓存取值。
        val name = "clipboard-0724-restart-${UUID.randomUUID()}"
        restartPrefs = name
        val directory = File(context.applicationInfo.dataDir, "shared_prefs")
        val persisted = File(directory, "${CouliPreferenceNamespaces.CLIPBOARD}.xml")
        assertTrue("重启前确实存在已提交的磁盘文件", persisted.isFile)
        persisted.copyTo(File(directory, "$name.xml"))
        val restartedContext =
            object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this

                override fun getSharedPreferences(
                    key: String,
                    mode: Int,
                ): SharedPreferences = context.getSharedPreferences(if (key == CouliPreferenceNamespaces.CLIPBOARD) name else key, mode)
            }
        // 新对象、新 records/productSession；仅恢复已保存的 B 会话，不再次登录或手工清摘要。
        board = newBoard(restartedContext)
        settle()
    }

    fun prefs() = ClipboardPreferences(context, SampleCipher)

    private fun ordersService() =
        AppOrders(
            ApiClientPageApi(client),
            null,
            routing,
            { tokens.loginEpoch },
            { tokens.userId },
            { false },
            null,
            { false },
            {},
            null,
            null,
        )

    private fun walletService() =
        AppWallet(
            ApiClientWalletApi(client),
            null,
            routing,
            { tokens.loginEpoch },
            { tokens.userId },
            { false },
            {},
            null,
            null,
            null,
            null,
            { null },
        )

    private fun updateService(): AppUpdate {
        val store = MemoryUpdateStore()
        return AppUpdate(
            UpdateGate(store, { NOW }, "1.0.0", "android", "official", null),
            store,
            { UpdateCheckOutcome.Failure },
            object : StoreAccess {
                override val installSource: String? = null

                override fun canOpen(storeKey: String) = false

                override fun open(storeKey: String) = false
            },
            scope,
            { false },
            { NOW },
        )
    }

    override fun close() {
        board.onBackground()
        lastLoginMethod.clear()
        job.cancel()
        settle()
        Dispatchers.resetMain()
        restartPrefs?.let { context.deleteSharedPreferences(it) }
        context
            .getSharedPreferences(CouliPreferenceNamespaces.CLIPBOARD, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private class ConsentedStore : PrivacyStore {
        override var decisionRaw = "consented"
        override var consentedVersion: String? = "1"
        override var consentedAtMillis: Long? = 0L
        override var basicMode = false
    }

    companion object {
        private const val NOW = ClipboardReview0724Harness.NOW
    }
}
