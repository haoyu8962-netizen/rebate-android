package com.couli.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.couli.app.clientcore.AppConfig
import com.couli.app.clientcore.AppTracker
import com.couli.app.clientcore.clientPrivacyRaw
import com.couli.app.clientcore.pendingEventsSink
import com.couli.app.clientcore.trackerCanUpload
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.app.update.AndroidStoreAccess
import com.couli.app.update.AppUpdate
import com.couli.app.update.variantUpdateChecker
import com.couli.app.update.variantUpdateStore
import com.couli.core.clientcore.AtomicTextFile
import com.couli.core.clientcore.BundledConfig
import com.couli.core.clientcore.ClientPlatformRaw
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clientcore.ExecutorPeriodicScheduler
import com.couli.core.clientcore.TrackerQueue
import com.couli.core.clientcore.TrackerSink
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentSwitchCleanerRegistry
import com.couli.core.config.EnvironmentSwitchListener
import com.couli.core.config.SharedPreferencesNamespaceCleaner
import com.couli.core.network.AndroidDeviceIdReader
import com.couli.core.network.AndroidKeystoreCipher
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiConfigFetcher
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceRegistry
import com.couli.core.network.EncryptedPreferences
import com.couli.core.network.InFlightCalls
import com.couli.core.network.KeystoreDeviceCredentialStore
import com.couli.core.network.KeystoreSessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.NetworkHooks
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.SecretCipher
import com.couli.core.network.SecurePendingKeyBackend
import com.couli.core.network.SessionTokens
import com.couli.core.network.UpdateRaw
import com.couli.core.privacy.AndroidPermissionRequester
import com.couli.core.privacy.AndroidPermissionSystem
import com.couli.core.privacy.CredentialCleanerRegistry
import com.couli.core.privacy.MainThreadRetryScheduler
import com.couli.core.privacy.PermissionPrompts
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.SharedPreferencesPrivacyStore
import com.couli.core.privacy.permissionPreferences
import com.couli.core.privacy.registerPlaceholderCredentialCleaners
import com.couli.core.privacy.registerProductionInitSteps
import com.couli.core.router.PrivacyState
import com.couli.core.router.UpdateCheck
import com.couli.core.update.UpdateGate
import com.couli.generated.ClientPlatform
import com.couli.generated.InstallChannel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.UUID
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** App 级依赖。 */
@Module
@InstallIn(SingletonComponent::class)
@Suppress("TooManyFunctions") // Hilt 的 @Provides 逐项一个函数（APP-03a 加了网络核心的令牌、加密、在途请求登记）
object AppModule {
    /**
     * 切换环境时的清理入口注册表（03 §3.6：会话、令牌、device_id、install_secret、LKG、页面缓存与 pending_action）。
     * 设备注册、配置 LKG 与埋点队列（APP-03b）经 Provider 取：清理在 Application.onCreate 的环境对账里就可能执行，不依赖它们
     * 是否已被别处创建。
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // 各清理对象逐项注入
    fun environmentSwitchCleaners(
        @ApplicationContext context: Context,
        tokens: SessionTokens,
        cipher: SecretCipher,
        inFlight: InFlightCalls,
        device: Provider<DeviceRegistry>,
        config: Provider<AppConfig>,
        tracker: Provider<TrackerQueue>,
        environmentGate: Provider<EnvironmentGate>,
        privacyGate: Provider<PrivacyGate>,
    ): EnvironmentSwitchCleanerRegistry =
        EnvironmentSwitchCleanerRegistry().apply {
            // 运行中切换环境（调试面板，在主线程）：开始时同步关环境闸门，并在任何等待之前同步作废网络世代、取消全部在途请求
            // （lessons 10、17）；清理与提交期间不注册设备、不发任何请求。结束后开闸门，并重新开始设备注册（从第 1 次按退避）、
            // 冷启动拉配置（R1-05）。失败（R2-03）时环境保持旧环境，但清理可能已清掉 device_id、install_secret 与 LKG：同样重新开始
            // 注册与拉取，恢复已被清除的初始化状态（旧环境里剩下的数据仍属于旧环境）。
            addSwitchListener(
                object : EnvironmentSwitchListener {
                    override fun onSwitchStarting() {
                        environmentGate.get().closeForSwitch()
                        inFlight.onEnvironmentSwitching()
                    }

                    override fun onSwitchFinished(committed: Boolean) {
                        environmentGate.get().reopenAfterSwitch()
                        privacyGate.get().restartDeviceRegistration()
                        config.get().onEnvironmentSwitched()
                    }
                },
            )
            // 第一步：作废并取消全部在途请求（含还在等连接的），旧环境的请求不再发出、迟到的响应不再写入（APP-03a A-R2-01）
            register { _, _ -> inFlight.onEnvironmentSwitching() }
            // APP-03b：device_id、install_secret（内存与安全存储）、LKG 配置文件、埋点队列（带旧环境的 device_id）
            register { _, _ -> device.get().clear() }
            register { _, _ -> config.get().store.clearForEnvironmentSwitch() }
            register { _, _ -> tracker.get().clear() }
            // 会话令牌（APP-03a）先清，再清整个 couli.session 等命名空间，最后删 Keystore 里的会话密钥（旧密文从此解不开）
            register { _, _ -> tokens.clear() }
            register(SharedPreferencesNamespaceCleaner(context))
            register { _, _ -> cipher.destroyKey() }
        }

    /** 在途请求登记（APP-03a）：网络核心与撤回同意、环境切换两处清理共用，清理时立即取消在途请求。 */
    @Provides
    @Singleton
    fun inFlightCalls(): InFlightCalls = InFlightCalls()

    /** 会话与未决记录的加密密钥（Android Keystore AES-GCM，APP-03a）。 */
    @Provides
    @Singleton
    fun secretCipher(): SecretCipher = AndroidKeystoreCipher()

    /** 会话令牌（access、refresh、session_scope）：Keystore 加密后存 couli.session（APP-03a；登录流程在 APP-05）。 */
    @Provides
    @Singleton
    fun sessionTokens(
        @ApplicationContext context: Context,
        cipher: SecretCipher,
    ): SessionTokens = KeystoreSessionTokens(EncryptedPreferences(context, cipher))

    /**
     * 网络核心（APP-03a）：环境闸门未通过时不发请求；隐私结论与撤回清理状态决定带不带凭据；强更结论给 ErrorActionMapper，
     * 确需强更的 10405 调 AppUpdate.on10405（切到主线程）。设备凭据与 10402 重新注册来自设备注册 [DeviceRegistry]（APP-03b）。
     * TODO(N-19): X-App-Id 的取值契约里没有登记，先用占位 couli。
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // 环境、隐私、强更、令牌、加密逐项注入
    fun apiClient(
        @ApplicationContext context: Context,
        environmentGate: EnvironmentGate,
        cleaners: EnvironmentSwitchCleanerRegistry,
        privacy: AppPrivacy,
        update: AppUpdate,
        tokens: SessionTokens,
        cipher: SecretCipher,
        inFlight: InFlightCalls,
        device: DeviceRegistry,
    ): ApiClient {
        val main = Handler(Looper.getMainLooper())
        return ApiClient(
            identity =
                ClientIdentity(
                    appId = "couli",
                    platform = ClientPlatform.ANDROID.raw,
                    appVersion = BuildConfig.VERSION_NAME.substringBefore('-'),
                    build = BuildConfig.VERSION_CODE.toString(),
                    channel = InstallChannel.OFFICIAL.raw,
                ),
            baseUrl = { if (environmentGate.isSettled) currentApiBaseUrl(context, cleaners) else null },
            context = {
                NetworkContext(
                    privacy =
                        when (privacy.conclusion) {
                            PrivacyState.CONSENTED -> PrivacyRaw.FULL
                            PrivacyState.BASIC_MODE -> PrivacyRaw.BASIC
                            PrivacyState.NOT_DECIDED -> PrivacyRaw.UNDECIDED
                        },
                    environmentSettled = environmentGate.isSettled,
                    // 只看「待清理」标记，不在网络线程里重试清理（清理由主线程的 PrivacyGate 负责）
                    credentialsReadable = !privacy.credentialAccess.cleanupPending,
                    update = update.routerUpdate.toNetworkUpdate(),
                )
            },
            device = device,
            tokens = tokens,
            pendingKeys = PendingKeyStore(SecurePendingKeyBackend(EncryptedPreferences(context, cipher)), System::currentTimeMillis),
            hooks =
                NetworkHooks(
                    onForceUpdate = { min -> main.post { update.on10405(min) } },
                    // 10402：单飞重新注册设备后沿用原键重放 1 次（BR-ID-09）
                    reRegisterDevice = device::reRegister,
                ),
            inFlight = inFlight,
        ).also { client ->
            // 强更状态恢复为受支持时，受限会话屏障到期（R1-10、lessons 19）：状态源一变就通知，不只靠下一个请求观察
            update.onRouterUpdate { client.onUpdateStateChanged(it.toNetworkUpdate()) }
        }
    }

    /**
     * 环境闸门：对账动作按构建类型不同（debug / staging 按显式选择或构建默认，release 只有 prod；
     * 见各源码集的 reconcileEnvironmentAtStartup）。Application.onCreate 调 settleAtStartup()。
     */
    @Provides
    @Singleton
    fun environmentGate(
        @ApplicationContext context: Context,
        cleaners: EnvironmentSwitchCleanerRegistry,
    ): EnvironmentGate = EnvironmentGate { reconcileEnvironmentAtStartup(context, cleaners) }

    /**
     * 撤回同意时的本地清理入口（BR-ID-13）：令牌、device_id、install_secret。会话与设备注册实现前先注册占位清理项
     * （清 couli.session 里约定的键）；三项缺一即启动失败（漏注册 = 漏删）。
     */
    @Provides
    @Singleton
    fun credentialCleaners(
        @ApplicationContext context: Context,
        tokens: SessionTokens,
        inFlight: InFlightCalls,
        device: Provider<DeviceRegistry>,
        tracker: Provider<TrackerQueue>,
    ): CredentialCleanerRegistry =
        CredentialCleanerRegistry().apply {
            // 第一步：作废并取消全部在途请求（含还在等连接、TLS 的），已捕获凭据的请求不再写出（APP-03a A-R2-01）
            register(IN_FLIGHT_CALLS_CLEANER) { inFlight.onPrivacyRevoked() }
            registerPlaceholderCredentialCleaners(context)
            // 真实的会话令牌（APP-03a，含 session_scope）；h5_token 仍由占位项按键删（APP-05）
            register(SESSION_TOKENS_CLEANER) { tokens.clear() }
            // 设备注册（APP-03b）：内存里的 device_id、install_secret 与安全存储（占位项按同名键也删一次）；作废在途注册
            register(DEVICE_REGISTRY_CLEANER) { device.get().clear() }
            // 埋点队列（APP-03b，BR-ID-13）：撤回同意立即清空，之后不再采集、不再上报
            register(TRACKER_QUEUE_CLEANER) { tracker.get().clear() }
            val missing = CredentialCleanerRegistry.REQUIRED - registeredNames().toSet()
            check(missing.isEmpty()) { "撤回同意的清理项没有注册：$missing" }
        }

    /** 隐私闸门（APP-02b）：本机隐私决定存 couli_privacy（切换环境保留）；初始化步骤见 PrivacyGate.kt。 */
    @Provides
    @Singleton
    fun privacyGate(
        @ApplicationContext context: Context,
        credentialCleaners: CredentialCleanerRegistry,
        device: Provider<DeviceRegistry>,
    ): PrivacyGate =
        PrivacyGate(
            store = SharedPreferencesPrivacyStore(context),
            clock = System::currentTimeMillis,
            bundledPolicyVersion = BUNDLED_PRIVACY_POLICY_VERSION,
        ).apply {
            retryScheduler = MainThreadRetryScheduler()
            withdrawalCleanup = { credentialCleaners.cleanAll() }
            // 设备注册（APP-03b）在后台执行，失败按已有退避重试
            registerProductionInitSteps { onResult -> device.get().registerAsync(onResult) }
            val main = Handler(Looper.getMainLooper())
            // 10402 后重新注册失败（凭据已丢）：回到主线程从第 1 次开始按退避重新注册
            device.get().onCredentialsLost = { main.post { restartDeviceRegistration() } }
        }

    /**
     * 设备注册（APP-03b，BR-ID-09）：ANDROID_ID → device_hash → POST /v1/devices；device_id、install_secret 都进 Keystore 加密的
     * couli.session（install_secret 只进安全存储，CSB-05；切换环境随命名空间清、Keystore 密钥删除）。网络核心与隐私闸门经 Provider
     * 延迟取（两者都依赖它）。
     */
    @Provides
    @Singleton
    fun deviceRegistry(
        @ApplicationContext context: Context,
        cipher: SecretCipher,
        inFlight: InFlightCalls,
        privacy: Provider<AppPrivacy>,
        client: Provider<ApiClient>,
    ): DeviceRegistry =
        DeviceRegistry(
            platform = ClientPlatformRaw.ANDROID,
            idReader = AndroidDeviceIdReader(context),
            store = KeystoreDeviceCredentialStore(EncryptedPreferences(context, cipher)),
            privacy = { privacy.get().let { clientPrivacyRaw(it.conclusion, it.credentialAccess.cleanupPending) } },
            client = { client.get() },
            epochs = inFlight::epochs,
        )

    /**
     * 配置与 LKG（APP-03b，03 §4.3）：LKG 存 filesDir（不在 cacheDir：「清除缓存」不清）；拉取经网络核心（getConfig，基本模式白名单内）；
     * 没有 LKG 用包内 default_config.json。切换环境由清理入口删 LKG。
     */
    @Provides
    @Singleton
    fun appConfig(
        @ApplicationContext context: Context,
        environmentGate: EnvironmentGate,
        client: Provider<ApiClient>,
    ): AppConfig {
        val file = AtomicTextFile(File(context.filesDir, LKG_CONFIG_FILE))
        val fetcher = ApiConfigFetcher({ client.get() })
        val store =
            ConfigStore(
                readFile = file::read,
                writeFile = file::write,
                fetcher = fetcher::fetch,
                clock = System::currentTimeMillis,
                bundled = BundledConfig.dataJson,
            )
        return AppConfig(store, environmentSettled = { environmentGate.isSettled })
    }

    /**
     * 埋点队列（APP-03b，03 §4.7）：队列存 filesDir；只在已同意时采集（隐私结论变化时通知队列，撤回立即清空）；强更检查有结果才上报；
     * 上报 sink 暂为空实现（TODO(N-20)：没有 /v1/events）。每 10 秒定时上报（R1-08，后台线程；只在已同意时运行）由 [AppTracker] 在
     * 真实启动流程里启动（R2-04）。
     * user_id 取自网络核心的有效会话（R1-09：会话隔离中、读不出时为空，游客只带 device_id）；session_id 按进程。
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // 隐私、强更、设备、会话、在途世代逐项注入
    fun trackerQueue(
        @ApplicationContext context: Context,
        privacy: AppPrivacy,
        update: AppUpdate,
        device: DeviceRegistry,
        client: Provider<ApiClient>,
        inFlight: InFlightCalls,
    ): TrackerQueue {
        val file = AtomicTextFile(File(context.filesDir, TRACKER_QUEUE_FILE))
        val current = { clientPrivacyRaw(privacy.conclusion, privacy.credentialAccess.cleanupPending) }
        return TrackerQueue(
            readFile = file::read,
            writeFile = file::write,
            sink = TrackerSink(::pendingEventsSink),
            privacy = current(),
            userId = { client.get().activeUserId() },
            deviceId = { device.deviceId },
            platform = ClientPlatformRaw.ANDROID,
            appVersion = BuildConfig.VERSION_NAME.substringBefore('-'),
            sessionId = { PROCESS_SESSION_ID },
            clock = System::currentTimeMillis,
            canUpload = { trackerCanUpload(update.routerUpdate) },
            channel = { InstallChannel.OFFICIAL.raw },
            // 上报批次的归属（R2-05）：撤回同意、切换环境（InFlightCalls 的隐私 / 环境世代）之后，已取出的批次不再交给 sink
            epochs = inFlight::epochs,
        ).also { queue ->
            privacy.onConclusionChanged { queue.onPrivacyChanged(current()) }
            update.onRouterUpdate { if (trackerCanUpload(it)) queue.flush() }
        }
    }

    /** Tracker 的启动接线（R2-04）：CouliApplication 在环境与隐私就绪后显式初始化队列并启动每 10 秒的定时上报。 */
    @Provides
    @Singleton
    fun appTracker(
        environmentGate: EnvironmentGate,
        tracker: Provider<TrackerQueue>,
    ): AppTracker = AppTracker({ tracker.get() }, ExecutorPeriodicScheduler(), environmentSettled = { environmentGate.isSettled })

    /** 真实启动流程（CouliApplication、MainActivity，R2-04）。 */
    @Provides
    @Singleton
    fun appStartup(
        environmentGate: EnvironmentGate,
        config: AppConfig,
        privacy: AppPrivacy,
        update: AppUpdate,
        tracker: AppTracker,
    ): AppStartup = AppStartup(environmentGate, config, privacy, update, tracker)

    /** 系统权限（APP-03b，03 §4.9）：状态与申请；MainActivity 提供当前 Activity。 */
    @Provides
    @Singleton
    fun permissionSystem(
        @ApplicationContext context: Context,
    ): AndroidPermissionSystem = AndroidPermissionSystem(context, permissionPreferences(context))

    /** 申请系统权限的唯一入口（APP-03b PermissionRequester；节流记录在 couli_permission，切换环境与清除缓存都不清）。 */
    @Provides
    @Singleton
    fun permissionRequester(
        @ApplicationContext context: Context,
        system: AndroidPermissionSystem,
    ): AndroidPermissionRequester = AndroidPermissionRequester(system, PermissionPrompts(), permissionPreferences(context))

    @Provides
    @Singleton
    fun appPrivacy(
        gate: PrivacyGate,
        environmentGate: EnvironmentGate,
    ): AppPrivacy = AppPrivacy(gate, environmentSettled = { environmentGate.isSettled })

    /**
     * 强更检查（APP-02c）：UpdateGate（core:config com.couli.core.update；结论 Release 存 couli.update，debug / staging 存
     * couli.update_debug，见各源码集的 variantUpdateStore）+ 检查器（debug / staging 读契约示例的假数据，Release 永远失败的占位，
     * 见各源码集的 variantUpdateChecker）+ 本机商店能力。只在环境 settled 且已同意隐私政策时发检查。
     */
    @Provides
    @Singleton
    fun appUpdate(
        @ApplicationContext context: Context,
        environmentGate: EnvironmentGate,
        privacy: AppPrivacy,
    ): AppUpdate {
        // Release：couli.update；debug / staging：couli.update_debug（调试假结果不进 Release 读取的存储，R1-03）
        val store = variantUpdateStore(context)
        // TODO(N-17): 渠道包（huawei）接入后按构建渠道取 X-Channel；现在只有 Android 通用包
        val channel = InstallChannel.OFFICIAL.raw
        val stores = AndroidStoreAccess(context, channel)
        val gate =
            UpdateGate(
                store = store,
                clock = System::currentTimeMillis,
                appVersion = BuildConfig.VERSION_NAME.substringBefore('-'),
                platform = ClientPlatform.ANDROID.raw,
                channel = channel,
                installSource = stores.installSource,
            )
        return AppUpdate(
            gate = gate,
            store = store,
            checker = variantUpdateChecker(context),
            stores = stores,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            canCheck = { environmentGate.isSettled && privacy.conclusion == PrivacyState.CONSENTED },
        ).also { update ->
            // 同意隐私政策（含基本模式补同意）后才补冷启动检查（BR-ID-11：同意前不发版本检查）
            privacy.onConclusionChanged { update.onCheckAllowedChanged() }
        }
    }

    /** 路由接线（APP-02a）：启动意图排队、统一入口守卫、pending_action；隐私结论来自 [AppPrivacy]（APP-02b），强更来自 [AppUpdate]（APP-02c）。 */
    @Provides
    @Singleton
    fun appRouting(
        @ApplicationContext context: Context,
        environmentGate: EnvironmentGate,
        cleaners: EnvironmentSwitchCleanerRegistry,
        privacy: AppPrivacy,
        update: AppUpdate,
    ): AppRouting =
        AppRouting(
            context,
            environmentGate,
            initialPrivacy = privacy.conclusion,
            privacyHooks =
                RoutingPrivacyHooks(
                    promptShowing = { privacy.promptShowing },
                    credentialsReadable = { privacy.credentialAccess.readable() },
                ),
            initialUpdate = update.routerUpdate,
        ).also { routing ->
            // 强更结论每次变化：写入 Router 的 update 状态源（重排 pending_action 恢复并 drain；below_min 时守卫删待办，F-AND-07）
            update.onRouterUpdate { routing.update.value = it }
            // 切换环境：作废进行中的推送回查、清 pending_action（R1-03）
            cleaners.register { _, _ -> routing.onEnvironmentSwitched() }
            // 隐私结论每次变化：Router.onPrivacyChanged、重排 pending_action 恢复、drain（AppRouting.privacy 的订阅）
            privacy.onConclusionChanged { routing.privacy.value = it }
            // 主动同意 = 完成一个前置步骤；明确仍不同意 = 丢弃本次待办（APP-02b R1-03、R1-04）
            privacy.onConsentEvent(routing::onConsentEvent)
        }

    /**
     * 包内隐私政策版本（整数，BR-ID-12）。TODO(N-16): 法务定稿的包内协议副本与版本号；接入 /v1/config.legal 后
     * 按「实际展示的版本」记录（rule-gaps：§2.1 API 只有包内版本）。
     */
    private const val BUNDLED_PRIVACY_POLICY_VERSION = "1"

    private const val SESSION_TOKENS_CLEANER = "session_tokens"
    private const val DEVICE_REGISTRY_CLEANER = "device_registry"
    private const val TRACKER_QUEUE_CLEANER = "tracker_queue"
    private const val LKG_CONFIG_FILE = "couli_config/lkg.json"
    private const val TRACKER_QUEUE_FILE = "couli_tracker/queue.json"

    /** 埋点的 session_id：本进程一次（03 §4.7 公共字段）。 */
    private val PROCESS_SESSION_ID: String = UUID.randomUUID().toString()
    private const val IN_FLIGHT_CALLS_CLEANER = "in_flight_calls"
}

/** Router 的强更进度 → 网络层的强更状态（检查中按未知）。 */
private fun UpdateCheck.toNetworkUpdate(): String =
    when (this) {
        UpdateCheck.BELOW_MIN -> UpdateRaw.BELOW
        UpdateCheck.OK, UpdateCheck.BELOW_RECOMMENDED -> UpdateRaw.SUPPORTED
        UpdateCheck.CHECKING, UpdateCheck.UNKNOWN -> UpdateRaw.UNKNOWN
    }
