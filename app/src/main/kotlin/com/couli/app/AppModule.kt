package com.couli.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ProcessLifecycleOwner
import com.couli.app.account.AppAccount
import com.couli.app.agent.AppAgent
import com.couli.app.agent.connectAgentScreenReader
import com.couli.app.agent.variantAgentConsentRecorded
import com.couli.app.agent.variantLocalExchange
import com.couli.app.clientcore.AppConfig
import com.couli.app.clientcore.AppTracker
import com.couli.app.clientcore.clientPrivacyRaw
import com.couli.app.clientcore.pendingEventsSink
import com.couli.app.clientcore.trackerCanUpload
import com.couli.app.clipboard.AppClipboard
import com.couli.app.clipboard.ClipboardPreferences
import com.couli.app.clipboard.connect
import com.couli.app.clipboard.connectPurchaseStatus
import com.couli.app.clipboard.connectRules
import com.couli.app.home.AppHome
import com.couli.app.home.connectScreenReader
import com.couli.app.home.homeLkgStore
import com.couli.app.home.homePrefsStore
import com.couli.app.home.variantEnvironmentKey
import com.couli.app.home.variantHomePort
import com.couli.app.login.AndroidInstalledAppProbe
import com.couli.app.login.AppLogin
import com.couli.app.login.variantWechatAdapter
import com.couli.app.me.AndroidMeSystem
import com.couli.app.me.AppMe
import com.couli.app.me.variantNicknameBackend
import com.couli.app.me.variantNotifyBackend
import com.couli.app.me.variantPushTokenSource
import com.couli.app.orders.AppOrders
import com.couli.app.orders.connectCleanup
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.NavTickets
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.app.routing.WebTargets
import com.couli.app.search.AppSearch
import com.couli.app.search.SearchDraftTexts
import com.couli.app.trade.AndroidJumpPorts
import com.couli.app.trade.AppProductImages
import com.couli.app.trade.AppTrade
import com.couli.app.trade.variantBrowserNoticeMillis
import com.couli.app.trade.variantImageSource
import com.couli.app.trade.variantJumpPorts
import com.couli.app.trade.variantWebCodeAuthorizer
import com.couli.app.update.AndroidStoreAccess
import com.couli.app.update.AppUpdate
import com.couli.app.update.variantUpdateChecker
import com.couli.app.update.variantUpdateStore
import com.couli.app.wallet.AppWallet
import com.couli.app.web.AppWeb
import com.couli.app.web.BridgeHostRegistry
import com.couli.core.bridge.WebConfigReader
import com.couli.core.clientcore.AmountMaskStore
import com.couli.core.clientcore.AtomicTextFile
import com.couli.core.clientcore.BundledConfig
import com.couli.core.clientcore.ClientPlatformRaw
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clientcore.ExecutorPeriodicScheduler
import com.couli.core.clientcore.TrackerQueue
import com.couli.core.clientcore.TrackerSink
import com.couli.core.clipboard.AndroidSystemClipboard
import com.couli.core.clipboard.ClipboardSensitive
import com.couli.core.config.CouliPreferenceNamespaces
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentSwitchCleanerRegistry
import com.couli.core.config.EnvironmentSwitchListener
import com.couli.core.config.SharedPreferencesNamespaceCleaner
import com.couli.core.me.MeLocalStore
import com.couli.core.network.AndroidDeviceIdReader
import com.couli.core.network.AndroidKeystoreCipher
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiClientPageApi
import com.couli.core.network.ApiConfigFetcher
import com.couli.core.network.ApiUpdateChecker
import com.couli.core.network.ClientIdentity
import com.couli.core.network.CredentialReadBarrier
import com.couli.core.network.DeviceRegistry
import com.couli.core.network.EncryptedPreferences
import com.couli.core.network.FileRevocationMarks
import com.couli.core.network.InFlightCalls
import com.couli.core.network.KeystoreDeviceCredentialStore
import com.couli.core.network.KeystoreSessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.NetworkHooks
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PersistedLoginEpochs
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.SecretCipher
import com.couli.core.network.SecurePendingKeyBackend
import com.couli.core.network.SessionTokens
import com.couli.core.network.UpdateRaw
import com.couli.core.orders.OrderRestoreStore
import com.couli.core.orders.OrderStaleCache
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
import com.couli.core.push.PushTokenSource
import com.couli.core.router.PrivacyState
import com.couli.core.router.UpdateCheck
import com.couli.core.tradejump.UnavailableBaichuan
import com.couli.core.update.UpdateConfigReader
import com.couli.core.update.UpdateGate
import com.couli.core.update.UpdateStatus
import com.couli.core.wallet.WalletStaleCache
import com.couli.feature.login.InvitePasteSource
import com.couli.feature.login.LegalVersions
import com.couli.feature.wallet.ApiClientWalletApi
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/** App 级依赖。 */
@Module
@InstallIn(SingletonComponent::class)
@Suppress("TooManyFunctions", "LargeClass") // Hilt 的 @Provides 逐项一个函数（APP-03a 加了网络核心的令牌、加密、在途请求登记）
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
        bridges: BridgeHostRegistry,
        readBarrier: CredentialReadBarrier = CredentialReadBarrier(),
    ): EnvironmentSwitchCleanerRegistry =
        EnvironmentSwitchCleanerRegistry().apply {
            // 运行中切换环境（调试面板，在主线程）：开始时同步关环境闸门，并在任何等待之前同步作废网络世代、取消全部在途请求
            // （lessons 10、17）；清理与提交期间不注册设备、不发任何请求。结束后开闸门，并重新开始设备注册（从第 1 次按退避）、
            // 冷启动拉配置（R1-05）。失败（R2-03）时环境保持旧环境，但清理可能已清掉 device_id、install_secret 与 LKG：同样重新开始
            // 注册与拉取，恢复已被清除的初始化状态（旧环境里剩下的数据仍属于旧环境）。
            addSwitchListener(
                object : EnvironmentSwitchListener {
                    override fun onSwitchStarting() {
                        // F-X-03（第 1 轮评审 R1-01）：屏障先关内部闸、等已准入的令牌 / 设备凭据读取结束，再作废网络世代、取消在途请求，
                        // 最后才关环境闸门（switching 对外可见）；之后清理器第一项再作废一次（覆盖作废与发布之间新登记的请求）
                        beginEnvironmentSwitchBehindBarrier(
                            readBarrier,
                            inFlight::onEnvironmentSwitching,
                            environmentGate.get()::closeForSwitch,
                        )
                    }

                    override fun onSwitchFinished(committed: Boolean) {
                        environmentGate.get().reopenAfterSwitch()
                        privacyGate.get().restartDeviceRegistration()
                        config.get().onEnvironmentSwitched()
                        // 首页（APP-11）：按新环境重建控制器（新环境作用域的 LKG → 包内默认）
                        homeInstance?.sync()
                        // Agent 页（APP-10）：按新环境重建控制器（从空对话开始）
                        agentInstance?.sync()
                    }
                },
            )
            // 第一步：作废并取消全部在途请求（含还在等连接的），旧环境的请求不再发出、迟到的响应不再写入（APP-03a A-R2-01）
            register { _, _ -> inFlight.onEnvironmentSwitching() }
            // APP-04：作废全部容器里在途的桥调用（旧环境的结果不交回页面）
            register { _, _ -> bridges.invalidateAll() }
            // APP-03b：device_id、install_secret（内存与安全存储）、LKG 配置文件、埋点队列（带旧环境的 device_id）
            register { _, _ -> device.get().clear() }
            register { _, _ -> config.get().store.clearForEnvironmentSwitch() }
            // 埋点队列（F-ALL-08 / APP-03b 共性第 23 条）：先直接删队列文件与 .tmp（不依赖队列对象能否建出来；覆盖安装导致的
            // 冷启动环境对账也走这里），再清内存里的队列
            register { _, _ -> trackerQueueFile(context).write(null) }
            register { _, _ -> tracker.get().clear() }
            // 商品图内存缓存（F-X-02，FU-AND-0714 R1-05）：旧环境（含假服务现画的假图）的位图不留到新环境；在途结果另按来源核对后丢弃
            register { _, _ -> productImagesInstance?.clearMemory() }
            // 订单缓存（APP-08，G10）：属于连接的那套服务器与账号，切换环境时删除（删不掉保留待清理、不读回）
            register { _, _ -> orderCacheStore(context).clearAll() }
            // 订单页恢复记录（FU-AND-0710 R2-02）：搜索词、筛选、锚点订单；删除确认，失败保留待清理（目录外 + 目录内标记）、不读回并报失败
            register { _, _ -> orderRestoreStore(context).clearAll() }
            // 钱包摘要缓存（APP-09，G22）：同上
            register { _, _ -> walletCacheStore(context).clearAll() }
            // 「我的」（APP-12，G29）：会话级数据（Me 快照、推送上报状态、客服半屏）；设备级偏好（couli_me_prefs）不清
            register { _, _ -> meInstance?.onEnvironmentSwitched() }
            // 首页（APP-11，G16 ②、G11）：先作废控制器与在途请求；LKG（含临时文件）、公告关闭记录与弹窗计数全清（删除确认，失败保留待清理并抛错）。
            // 直接按 context 取存储：Application 启动时的环境对账就能执行，不依赖 AppHome 是否已创建
            register { _, _ -> homeInstance?.onEnvironmentSwitching() }
            // Agent 页（APP-10 G06）：同步作废控制器（关连接、停计时、清消息与金额，不发 cancel）与还没交出的情境；对话只在内存，没有要删的本机数据。
            // 第一步的在途请求作废已经让旧环境的请求不再发出（假服务的每次发包另核对环境）
            register { _, _ -> agentInstance?.onEnvironmentSwitching() }
            register { _, _ -> homeLkgStore(context).clearAll() }
            register { _, _ -> homePrefsStore(context).clearAll() }
            // 会话令牌（APP-03a）先清，再清整个 couli.session 等命名空间，最后删 Keystore 里的会话密钥（旧密文从此解不开）
            register { _, _ -> tokens.clear() }
            register(SharedPreferencesNamespaceCleaner(context))
            register { _, _ -> cipher.destroyKey() }
        }

    /** 凭据读取屏障（F-X-03）：ApiClient、设备注册的令牌与设备凭据读取都在它里面现判；撤回同意、切换环境用它竖屏障。 */
    @Provides
    @Singleton
    fun credentialReadBarrier(): CredentialReadBarrier = CredentialReadBarrier()

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
    ): SessionTokens =
        KeystoreSessionTokens(
            EncryptedPreferences(context, cipher),
            // 令牌文件整体写不进时的独立撤销标记（F-AND-32，FU-AND-0715）：不在 couli.session 里，App 私有、不备份
            marks = FileRevocationMarks(File(context.noBackupFilesDir, KeystoreSessionTokens.REVOCATION_FILE)),
        )

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
        login: Provider<AppLogin>,
        orders: Provider<AppOrders>,
        wallet: Provider<AppWallet>,
        readBarrier: CredentialReadBarrier = CredentialReadBarrier(),
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
                    // 个性化推荐已关闭（APP-12 G03）：本机设备级偏好，网络层只在已同意时带 X-Personalization: off
                    personalizationOff = meLocalStore(context).personalizationOffNow(),
                )
            },
            device = device,
            tokens = tokens,
            pendingKeys = PendingKeyStore(SecurePendingKeyBackend(EncryptedPreferences(context, cipher)), System::currentTimeMillis),
            hooks =
                NetworkHooks(
                    // 切到主线程后、进强更之前复核这次调用的归属（APP-07 共性第 29 条）：期间已离页、切 Tab、撤回同意、切环境的迟到
                    // 整体 10405（如剪贴板 parse）不跳强更、不落盘
                    onForceUpdateChecked = { min, stillValid -> main.post { if (stillValid()) update.on10405(min) } },
                    // 刷新失败清会话（clear_session_login / clear_session_stay_force_update）：同步守卫的会话状态，普通状态下跳登录
                    // （LoginSessionExpired，APP-05a）
                    onEffect = { effect -> main.post { login.get().onNetworkEffect(effect) } },
                    // 10402：单飞重新注册设备后沿用原键重放 1 次（BR-ID-09）
                    reRegisterDevice = device::reRegister,
                    // 有效世代的 10402（含重放里的）一律先隔离所用凭据（F-ALL-08，共性第 25 条）
                    quarantineDevice = device::quarantine,
                    // 重放里再次 10402、预算用完：按清理世代登记一次恢复，启动唯一的退避注册链（R1-02）
                    recoverDevice = device::recoverAfterRejection,
                    // 会话保存、刷新、清理完成（APP-05a 共性第 14 条）：带世代切到主线程，同步 Router（受限转 full 时经守卫恢复待办）
                    onSessionStored = { generation ->
                        main.post {
                            login.get().onSessionStored(generation)
                            // 订单（APP-08）：会话换代即作废列表、详情、待跟单卡的在途请求，清掉旧账号的结果与缓存
                            orders.get().onSessionChanged()
                            // 钱包（APP-09）：会话换代即清掉已显示的余额、金额与已填内容，重置禁用期与 step-up token，按新会话重取
                            wallet.get().onSessionChanged()
                            // 「我的」（APP-12）：按新会话重算身份（清 Me 快照、移出只能登录访问的页面、推送会话跟踪）
                            meInstance?.syncIdentity()
                            // 首页（APP-11）：会话换代即作废旧控制器、按新身份重建（旧账号作用域的 LKG 清掉）
                            homeInstance?.sync()
                            // Agent 页（APP-10）：会话换代即作废旧控制器（不发 cancel），按新身份重建（从空对话开始）
                            agentInstance?.sync()
                        }
                    },
                    // 10006 的统一落点（APP-12 G09）：入口时的导航票据（AppRouting 建好之前没有，落点一律不放行）与切到主线程后的一处判断
                    navigationTicket = { navTickets?.ticket() },
                    onRiskNotice = { ticket, stillValid -> main.post { meInstance?.on10006(ticket, stillValid) } },
                ),
            inFlight = inFlight,
            // debug / staging：Debug 专用的本机假登录服务（打开时整段在本机回答，不连任何地址）；release 是普通 OkHttpClient
            http = variantHttpClient(context),
            readBarrier = readBarrier,
            // APP-10b §2 第 13 条：debug / staging 的本机 Agent 假服务在实际传输入口（写出前核对之后）按 DebugMockGate 换入；release 为 null
            local = variantLocalExchange(context),
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
    ): EnvironmentGate =
        EnvironmentGate { reconcileEnvironmentAtStartup(context, cleaners) }.also { gate ->
            // F-X-02：Debug 替身的统一闸门要求环境已就绪（Release 为空实现）
            variantBindEnvironmentGate { gate.isSettled }
        }

    /**
     * 撤回同意时的本地清理入口（BR-ID-13）：令牌、device_id、install_secret。会话与设备注册实现前先注册占位清理项
     * （清 couli.session 里约定的键）；三项缺一即启动失败（漏注册 = 漏删）。
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // 各清理对象逐项注入
    fun credentialCleaners(
        @ApplicationContext context: Context,
        tokens: SessionTokens,
        inFlight: InFlightCalls,
        device: Provider<DeviceRegistry>,
        tracker: Provider<TrackerQueue>,
        bridges: BridgeHostRegistry,
    ): CredentialCleanerRegistry =
        CredentialCleanerRegistry().apply {
            // 第一步：作废并取消全部在途请求（含还在等连接、TLS 的），已捕获凭据的请求不再写出（APP-03a A-R2-01）
            register(IN_FLIGHT_CALLS_CLEANER) { inFlight.onPrivacyRevoked() }
            // Agent 页（APP-10 G06、APP-12 第 2 条）：确认撤回的同步入口立即停流、作废票据与控制器（对话、卡片、收益金额只在内存，作废即清空）
            register(AppAgent.AGENT_CONTROLLER_CLEANER) { agentInstance?.onConsentWithdrawing() }
            // APP-04：作废全部容器里在途的桥调用（撤回之后晚到的结果不交回页面）
            register(BRIDGE_CALLS_CLEANER) { bridges.invalidateAll() }
            // 首页（APP-11 R1-01）：在任何存储清理之前先作废首页控制器与预览实例（已排程的写入不再发起；存储层的世代票据挡住已在等锁的写入）
            register(AppHome.HOME_CONTROLLER_CLEANER) { homeInstance?.onConsentWithdrawing() }
            registerPlaceholderCredentialCleaners(context)
            // 真实的会话令牌（APP-03a，含 session_scope）；h5_token 仍由占位项按键删（APP-05）
            register(SESSION_TOKENS_CLEANER) { tokens.clear() }
            // 设备注册（APP-03b）：内存里的 device_id、install_secret 与安全存储（占位项按同名键也删一次）；作废在途注册
            register(DEVICE_REGISTRY_CLEANER) { device.get().clear() }
            // 埋点队列（APP-03b，BR-ID-13）：撤回同意立即清空，之后不再采集、不再上报；文件与 .tmp 先直接删（F-ALL-08）
            register(TRACKER_QUEUE_CLEANER) {
                trackerQueueFile(context).write(null)
                tracker.get().clear()
            }
            // 剪贴板（APP-07）：本机的自写记录与 24 小时提示记录（只有 hash）一并删除；设备级开关不在撤回清理范围（BR-ID-16）
            register(CLIPBOARD_HASHES_CLEANER) { ClipboardPreferences(context).clearHashes() }
            // 订单缓存（APP-08，G10；APP-03b 撤回清理名单）：删除要确认落盘，失败保留待清理标记、清理完成之前不读回旧数据
            register(AppOrders.ORDER_CACHE_CLEANER) { orderCacheStore(context).clearAll() }
            // 订单页恢复记录（FU-AND-0710 R1-03、R2-02）：直接登记在这里，Application 启动时 PrivacyGate 的补清理同样执行并计入成败，
            // 不依赖 AppOrders 是否已创建、隐私结论通知是否发出；删除要确认，失败保留待清理并抛错
            register(AppOrders.ORDER_RESTORE_CLEANER) { orderRestoreStore(context).clearAll() }
            // 钱包摘要缓存（APP-09，G22；APP-03b 撤回清理名单）：删除要确认落盘，失败保留待清理标记、清理完成之前不读回旧数据
            register(AppWallet.WALLET_CACHE_CLEANER) { walletCacheStore(context).clearAll() }
            // 「我的」的设备级偏好（APP-12 G03、G28、G29：引导条关闭时刻、个性化开关与切换时间）：删除确认落盘，失败记待清理并抛错
            register(MeLocalStore.CLEANER) { meLocalStore(context).clearAll() }
            // 「我的」的会话级数据（G18、G29：推送上报的在途与待报、Me 快照、客服半屏；草稿随页面执行器按新身份重建作废）
            register(AppMe.ME_SESSION_CLEANER) { meInstance?.clearForConsentRevoked() }
            // Debug 假账号服务按账号保存的状态（APP-13a §2 第 11 项；Release 为空实现）
            register(FAKE_ACCOUNT_CLEANER) { variantResetFakeAccount(context) }
            // 首页（APP-11，G16 ②、G11）：LKG 全部作用域（含临时文件）与设备级的公告关闭记录、弹窗计数；直接登记在这里，启动时 PrivacyGate 的补清理同样执行，
            // 删除要确认，失败保留待清理（LKG 的标记在目录外）并抛错
            register(AppHome.HOME_LKG_CLEANER) { homeLkgStore(context).clearAll() }
            register(AppHome.HOME_PREFS_CLEANER) { homePrefsStore(context).clearAll() }
            val missing = CredentialCleanerRegistry.REQUIRED - registeredNames().toSet()
            check(missing.isEmpty()) { "撤回同意的清理项没有注册：$missing" }
        }

    /** 隐私闸门（APP-02b）：本机隐私决定存 couli_privacy（切换环境保留）；初始化步骤见 PrivacyGate.kt。 */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // 各依赖逐项注入（F-X-03 加了凭据读取屏障）
    fun privacyGate(
        @ApplicationContext context: Context,
        credentialCleaners: CredentialCleanerRegistry,
        device: Provider<DeviceRegistry>,
        trade: Provider<AppTrade>,
        push: Provider<PushTokenSource>,
        readBarrier: CredentialReadBarrier = CredentialReadBarrier(),
        inFlight: InFlightCalls = InFlightCalls(),
    ): PrivacyGate =
        PrivacyGate(
            store = SharedPreferencesPrivacyStore(context),
            clock = System::currentTimeMillis,
            bundledPolicyVersion = BUNDLED_PRIVACY_POLICY_VERSION,
        ).apply {
            retryScheduler = MainThreadRetryScheduler()
            // F-X-03（第 1 轮评审 R1-01）：撤回同意记下「待清理」（对外可见）之前，屏障先关内部闸、等已准入的读取结束、作废并取消在途请求
            credentialBarrier = withdrawalBarrier(readBarrier, inFlight::onPrivacyRevoked)
            withdrawalCleanup = { credentialCleaners.cleanAll() }
            // 设备注册（APP-03b）在后台执行，失败按已有退避重试
            registerProductionInitSteps(
                union = { trade.get().initializeUnionSdk() },
                // 推送（APP-12 G17）：只启动令牌来源（Release 为空操作，不初始化任何推送 SDK）
                push = { push.get().start() },
            ) { onResult -> device.get().registerAsync(onResult) }
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
    @Suppress("LongParameterList") // 各依赖逐项注入（F-X-03 加了凭据读取屏障）
    fun deviceRegistry(
        @ApplicationContext context: Context,
        cipher: SecretCipher,
        inFlight: InFlightCalls,
        privacy: Provider<AppPrivacy>,
        client: Provider<ApiClient>,
        environmentGate: EnvironmentGate,
        readBarrier: CredentialReadBarrier = CredentialReadBarrier(),
    ): DeviceRegistry =
        DeviceRegistry(
            platform = ClientPlatformRaw.ANDROID,
            idReader = AndroidDeviceIdReader(context),
            store = KeystoreDeviceCredentialStore(EncryptedPreferences(context, cipher)),
            privacy = { privacy.get().let { clientPrivacyRaw(it.conclusion, it.credentialAccess.cleanupPending) } },
            client = { client.get() },
            epochs = inFlight::epochs,
            // F-X-03 ③：设备凭据的每个读取入口（埋点、诊断、注册、签名）都现判环境已就绪与隐私口径
            environmentSettled = { environmentGate.isSettled },
            readBarrier = readBarrier,
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
        val file = trackerQueueFile(context)
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
     * couli.update_debug，见各源码集的 variantUpdateStore）+ 检查器（debug / staging 读契约示例的假数据；Release 经 ApiClient 请求
     * GET /v1/app-versions/check 的 [ApiUpdateChecker]，F-X-01；见各源码集的 variantUpdateChecker）+ 本机商店能力。
     * 只在环境 settled 且已同意隐私政策时发检查（基本模式不发）。回前台复查间隔取当前生效配置的 app_update.recheck_interval_sec。
     */
    @Provides
    @Singleton
    fun appUpdate(
        @ApplicationContext context: Context,
        environmentGate: EnvironmentGate,
        privacy: AppPrivacy,
        client: Provider<ApiClient>,
        config: Provider<AppConfig>,
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
        val canCheck = { environmentGate.isSettled && privacy.conclusion == PrivacyState.CONSENTED }
        val configInterval = {
            UpdateConfigReader.recheckIntervalSec(
                config
                    .get()
                    .store.snapshots.value.data,
            )
        }
        val real = ApiUpdateChecker(client = { client.get() }, allowed = canCheck, recheckIntervalSec = configInterval)
        return AppUpdate(
            gate = gate,
            store = store,
            checker = variantUpdateChecker(context, real),
            stores = stores,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            canCheck = canCheck,
            recheckIntervalSec = configInterval,
        ).also { update ->
            // 同意隐私政策（含基本模式补同意）后才补冷启动检查（BR-ID-11：同意前不发版本检查）
            privacy.onConclusionChanged { update.onCheckAllowedChanged() }
        }
    }

    /** 路由接线（APP-02a）：启动意图排队、统一入口守卫、pending_action；隐私结论来自 [AppPrivacy]（APP-02b），强更来自 [AppUpdate]（APP-02c）。 */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // 环境、隐私、强更与配置逐项注入
    fun appRouting(
        @ApplicationContext context: Context,
        environmentGate: EnvironmentGate,
        cleaners: EnvironmentSwitchCleanerRegistry,
        privacy: AppPrivacy,
        update: AppUpdate,
        config: AppConfig,
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
            // 导航票据（APP-12 G09）：网络层的入口取票据经它
            navTickets = routing.navTickets
            // 目标网页白名单（APP-04）：external_hosts 与可信 H5 源取生效配置（current → LKG → 包内）
            routing.webTargets = {
                val data = config.store.snapshots.value.data
                WebTargets(WebConfigReader.externalHosts(data), WebConfigReader.trustedOrigins(data).toList())
            }
            // 强更结论每次变化：写入 Router 的 update 状态源（重排 pending_action 恢复并 drain；below_min 时守卫删待办，F-AND-07）
            update.onRouterUpdate { routing.update.value = it }
            // 切换环境：作废进行中的推送回查、清 pending_action（R1-03）
            cleaners.register { _, _ -> routing.onEnvironmentSwitched() }
            // Debug 假账号服务的状态随环境切换清掉（APP-13a §2 第 11 项；Release 为空实现）
            cleaners.register { _, _ -> variantResetFakeAccount(context) }
            // 隐私结论每次变化：Router.onPrivacyChanged、重排 pending_action 恢复、drain（AppRouting.privacy 的订阅）
            privacy.onConclusionChanged { routing.privacy.value = it }
            // 主动同意 = 完成一个前置步骤；明确仍不同意 = 丢弃本次待办（APP-02b R1-03、R1-04）
            privacy.onConsentEvent(routing::onConsentEvent)
        }

    /**
     * 登录（APP-05a）：LoginService（ApiClient 保存会话、Router 恢复 pending_action）、守卫的会话状态、登出。协议版本取包内默认配置的
     * legal.*.version（BR-ID-04）。隐私结论、强更状态变化后重新同步会话状态（撤回清理、受限会话刷新会改变它）。
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // 各能力的来源逐项注入
    fun appLogin(
        @ApplicationContext context: Context,
        client: ApiClient,
        tokens: SessionTokens,
        routing: AppRouting,
        update: AppUpdate,
        privacy: AppPrivacy,
        clipboard: Provider<AppClipboard>,
        trade: Provider<AppTrade>,
    ): AppLogin =
        AppLogin(
            client = client,
            tokens = tokens,
            routing = routing,
            updateCheck = { update.routerUpdate },
            markBelow = { update.onServerForced() },
            legal = LegalVersions.parse(BundledConfig.dataJson),
            // 第三方登录（APP-05b）：Release 微信适配器恒不可用；Debug / Staging 本机假服务打开时是假适配器
            adapters = listOf(variantWechatAdapter(context, AndroidInstalledAppProbe(context))),
        ).also { login ->
            privacy.onConclusionChanged { login.syncSession() }
            update.onRouterUpdate { login.syncSession() }
            // 邀请码的剪贴板来源（APP-07）：游客邀请预填、邀请码行【粘贴】
            login.invite =
                object : InvitePasteSource {
                    override fun takePrefill(): String? = clipboard.get().invitePrefill.take()

                    override fun paste(): String? = clipboard.get().pasteInviteCode()

                    // G15：还没看过用途说明时先出弹层，关掉后同一次点击读一次、抽码填回这个输入框
                    override fun pasteLater(onFilled: (String?) -> Unit): Boolean {
                        val board = clipboard.get()
                        if (!board.manualNoticeNeeded()) return false
                        board.pasteInviteCode(onFilled)
                        return true
                    }

                    override val blocked: StateFlow<Boolean> get() = clipboard.get().blocked
                }
            // 剪贴板普通互斥模态的生产来源（FU-AND-0724 G28 / G29）：登录、购买、更新三个服务都已构造完成后一次接上（不在 appClipboard
            // provider 里取 AppLogin，避免循环依赖）；在 loginBuilt 之前接，订阅的初始同步不会经浮层来源回头取 AppLogin
            val board = clipboard.get()
            val shop = trade.get()
            board.connectModalSources(trade = shop, login = login, update = update)
            // 换号的生产通知（G07，代码复审 r1 S2-2）：网络层每次保存 / 清理会话都转给剪贴板，由它按登录世代判断是否换号、清商品摘要；
            // 每次取当前实例（不捕获构造时的对象）
            login.sessionStoredListener = { clipboard.get().onSessionStored() }
            loginBuilt = true
        }

    /**
     * 搜索与商品详情（APP-06a）：经 ApiClient 搜索 / 取详情（请求带页面归属）；Tab 按 ConfigStore 的 search_status 三层取值；
     * search_disabled 刷新配置（304 也算确认）；详情公开态按会话与隐私结论。
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // 网络、配置、路由、隐私、购买与剪贴板逐项注入
    fun appSearch(
        client: ApiClient,
        config: AppConfig,
        routing: AppRouting,
        privacy: AppPrivacy,
        trade: AppTrade,
        clipboard: AppClipboard,
        orders: AppOrders,
    ): AppSearch =
        AppSearch(client, config.store, routing, privacy = { privacy.conclusion }, trade = trade, clipboard = clipboard, orders = orders)

    /**
     * 订单（APP-08）：列表、详情、待跟单卡经 ApiClient（请求带页面归属与会话世代）；订单号复制经剪贴板唯一写入口（APP-07）；10405 强制进入强更
     * （服务端已判定，AppUpdate.onServerForced）；断网缓存在 noBackupFilesDir 下按账号隔离（撤回、切换环境、登出、换账号时删除）；通知行按
     * NotificationManagerCompat 与 PermissionRequester 的本机记录。会话状态每次变化（登录、退出、换账号）都通知它。
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // 网络、配置、路由、会话、剪贴板、权限与强更逐项注入
    fun appOrders(
        @ApplicationContext context: Context,
        client: ApiClient,
        config: AppConfig,
        routing: AppRouting,
        tokens: SessionTokens,
        clipboard: AppClipboard,
        permissions: AndroidPermissionRequester,
        update: Provider<AppUpdate>,
        mask: AmountMaskStore,
        privacy: AppPrivacy,
        cleaners: EnvironmentSwitchCleanerRegistry,
    ): AppOrders =
        AppOrders(
            api = ApiClientPageApi(client),
            store = config.store,
            routing = routing,
            sessionEpoch = { tokens.loginEpoch },
            account = { client.activeUserId() },
            writer = { text -> clipboard.writer.write(text) },
            permissions = permissions,
            notificationsEnabled = { NotificationManagerCompat.from(context).areNotificationsEnabled() },
            forceUpdate = { update.get().onServerForced() },
            cache = orderCacheStore(context, tokens),
            mask = mask,
            // 相对时间（今天 / 昨天）按服务端 Date 校准（BR-TEXT-11，APP-08 共性第 34 条）；禁用期计时仍用本机时钟
            displayClock = client::serverNow,
            // 订单页恢复记录（R1-03、R2-02）：个人数据存 noBackupFilesDir 下的原子文件，系统保存状态只存随机引用；两个清理器直接删除
            restoreStore = orderRestoreStore(context, tokens),
            // F-X-17 / F-X-19 补充二（FU-AND-0717）：归属、分区与会话对账只用一份身份快照
            identity = client::sessionIdentity,
        ).also { orders ->
            orders.connectSession()
            // 撤回同意、基本模式、切换环境：同步清空各显示位置的待跟单卡（APP-08 共性第 38、43 条）
            orders.connectCleanup(privacy, cleaners)
        }

    /** 推送令牌来源（APP-12 G17）：Release 恒为无令牌；debug / staging 在假服务接管时给测试令牌。 */
    @Provides
    @Singleton
    fun pushTokenSource(
        @ApplicationContext context: Context,
    ): PushTokenSource = variantPushTokenSource(context)

    /**
     * 「我的」、设置、隐私中心、关于、封禁说明、申诉、推送上报与统一客服出口（APP-12）：读写经 ApiClient（带页面归属、身份与 expectedAccount）；邀请码、客服链接、
     * 诊断信息复制经剪贴板唯一写入口（APP-07）；10405 进已有强更分流；本机偏好 couli_me_prefs（撤回清理名单）；改昵称、通知设置的待定 operation 只在 debug /
     * staging 由进程内假服务实现（G01、G02），release 为空。各处「联系客服」改接统一客服出口。
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // 各能力的来源逐项注入
    fun appMe(
        @ApplicationContext context: Context,
        client: ApiClient,
        tokens: SessionTokens,
        routing: AppRouting,
        privacy: AppPrivacy,
        update: AppUpdate,
        config: AppConfig,
        login: AppLogin,
        clipboard: AppClipboard,
        mask: AmountMaskStore,
        permissions: AndroidPermissionRequester,
        device: DeviceRegistry,
        images: AppProductImages,
        trade: AppTrade,
        wallet: AppWallet,
        orders: AppOrders,
        push: PushTokenSource,
    ): AppMe =
        AppMe(
            client = client,
            tokens = tokens,
            routing = routing,
            privacy = privacy,
            store = config.store,
            login = { login },
            updateCheck = { update.routerUpdate },
            serverForced = { update.onServerForced() },
            writer = { text -> clipboard.writer.write(text) },
            local = meLocalStore(context),
            mask = mask,
            system = AndroidMeSystem(context, permissions, device, images),
            pushSource = push,
            nickname = variantNicknameBackend(context),
            notify = variantNotifyBackend(context),
            tipsReset = { trade.tips.invalidate() },
        ).also { me ->
            me.connect { listener -> update.onRouterUpdate(listener) }
            // 各处「联系客服」改接统一客服出口（brief §2.9）
            login.customerService = me.cs::open
            trade.customerService = me.cs::open
            wallet.customerService = me.cs::open
            orders.customerService = me.cs::open
            // 注销与换号（APP-13a）：共享状态交给 BindPhone 与「我的」；二次验证共用钱包的发码截止时间与 token 缓存
            me.account =
                AppAccount(
                    client = client,
                    tokens = tokens,
                    routing = routing,
                    me = me,
                    wallet = wallet,
                    login = login,
                    stepUpPhone = variantStepUpPhone(context),
                    updateCheck = { update.routerUpdate },
                    serverForced = { update.onServerForced() },
                ).also { it.sync() }
            meInstance = me
        }

    /**
     * 首页 SDUI（APP-11）：控制器按身份建立与作废、三级兜底（LKG noBackupFilesDir/couli_home_lkg，待清理标记在目录外）、公告关闭记录与弹窗计数（couli_home_prefs）、
     * 待定 operation 的本机假服务（debug / staging，环境允许 MOCK_DATA 才接管；Release 为 null）、sdui_card_error 进 Tracker 本地队列、
     * 弹窗的阻挡项（首启隐私弹窗、强更、可选更新提示、剪贴板提示条、半屏 / 对话框）、读屏状态与置灰开关；诊断信息「首页版本」改取它（G31）。
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // 各能力的来源逐项注入
    fun appHome(
        @ApplicationContext context: Context,
        client: ApiClient,
        tokens: SessionTokens,
        routing: AppRouting,
        privacy: AppPrivacy,
        config: AppConfig,
        tracker: Provider<TrackerQueue>,
        update: AppUpdate,
        images: AppProductImages,
        trade: AppTrade,
        clipboard: AppClipboard,
        me: AppMe,
        permissions: AndroidPermissionRequester,
    ): AppHome =
        AppHome(
            client = client,
            tokens = tokens,
            routing = routing,
            privacy = privacy,
            port = variantHomePort(context),
            // 会员作用域按持久化登录世代再分区（F-AND-25，FU-AND-0714 R1-07）
            lkg = homeLkgStore(context, tokens),
            prefs = homePrefsStore(context),
            images = images,
            envKey = { variantEnvironmentKey(context) },
            configData = { config.store.snapshots.value.data },
            track = { event -> tracker.get().track(event) },
            serverForced = { update.onServerForced() },
            cardTexts = SearchDraftTexts,
            purchaseStatus = trade::platformStatus,
            appVersion = BuildConfig.VERSION_NAME.substringBefore('-'),
        ).also { home ->
            home.connect()
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                config.store.snapshots.collect { home.onConfigChanged() }
            }
            // 弹窗的阻挡项（G14 ④）：任一来源变化都通知首页与预览实例复核（共性第 21 条）
            home.addBlocker(BLOCKER_PRIVACY, { privacy.promptShowing }, privacy.state)
            home.addBlocker(BLOCKER_FORCE_UPDATE, { update.ui.value.status == UpdateStatus.BELOW_MIN }, update.ui)
            home.addBlocker(BLOCKER_UPDATE_PROMPT, { update.ui.value.state.showPrompt }, update.ui)
            home.addBlocker(BLOCKER_CLIPBOARD, { clipboard.prompt.value != null }, clipboard.prompt)
            home.addBlocker(
                BLOCKER_SHEET,
                {
                    clipboard.result.value != null || me.cs.sheet.value != null || permissions.prompts.current.value != null ||
                        trade.sensitiveOverlay() != null
                },
                merge(clipboard.result, me.cs.sheet, permissions.prompts.current),
            )
            connectScreenReader(context, home)
            me.homeVersion = { home.currentVersion() }
            homeInstance = home
        }

    /**
     * Agent 页（APP-10）：控制器按身份建立与作废（会话、隐私、环境、AI 同意世代、页面实例）；待定 operation 的本机假服务（debug / staging，假服务开关在 + 环境允许
     * MOCK_DATA 才给端口；Release 恒为 null → 「未开放」、不发请求）；购买复用 AppTrade 的 OpenPurchaseController（场景 agent）；金额隐藏与钱包、订单页共用同一个
     * AmountMaskStore；route_entry_blocked 进 Tracker 本地队列；10405 进已有强更分流；「粘贴查返利」经剪贴板唯一读取入口；同意弹窗的阻挡层与首页同一套来源。
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // 各能力的来源逐项注入
    fun appAgent(
        @ApplicationContext context: Context,
        client: ApiClient,
        tokens: SessionTokens,
        routing: AppRouting,
        privacy: AppPrivacy,
        config: AppConfig,
        tracker: Provider<TrackerQueue>,
        update: AppUpdate,
        trade: AppTrade,
        clipboard: AppClipboard,
        mask: AmountMaskStore,
        me: AppMe,
        home: AppHome,
        search: AppSearch,
        orders: AppOrders,
    ): AppAgent {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var built: AppAgent? = null
        val agentPort =
            com.couli.core.agent.ApiAgentContractPort(
                client = client,
                main = { runnable -> main.post(runnable) },
                // 应用级准入（身份变更在途、AI 同意失效、配置关闭），在网络线程现判
                allowed = { built?.requestsAllowed() == true },
            )
        return AppAgent(
            client = client,
            tokens = tokens,
            routing = routing,
            privacy = privacy,
            // APP-10b：Debug / Release 同一个真实端口（经 ApiClient 管线；假服务在传输入口换入）
            port = { agentPort },
            envKey = { variantEnvironmentKey(context) },
            configData = { config.store.snapshots.value.data },
            mask = mask.hidden,
            track = { event -> tracker.get().track(event) },
            serverForced = { update.onServerForced() },
            trade = trade,
            paste = clipboard::pasteForAgent,
            consentRecorded = { accepted -> variantAgentConsentRecorded(context, accepted) },
        ).also { agent ->
            agent.connect()
            agent.customerService = { me.cs.open(AppAgent.AGENT_ROUTE) }
            // 同意弹窗的阻挡层（共性第 5 条）：与首页弹窗同一套来源（隐私弹窗、强更、更新提示、半屏 / 对话框）
            agent.connectBlockers({ home.blockers().isNotEmpty() }, home.blockerChanges)
            connectAgentScreenReader(context, agent)
            // 情境入口（G27）：商品详情、订单详情的【问问 AI】
            search.agent = agent
            orders.agent = agent
            // APP-10b G01：退出登录、撤回隐私同意的确认入口经应用级协调器先停流并尽力 cancel
            me.beginAgentIdentityChange = agent::beginIdentityChange
            me.endAgentIdentityChange = agent::endIdentityChange
            // 配置变化（agent.enabled / agent.entry.visible）立即同步（鸿蒙 R1-07）
            agent.connectConfig(config.store.snapshots)
            built = agent
            agentInstance = agent
        }
    }

    /** 金额隐藏开关（G24：本机偏好，订单与钱包共用同一份；存储名 couli_amount_mask 与键不变）。 */
    @Provides
    @Singleton
    fun amountMaskStore(
        @ApplicationContext context: Context,
    ): AmountMaskStore = AmountMaskStore(context)

    /**
     * 钱包、提现、提现记录、收款账号、实名、劳务协议（APP-09）：读请求经 ApiClient（带页面归属与会话世代）；提交、确认、放弃经未决键管线
     * （SecurePendingKeyBackend）；交易流水号复制经剪贴板唯一写入口（APP-07）；10405 强制进入强更；断网缓存在 noBackupFilesDir 下按账号隔离（撤回、
     * 切换环境、登出、换账号时删除）；实名与劳务协议的待定接口只在 debug / staging 由进程内假服务实现（G01、G02），release 为空（页面置灰）；
     * route_params_dropped 进 Tracker 本地队列。
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // 网络、配置、路由、会话、剪贴板、埋点与强更逐项注入
    fun appWallet(
        @ApplicationContext context: Context,
        client: ApiClient,
        config: AppConfig,
        routing: AppRouting,
        tokens: SessionTokens,
        clipboard: AppClipboard,
        mask: AmountMaskStore,
        tracker: Provider<TrackerQueue>,
        update: Provider<AppUpdate>,
        login: Provider<AppLogin>,
    ): AppWallet =
        AppWallet(
            api = ApiClientWalletApi(client),
            store = config.store,
            routing = routing,
            sessionEpoch = { tokens.loginEpoch },
            account = { client.activeUserId() },
            writer = { text -> clipboard.writer.write(text) },
            forceUpdate = { update.get().onServerForced() },
            cache = walletCacheStore(context, tokens),
            mask = mask,
            realname = variantRealNameBackend(context),
            labor = variantLaborBackend(context),
            stepUpPhone = variantStepUpPhone(context),
            track = { event -> tracker.get().track(event) },
            onOverlay = clipboard::onSensitiveOverlay,
            // 实名查询的 getMe 与登录校验、「我的」、桥 auth.getUser 共用一份 Me 读取截止时间（APP-12 Android R3-01）
            meRead = { login.get().meReadGate },
            // F-AND-37（FU-AND-0717）：钱包二次验证 token 按会话作用域隔离（取不到按 full）
            sessionScope = { client.activeSessionScope() ?: com.couli.core.wallet.stepup.StepUpEnv.SCOPE_FULL },
            // F-X-17 / F-X-19 补充二（FU-AND-0717）
            identity = client::sessionIdentity,
            // 末轮窄修 R3-02：令牌存储带持久化登录世代——分区读不出时金额草稿延后读写，不按账号回退
            partitionTracked = true,
        ).also { wallet -> wallet.connectSession() }

    /**
     * 购买、外跳与授权（APP-06b）：open 经 ApiClient（请求带页面归属）；百川是不可用实现（SDK 未引入、AppKey 占位，TODO(N-28)）；
     * web_code 授权在回跳 scheme 登记前不可执行（release），debug / staging 用本机假授权；link_jump 进 Tracker 本地队列。
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // 购买接线的各项来源逐项注入
    fun appTrade(
        @ApplicationContext context: Context,
        client: ApiClient,
        config: AppConfig,
        routing: AppRouting,
        tracker: Provider<TrackerQueue>,
        privacy: Provider<AppPrivacy>,
        clipboard: AppClipboard,
    ): AppTrade =
        AppTrade(
            api = ApiClientPageApi(client),
            store = config.store,
            routing = routing,
            tracker = tracker,
            baichuan = UnavailableBaichuan(),
            webCode = variantWebCodeAuthorizer(context),
            // copy_tpwd 经剪贴板唯一写入口（写入前记 hash，APP-07）
            ports = variantJumpPorts(context, AndroidJumpPorts(context, writer = clipboard.writer)),
            browserNoticeMillis = variantBrowserNoticeMillis(),
            // 基本模式下点购买：弹补同意（与 Router 的补同意同一个弹窗，A-R1-04）
            consentPrompt = { privacy.get().requestFullFunction() },
        ).also { trade ->
            // AuthSheet 出现时同步作废剪贴板在途的 parse 与提示条（APP-07，A-R1-04）
            trade.onSensitiveOverlay = clipboard::onSensitiveOverlay
            // 剪贴板多卡弹层的商品卡按同一平台购买状态映射（APP-07 共性第 32 条）；AppTrade 依赖 AppClipboard，所以在这里接
            clipboard.connectPurchaseStatus(trade, config.store)
        }

    /** 商品图加载（APP-06a 共性第 22 条）：经 App 的 HTTP 客户端（debug 带本机假服务）取 https 图片。 */
    @Provides
    @Singleton
    fun productImages(
        @ApplicationContext context: Context,
    ): AppProductImages =
        AppProductImages(variantHttpClient(context), source = variantImageSource(context)).also { productImagesInstance = it }

    /** 活着的 BridgeHost（APP-04）：撤回同意、切换环境时作废在途桥调用。 */
    @Provides
    @Singleton
    fun bridgeHostRegistry(): BridgeHostRegistry = BridgeHostRegistry()

    /** 两类 WebView 容器与 JSBridge 的接线（APP-04）。 */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // 各能力的来源逐项注入
    fun appWeb(
        @ApplicationContext context: Context,
        config: AppConfig,
        client: Provider<ApiClient>,
        routing: AppRouting,
        privacy: AppPrivacy,
        update: AppUpdate,
        tracker: Provider<TrackerQueue>,
        permissions: AndroidPermissionRequester,
        bridges: BridgeHostRegistry,
        tokens: SessionTokens,
        inFlight: InFlightCalls,
        login: Provider<AppLogin>,
        clipboard: Provider<AppClipboard>,
    ): AppWeb = AppWeb(context, config, client, routing, privacy, update, tracker, permissions, bridges, tokens, inFlight, login, clipboard)

    /**
     * 剪贴板识别（APP-07；BR-ID-16、BR-INV-04）：系统剪贴板经 [AndroidSystemClipboard]；parse 经 ApiClient（请求带提示条 / 发起页的归属）；
     * 隐私结论、会话与环境变化时作废提示条与在途 parse；浮层来源（AuthSheet、auth_jump、第三方登录进行中）经 Provider 现取。
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // 各状态源逐项注入
    fun appClipboard(
        @ApplicationContext context: Context,
        client: ApiClient,
        routing: AppRouting,
        privacy: AppPrivacy,
        config: AppConfig,
        tokens: SessionTokens,
        cleaners: EnvironmentSwitchCleanerRegistry,
        trade: Provider<AppTrade>,
        login: Provider<AppLogin>,
        update: Provider<AppUpdate>,
    ): AppClipboard =
        AppClipboard(
            system = AndroidSystemClipboard(context),
            api = ApiClientPageApi(client),
            routing = routing,
            privacy = { privacy.conclusion },
            promptShowing = { privacy.promptShowing },
            configData = { config.store.snapshots.value.data },
            prefs = ClipboardPreferences(context),
            sessionEpoch = { tokens.loginEpoch },
        ).also { board ->
            // 前台轮次只由进程级生命周期决定（FU-AND-0724，R12）：ON_START / ON_STOP 在观察者回调里同步转给协调器
            board.bindProcessLifecycle(ProcessLifecycleOwner.get().lifecycle)
            board.overlay = {
                // AppLogin 构造时（init 里发布会话状态）会同步触发剪贴板的会话回调：那时还不能取 AppLogin（Dagger 判循环依赖、已登录冷启动即崩），
                // 也不可能有第三方登录在进行（APP-08 联调发现的既有问题）
                trade.get().sensitiveOverlay() ?: ClipboardSensitive.OVERLAY_OAUTH.takeIf { loginBuilt && login.get().oauth.busy }
            }
            // parse 单条 / 多条结果里的 10405（外层成功）：强制进入强更界面（APP-07 共性第 20、21 条，不经按缓存最低版本比较的闸门）
            board.onForceUpdate = { update.get().onServerForced() }
            // 隐私结论（撤回、基本模式）、登录 / 退出 / 切换账号、切换环境：作废提示条与在途 parse
            board.connect(privacy, routing, cleaners)
            // 规则表变化（features.flags 的电商平台开关）：作废候选已不被允许的提示条（FU-CLIP §2.1 第 5 条）
            board.connectRules(config.store)
        }

    /**
     * 包内隐私政策版本（整数，BR-ID-12）。TODO(N-16): 法务定稿的包内协议副本与版本号；接入 /v1/config.legal 后
     * 按「实际展示的版本」记录（rule-gaps：§2.1 API 只有包内版本）。
     */
    private const val BUNDLED_PRIVACY_POLICY_VERSION = "1"

    /** 已构造的 AppMe（网络层的会话通知、10006 落点、清理入口在它建好之前不取它，避免 Dagger 循环依赖）。 */
    @Volatile
    private var meInstance: AppMe? = null

    /** 已构造的 AppAgent（会话通知、撤回与环境切换的清理在它建好之前不取它）。 */
    @Volatile
    private var agentInstance: AppAgent? = null

    /** 已构造的 AppHome（环境切换的清理与结束通知在它建好之前不取它）。 */
    @Volatile
    private var homeInstance: AppHome? = null

    /** 已构造的商品图加载器（环境切换时清内存缓存；它建好之前不取它，F-X-02 R1-05）。 */
    @Volatile
    private var productImagesInstance: AppProductImages? = null

    private const val BLOCKER_PRIVACY = "privacy_dialog"
    private const val BLOCKER_FORCE_UPDATE = "force_update"
    private const val BLOCKER_UPDATE_PROMPT = "update_prompt"
    private const val BLOCKER_CLIPBOARD = "clipboard_prompt"
    private const val BLOCKER_SHEET = "sheet_open"

    /** AppRouting 的导航票据（网络层每个调用入口取一次，APP-12 G09）。 */
    @Volatile
    private var navTickets: NavTickets? = null

    /** AppLogin 已构造完成（剪贴板浮层来源在此之前不取它，见 appClipboard）。 */
    @Volatile
    private var loginBuilt = false

    private const val SESSION_TOKENS_CLEANER = "session_tokens"
    private const val DEVICE_REGISTRY_CLEANER = "device_registry"
    private const val TRACKER_QUEUE_CLEANER = "tracker_queue"
    private const val LKG_CONFIG_FILE = "couli_config/lkg.json"

    /** 埋点的 session_id：本进程一次（03 §4.7 公共字段）。 */
    private val PROCESS_SESSION_ID: String = UUID.randomUUID().toString()
    private const val IN_FLIGHT_CALLS_CLEANER = "in_flight_calls"
    private const val BRIDGE_CALLS_CLEANER = "bridge_calls"
    private const val CLIPBOARD_HASHES_CLEANER = "clipboard_hashes"
    private const val FAKE_ACCOUNT_CLEANER = "fake_account"
}

/**
 * 埋点队列文件（filesDir，「清除缓存」不清）；写 null 同时删 .tmp（AtomicTextFile）。队列与两个清理入口共用同一个实例（读写互斥）。
 */
internal fun trackerQueueFile(context: Context): AtomicTextFile {
    val path = File(context.filesDir, TrackerQueueFile.PATH)
    return synchronized(TrackerQueueFile) { TrackerQueueFile.instances.getOrPut(path.absolutePath) { AtomicTextFile(path) } }
}

private object TrackerQueueFile {
    const val PATH = "couli_tracker/queue.json"

    /** 按绝对路径各一份（生产只有一个 filesDir；单测每个用例的 filesDir 不同）。 */
    val instances = mutableMapOf<String, AtomicTextFile>()
}

/** 「我的」的设备级本机偏好（APP-12）：进程内一份（网络层、隐私中心、撤回清理共用）。 */
internal fun meLocalStore(context: Context): MeLocalStore =
    synchronized(MeLocalStores) { MeLocalStores.instance ?: MeLocalStore(context).also { MeLocalStores.instance = it } }

private object MeLocalStores {
    var instance: MeLocalStore? = null
}

/**
 * 令牌存储里的持久化登录世代（F-AND-25 / F-AND-29）：订单缓存、钱包摘要缓存、订单页恢复记录按「账号 + 它」绑定归属。令牌存储没实现
 * [PersistedLoginEpochs] 时按未知（不读缓存、不恢复）。
 */
internal fun persistedLoginEpochOf(tokens: SessionTokens): () -> String? = { (tokens as? PersistedLoginEpochs)?.persistedLoginEpoch() }

/**
 * 订单缓存（APP-08，G10）：noBackupFilesDir 下按账号分目录；订单页与两个清理入口共用同一个实例（同步、互斥）。订单页的取实例处给 [tokens]：
 * 缓存按「账号 + 持久化登录世代」分区（F-AND-25；实例先由清理入口建出来的，这时接上）；清理入口与单测不给（清理不看分区）。
 */
internal fun orderCacheStore(
    context: Context,
    tokens: SessionTokens? = null,
): OrderStaleCache.Store {
    val root = AppOrders.cacheRoot(context)
    return synchronized(OrderCacheStores) {
        OrderCacheStores.instances.getOrPut(root.absolutePath) { OrderStaleCache.Store(root, orderCachePendingFlag(context)) }
    }.also { store -> if (tokens != null) store.partitionByLoginEpoch() }
}

/**
 * 订单缓存的「待清理」状态（R3-02）：缓存目录之外的 SharedPreferences，commit 确认写入；启动、读写缓存前由 Store 核验。
 */
@SuppressLint("ApplySharedPref", "UseKtx") // 必须 commit 并拿到返回值确认写入（KTX 的 edit(commit = true) 丢掉返回值）
internal fun orderCachePendingFlag(context: Context): OrderStaleCache.PendingClearFlag {
    val prefs = context.applicationContext.getSharedPreferences(CouliPreferenceNamespaces.ORDER_CACHE_PENDING, Context.MODE_PRIVATE)
    return object : OrderStaleCache.PendingClearFlag {
        override fun isSet(): Boolean = prefs.getBoolean(ORDER_CACHE_PENDING_KEY, false)

        override fun set(): Boolean = prefs.edit().putBoolean(ORDER_CACHE_PENDING_KEY, true).commit()

        override fun clear(): Boolean = prefs.edit().remove(ORDER_CACHE_PENDING_KEY).commit()
    }
}

private const val ORDER_CACHE_PENDING_KEY = "pending_clear"

/**
 * 订单页恢复记录（FU-AND-0710 R1-03、R2-02）：noBackupFilesDir 下每份记录一个原子文件；订单页与两个清理入口共用同一个实例（同步、互斥）。
 */
internal fun orderRestoreStore(
    context: Context,
    tokens: SessionTokens? = null,
): OrderRestoreStore {
    val root = AppOrders.restoreRoot(context)
    return synchronized(OrderRestoreStores) {
        OrderRestoreStores.instances.getOrPut(root.absolutePath) { OrderRestoreStore(root, orderRestorePendingFlag(context)) }
    }.also { store -> tokens?.let { store.bindLoginEpoch(persistedLoginEpochOf(it)) } }
}

/**
 * 订单页恢复记录的「待清理」状态：与订单缓存的待清理同一个 SharedPreferences（不以 couli. 开头，切换环境的命名空间清理不会抹掉它），commit 确认。
 */
@SuppressLint("ApplySharedPref", "UseKtx") // 必须 commit 并拿到返回值确认写入
internal fun orderRestorePendingFlag(context: Context): OrderStaleCache.PendingClearFlag {
    val prefs = context.applicationContext.getSharedPreferences(CouliPreferenceNamespaces.ORDER_CACHE_PENDING, Context.MODE_PRIVATE)
    return object : OrderStaleCache.PendingClearFlag {
        override fun isSet(): Boolean = prefs.getBoolean(ORDER_RESTORE_PENDING_KEY, false)

        override fun set(): Boolean = prefs.edit().putBoolean(ORDER_RESTORE_PENDING_KEY, true).commit()

        override fun clear(): Boolean = prefs.edit().remove(ORDER_RESTORE_PENDING_KEY).commit()
    }
}

private const val ORDER_RESTORE_PENDING_KEY = "restore_pending_clear"

private object OrderRestoreStores {
    /** 按绝对路径各一份（生产只有一个；单测每个用例的目录不同）。 */
    val instances = mutableMapOf<String, OrderRestoreStore>()
}

/** 钱包摘要缓存（APP-09，G22）：noBackupFilesDir 下按账号分目录；钱包页与两个清理入口共用同一个实例。 */
internal fun walletCacheStore(
    context: Context,
    tokens: SessionTokens? = null,
): WalletStaleCache.Store {
    val root = AppWallet.cacheRoot(context)
    return synchronized(WalletCacheStores) {
        WalletCacheStores.instances.getOrPut(root.absolutePath) { WalletStaleCache.Store(root, walletCachePendingFlag(context)) }
    }.also { store -> if (tokens != null) store.partitionByLoginEpoch() }
}

/** 钱包缓存的「待清理」状态：缓存目录之外的 SharedPreferences，commit 确认写入（APP-08 共性第 28 条）。 */
@SuppressLint("ApplySharedPref", "UseKtx") // 必须 commit 并拿到返回值确认写入
internal fun walletCachePendingFlag(context: Context): WalletStaleCache.PendingClearFlag {
    val prefs = context.applicationContext.getSharedPreferences(CouliPreferenceNamespaces.WALLET_CACHE_PENDING, Context.MODE_PRIVATE)
    return object : WalletStaleCache.PendingClearFlag {
        override fun isSet(): Boolean = prefs.getBoolean(ORDER_CACHE_PENDING_KEY, false)

        override fun set(): Boolean = prefs.edit().putBoolean(ORDER_CACHE_PENDING_KEY, true).commit()

        override fun clear(): Boolean = prefs.edit().remove(ORDER_CACHE_PENDING_KEY).commit()
    }
}

private object WalletCacheStores {
    val instances = mutableMapOf<String, WalletStaleCache.Store>()
}

private object OrderCacheStores {
    /** 按绝对路径各一份（生产只有一个；单测每个用例的目录不同）。 */
    val instances = mutableMapOf<String, OrderStaleCache.Store>()
}

/** Router 的强更进度 → 网络层的强更状态（检查中按未知）。 */
private fun UpdateCheck.toNetworkUpdate(): String =
    when (this) {
        UpdateCheck.BELOW_MIN -> UpdateRaw.BELOW
        UpdateCheck.OK, UpdateCheck.BELOW_RECOMMENDED -> UpdateRaw.SUPPORTED
        UpdateCheck.CHECKING, UpdateCheck.UNKNOWN -> UpdateRaw.UNKNOWN
    }
