package com.couli.app.update

import android.app.Application
import android.content.Context
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import com.couli.app.AppModule
import com.couli.app.BuildConfig
import com.couli.app.DebugMockGate
import com.couli.app.RawFakeServerSwitch
import com.couli.app.clientcore.AppConfig
import com.couli.app.clipboard.AppClipboard
import com.couli.app.clipboard.SampleCipher
import com.couli.app.home.AppHome
import com.couli.app.login.AppLogin
import com.couli.app.me.AppMe
import com.couli.app.orders.AppOrders
import com.couli.app.privacy.AppPrivacy
import com.couli.app.trade.AppTrade
import com.couli.app.wallet.AppWallet
import com.couli.core.clientcore.BundledConfig
import com.couli.core.clientcore.ConfigReply
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clientcore.TrackerQueue
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.config.EnvironmentSwitchCleanerRegistry
import com.couli.core.network.ApiClient
import com.couli.core.network.DeviceIdReader
import com.couli.core.network.DeviceRegistry
import com.couli.core.network.InFlightCalls
import com.couli.core.network.MemoryDeviceCredentialStore
import com.couli.core.network.MemorySessionTokens
import com.couli.core.privacy.CredentialCleanerRegistry
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.privacy.RetryScheduler
import com.couli.core.push.NoPushTokenSource
import com.couli.core.router.SessionState
import com.couli.core.update.SharedPreferencesUpdateStore
import com.couli.core.update.UpdateCheckOutcome
import com.couli.core.update.UpdateCheckResult
import com.couli.core.update.UpdateGate
import com.couli.core.update.UpdatePersisted
import com.couli.core.update.UpdateStore
import java.util.UUID
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * D10-618 Q1：真正调用 AppModule 工厂，不在测试里重写页面或 NetworkHooks 的强更接线。
 * 网络只替换既有 DebugFakeLoginServer 的传输出口（SingleSettle10405Wire）；真实 ApiClient / AppUpdate / UpdateGate 全部保留。
 */
internal class SingleSettle10405Graph(
    initialScope: String = "full",
) : AutoCloseable {
    val context: Context = ApplicationProvider.getApplicationContext<Application>()
    private val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
    private val cleaners = EnvironmentSwitchCleanerRegistry()
    private val inFlight = InFlightCalls()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    val tokens = MemorySessionTokens("fixture-access", "fixture-refresh", initialScope, "fixture-user")
    private val namespace = "single_settle_10405_${UUID.randomUUID()}"
    val disk = SharedPreferencesUpdateStore(context, namespace)
    val writes = mutableListOf<UpdatePersisted>()
    val writeStacks = mutableListOf<List<StackTraceElement>>()
    private val store =
        object : UpdateStore {
            override fun load(): UpdatePersisted = disk.load()

            override fun save(value: UpdatePersisted) {
                disk.save(value)
                writes += value
                writeStacks += Throwable("记录强更持久化写入来源").stackTrace.toList()
            }
        }
    val gate = UpdateGate(store, { NOW }, VERSION, "android", "official", null)
    val update: AppUpdate
    val privacy =
        AppPrivacy(
            PrivacyGate(ConsentedStore(), { NOW }, "1").apply {
                withdrawalCleanup = {}
                retryScheduler = RetryScheduler { _, _ -> }
            },
            { true },
            { NOW },
        ).also { it.coldStart() }
    private val config =
        AppConfig(
            ConfigStore(
                { null },
                {},
                { _, done -> done(ConfigReply(ConfigReply.OK, BundledConfig.dataJson, null)) },
                { NOW },
                BundledConfig.dataJson,
            ),
            { true },
        )
    val routing: com.couli.app.routing.AppRouting
    val client: ApiClient
    private lateinit var board: AppClipboard
    private lateinit var trade: AppTrade
    private lateinit var login: AppLogin
    private lateinit var orders: AppOrders
    private lateinit var wallet: AppWallet
    val me: AppMe
    val home: AppHome

    init {
        fakeHomeOff()
        SingleSettle10405Wire.reset()
        RobolectricStartup.initialize(context)
        // 同一进程已有成功检查。后续禁止补查，防止成功复查洗掉错误实现写下的 forcedByServer。
        gate.apply(UpdateCheckResult("0.0.0", "0.0.0", VERSION, null, emptyList()), NOW)
        update = AppUpdate(gate, store, { UpdateCheckOutcome.Failure }, NoStores, scope, { false }, { NOW })
        routing = AppModule.appRouting(context, environment, cleaners, privacy, update, config)
        val device =
            DeviceRegistry(
                "android",
                DeviceIdReader { error("测试已有设备凭据") },
                MemoryDeviceCredentialStore().also { it.save("fixture-device", "fixture-secret") },
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
        val tracker =
            TrackerQueue(
                { null },
                {},
                { _: List<String> -> true },
                "full",
                { tokens.userId },
                "fixture-device",
                "android",
                VERSION,
                "fixture-session",
                { NOW },
                canUpload = { false },
            )
        val invite = AppModule.pendingInviteCode(privacy, cleaners)
        // setUp 已将 AppModule.loginBuilt 复位：AppLogin 构造发布会话时，生产门闩保证此 Provider 尚不取 login。
        // appLogin 工厂还会反向取 board / trade 接模态来源，因此保持工厂所需的构造顺序。
        board =
            AppModule.appClipboard(
                context,
                client,
                routing,
                privacy,
                config,
                tokens,
                cleaners,
                Provider { trade },
                Provider { login },
                Provider { update },
                pendingInvite = invite,
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
                invite,
                AppModule.lastLoginMethodStore(context, CredentialCleanerRegistry()),
            )
        val permissions = AppModule.permissionRequester(context, AppModule.permissionSystem(context))
        val mask = AppModule.amountMaskStore(context)
        val images = AppModule.productImages(context)
        orders =
            AppModule.appOrders(
                context,
                client,
                config,
                routing,
                tokens,
                board,
                permissions,
                Provider { update },
                mask,
                privacy,
                cleaners,
            )
        wallet =
            AppModule.appWallet(
                context,
                client,
                config,
                routing,
                tokens,
                board,
                mask,
                Provider { tracker },
                Provider { update },
                Provider { login },
            )
        routing.updateSession(SessionState.PHONE_BOUND, resumePending = false)
        me =
            AppModule.appMe(
                context,
                client,
                tokens,
                routing,
                privacy,
                update,
                config,
                login,
                board,
                mask,
                permissions,
                device,
                images,
                trade,
                wallet,
                orders,
                NoPushTokenSource(),
            )
        home =
            AppModule.appHome(
                context,
                client,
                tokens,
                routing,
                privacy,
                config,
                Provider { tracker },
                update,
                images,
                trade,
                board,
                me,
                permissions,
            )
        writes.clear()
        writeStacks.clear()
    }

    /** 新存储对象、新 Gate 只从持久层恢复，不复用 AppUpdate.ui 的状态。 */
    fun restartedGate() = UpdateGate(SharedPreferencesUpdateStore(context, namespace), { NOW }, VERSION, "android", "official", null)

    fun phoneChecksSettled(): Boolean = login.phoneChecksSettled()

    @OptIn(RawFakeServerSwitch::class)
    private fun fakeHomeOff() {
        DebugMockGate.setSwitch(context, false)
    }

    override fun close() {
        home.controller.value?.invalidate()
        me.deps.tab.leave()
        me.deps.repo.reset()
        board.onBackground()
        job.cancel()
        context.deleteSharedPreferences(namespace)
    }

    private class ConsentedStore : PrivacyStore {
        override var decisionRaw = "consented"
        override var consentedVersion: String? = "1"
        override var consentedAtMillis: Long? = 0L
        override var basicMode = false
    }

    private object NoStores : StoreAccess {
        override val installSource: String? = null

        override fun canOpen(storeKey: String) = false

        override fun open(storeKey: String) = false
    }

    companion object {
        val VERSION: String = BuildConfig.VERSION_NAME.substringBefore('-')
        const val NOW = 1_760_068_800_000L
    }
}

private object RobolectricStartup {
    fun initialize(context: Context) {
        org.robolectric.Robolectric.setupContentProvider(InitializationProvider::class.java, context.packageName + ".androidx-startup")
    }
}

/** Robolectric 会复用应用类的静态字段；每例开始与结束都恢复工厂的冷启动状态，不改业务回调。 */
internal object SingleSettle10405ProcessState {
    fun reset() {
        val module = AppModule::class.java
        module.getDeclaredField("loginBuilt").apply { isAccessible = true }.setBoolean(null, false)
        listOf("meInstance", "agentInstance", "homeInstance", "productImagesInstance", "navTickets").forEach { name ->
            module.getDeclaredField(name).apply { isAccessible = true }.set(null, null)
        }
        // 此偏好存储是单一静态实例，未按 Robolectric 每例独立的 Context / 路径分区。
        Class
            .forName("com.couli.app.MeLocalStores")
            .getDeclaredField("instance")
            .apply { isAccessible = true }
            .set(null, null)
    }
}
