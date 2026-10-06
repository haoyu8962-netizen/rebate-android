package com.couli.app

import android.content.Context
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
import com.couli.core.privacy.CredentialCleanerRegistry
import com.couli.core.privacy.MainThreadRetryScheduler
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.SharedPreferencesPrivacyStore
import com.couli.core.privacy.registerPlaceholderCredentialCleaners
import com.couli.core.privacy.registerProductionInitSteps
import com.couli.core.router.PrivacyState
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
object AppModule {
    /**
     * 切换环境时的清理入口注册表（03 §3.6）。先登记本 App 的 SharedPreferences 命名空间；
     * 会话、LKG、Keystore 等模块实现后各自注册。
     */
    @Provides
    @Singleton
    fun environmentSwitchCleaners(
        @ApplicationContext context: Context,
    ): EnvironmentSwitchCleanerRegistry =
        EnvironmentSwitchCleanerRegistry().apply {
            register(SharedPreferencesNamespaceCleaner(context))
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
    ): CredentialCleanerRegistry =
        CredentialCleanerRegistry().apply {
            registerPlaceholderCredentialCleaners(context)
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
}
