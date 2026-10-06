package com.couli.app

import android.content.Context
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentSwitchCleanerRegistry
import com.couli.core.config.SharedPreferencesNamespaceCleaner
import com.couli.core.privacy.CredentialCleanerRegistry
import com.couli.core.privacy.MainThreadRetryScheduler
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.SharedPreferencesPrivacyStore
import com.couli.core.privacy.registerPlaceholderCredentialCleaners
import com.couli.core.privacy.registerProductionInitSteps
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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

    /** 路由接线（APP-02a）：启动意图排队、统一入口守卫、pending_action；隐私结论来自 [AppPrivacy]（APP-02b）。 */
    @Provides
    @Singleton
    fun appRouting(
        @ApplicationContext context: Context,
        environmentGate: EnvironmentGate,
        cleaners: EnvironmentSwitchCleanerRegistry,
        privacy: AppPrivacy,
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
        ).also { routing ->
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
