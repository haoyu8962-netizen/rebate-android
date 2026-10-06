package com.couli.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.app.update.AndroidStoreAccess
import com.couli.app.update.AppUpdate
import com.couli.app.update.variantUpdateChecker
import com.couli.app.update.variantUpdateStore
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentSwitchCleanerRegistry
import com.couli.core.config.SharedPreferencesNamespaceCleaner
import com.couli.core.network.AndroidKeystoreCipher
import com.couli.core.network.ApiClient
import com.couli.core.network.ClientIdentity
import com.couli.core.network.EncryptedPreferences
import com.couli.core.network.InFlightCalls
import com.couli.core.network.KeystoreSessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.NetworkHooks
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.SecretCipher
import com.couli.core.network.SecurePendingKeyBackend
import com.couli.core.network.SessionTokens
import com.couli.core.network.UnregisteredDevice
import com.couli.core.network.UpdateRaw
import com.couli.core.privacy.CredentialCleanerRegistry
import com.couli.core.privacy.MainThreadRetryScheduler
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.SharedPreferencesPrivacyStore
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
     * 切换环境时的清理入口注册表（03 §3.6）。先登记本 App 的 SharedPreferences 命名空间；
     * 会话、LKG、Keystore 等模块实现后各自注册。
     */
    @Provides
    @Singleton
    fun environmentSwitchCleaners(
        @ApplicationContext context: Context,
        tokens: SessionTokens,
        cipher: SecretCipher,
        inFlight: InFlightCalls,
    ): EnvironmentSwitchCleanerRegistry =
        EnvironmentSwitchCleanerRegistry().apply {
            // 第一步：作废并取消全部在途请求（含还在等连接的），旧环境的请求不再发出、迟到的响应不再写入（APP-03a A-R2-01）
            register { _, _ -> inFlight.onEnvironmentSwitching() }
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
     * 确需强更的 10405 调 AppUpdate.on10405（切到主线程）。设备凭据在 APP-03b 设备注册后接入，现在是未注册。
     * TODO(APP-03b): DeviceCredentials（device_id、install_secret）、10402 重新注册设备。
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
            device = UnregisteredDevice,
            tokens = tokens,
            pendingKeys = PendingKeyStore(SecurePendingKeyBackend(EncryptedPreferences(context, cipher)), System::currentTimeMillis),
            hooks = NetworkHooks(onForceUpdate = { min -> main.post { update.on10405(min) } }),
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
    ): CredentialCleanerRegistry =
        CredentialCleanerRegistry().apply {
            // 第一步：作废并取消全部在途请求（含还在等连接、TLS 的），已捕获凭据的请求不再写出（APP-03a A-R2-01）
            register(IN_FLIGHT_CALLS_CLEANER) { inFlight.onPrivacyRevoked() }
            registerPlaceholderCredentialCleaners(context)
            // 真实的会话令牌（APP-03a，含 session_scope）；h5_token 仍由占位项按键删（APP-05）
            register(SESSION_TOKENS_CLEANER) { tokens.clear() }
            val missing = CredentialCleanerRegistry.REQUIRED - registeredNames().toSet()
            check(missing.isEmpty()) { "撤回同意的清理项没有注册：$missing" }
        }

    /** 隐私闸门（APP-02b）：本机隐私决定存 couli_privacy（切换环境保留）；初始化步骤见 PrivacyGate.kt。 */
    @Provides
    @Singleton
    fun privacyGate(
        @ApplicationContext context: Context,
        credentialCleaners: CredentialCleanerRegistry,
    ): PrivacyGate =
        PrivacyGate(
            store = SharedPreferencesPrivacyStore(context),
            clock = System::currentTimeMillis,
            bundledPolicyVersion = BUNDLED_PRIVACY_POLICY_VERSION,
        ).apply {
            retryScheduler = MainThreadRetryScheduler()
            withdrawalCleanup = { credentialCleaners.cleanAll() }
            registerProductionInitSteps()
        }

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
    private const val IN_FLIGHT_CALLS_CLEANER = "in_flight_calls"
}

/** Router 的强更进度 → 网络层的强更状态（检查中按未知）。 */
private fun UpdateCheck.toNetworkUpdate(): String =
    when (this) {
        UpdateCheck.BELOW_MIN -> UpdateRaw.BELOW
        UpdateCheck.OK, UpdateCheck.BELOW_RECOMMENDED -> UpdateRaw.SUPPORTED
        UpdateCheck.CHECKING, UpdateCheck.UNKNOWN -> UpdateRaw.UNKNOWN
    }
