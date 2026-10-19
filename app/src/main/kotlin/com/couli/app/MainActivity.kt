package com.couli.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.app.agent.AppAgent
import com.couli.app.clientcore.ActivityPermissionHost
import com.couli.app.clientcore.AppConfig
import com.couli.app.clientcore.PermissionPromptHost
import com.couli.app.clipboard.AppClipboard
import com.couli.app.home.AppHome
import com.couli.app.home.VariantHomeContent
import com.couli.app.login.AppLogin
import com.couli.app.me.AppMe
import com.couli.app.orders.AppOrders
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.LaunchIntentSanitizer
import com.couli.app.search.AppSearch
import com.couli.app.shell.AppShell
import com.couli.app.trade.AppProductImages
import com.couli.app.update.AppUpdate
import com.couli.app.wallet.AppWallet
import com.couli.app.web.AppWeb
import com.couli.core.config.EnvironmentGate
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalProductImageLoader
import com.couli.core.privacy.AndroidPermissionRequester
import com.couli.core.privacy.AndroidPermissionSystem
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 单 Activity。启动 Intent 与运行中收到的深链都先交给 [AppRouting]（排队 → 统一入口守卫 → Router），
 * 不直接按 Intent 里的路由名打开任何页面（03 §4.4）。环境闸门 settled 后进入 [AppShell]（隐私闸门、Tab，APP-02b）。
 * 强更（APP-02c）：回前台（[onStart]，冷启动那次与配置变更重建除外）交给 [AppUpdate] 按间隔复查。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var environmentGate: EnvironmentGate

    @Inject
    lateinit var appRouting: AppRouting

    @Inject
    lateinit var privacy: AppPrivacy

    @Inject
    lateinit var update: AppUpdate

    @Inject
    lateinit var config: AppConfig

    @Inject
    lateinit var startup: AppStartup

    @Inject
    lateinit var permissionSystem: AndroidPermissionSystem

    @Inject
    lateinit var permissions: AndroidPermissionRequester

    @Inject
    lateinit var web: AppWeb

    @Inject
    lateinit var login: AppLogin

    @Inject
    lateinit var search: AppSearch

    @Inject
    lateinit var productImages: AppProductImages

    @Inject
    lateinit var clipboard: AppClipboard

    @Inject
    lateinit var orders: AppOrders

    @Inject
    lateinit var wallet: AppWallet

    @Inject
    lateinit var me: AppMe

    @Inject
    lateinit var home: AppHome

    @Inject
    lateinit var agent: AppAgent

    private val permissionHost = ActivityPermissionHost(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 系统权限申请只经 PermissionRequester（APP-03b，03 §4.9）；这里只提供当前 Activity 的系统授权框
        permissionSystem.host = permissionHost
        // 重建（旋转、进程恢复）时不重放启动意图：它是一次性的。
        if (savedInstanceState == null) appRouting.onLaunchIntent(intent?.dataString, coldStart = true)
        // 原始 data 已交给 Router；Navigation 只能看到清洗后的 Intent（不带 data 与框架导航 extras，R1-01）
        intent = LaunchIntentSanitizer.sanitize(intent, variantLaunchExtraAllowlist())
        setContent {
            val gateState by environmentGate.state.collectAsStateWithLifecycle()
            val trace by appRouting.lastTrace.collectAsStateWithLifecycle()
            LaunchedEffect(gateState) {
                appRouting.drain()
                // 环境闸门【重试】成功后：补隐私初始化、强更检查、配置冷启动、Tracker 定时上报（各自只生效一次，APP-03b R2-04）
                startup.onEnvironmentGateChanged()
            }
            CouliTheme {
                // 商品图（APP-06a 共性第 22 条）：列表卡与详情经它加载契约 image 字段
                CompositionLocalProvider(LocalProductImageLoader provides productImages) {
                    RootScreen(
                        gateState = gateState,
                        onRetry = { environmentGate.retry() },
                        settledContent = {
                            AppShell(
                                routing = appRouting,
                                privacy = privacy,
                                update = update,
                                web = web,
                                login = login,
                                search = search,
                                clipboard = clipboard,
                                orders = orders,
                                wallet = wallet,
                                me = me,
                                home = home,
                                agent = agent,
                                // 首页 SDUI（APP-11）：debug / staging 在列表底部多一个开发入口（调试首屏）
                                homeContent = { VariantHomeContent(home, trace) },
                            )
                        },
                    )
                }
                // 系统权限的用途说明浮层与【去设置】引导（APP-03b）
                PermissionPromptHost(prompts = permissions.prompts)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 首页（APP-11）：App 在前台（弹窗、轮播的可见性）
        home.onForeground(true)
        // Agent 页（APP-10）：App 在前台（可见性：进后台时关连接、停计时）
        agent.onForeground(true)
        // AuthSheet 自动弹出计数：后台停留 ≥ 1800 秒回前台清零（BR-ID-18）
        search.trade?.onAppForeground()
        // 回前台：距上次成功检查超过复查间隔才查（03 §4.1）；冷启动那次由 Application 发起
        if (!skipNextForeground) {
            update.onForeground()
            // 回前台带 If-None-Match 拉配置（APP-03b，03 §4.3）
            config.onForeground()
        }
        skipNextForeground = false
    }

    override fun onDestroy() {
        if (permissionSystem.host === permissionHost) permissionSystem.host = null
        // Agent 页实例真正销毁（配置重建除外，brief §3 Android：配置重建只在内存保留对话）：page_destroyed，清空消息，不发 cancel
        if (!isChangingConfigurations) agent.onPageDestroyed()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // 剪贴板（FU-AND-0724）：前台轮次只由进程级生命周期决定（AppModule 绑定 ProcessLifecycleOwner）；Activity 恢复只通知协调器再试一次
        clipboard.onActivityResumed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        clipboard.onWindowFocus(hasFocus)
    }

    override fun onStop() {
        super.onStop()
        // 首页（APP-11）：进后台（含配置变更重建的短暂停留）时轮播暂停、弹窗等待
        home.onForeground(false)
        agent.onForeground(false)
        if (!isChangingConfigurations) search.trade?.onAppBackground()
        // 导航票据（APP-12 G09）：真正进入后台算一次（回前台后，进后台之前发起的请求得到 10006 不再导航）
        if (!isChangingConfigurations) appRouting.navTickets.onBackground()
        // 旋转等配置变更的重建不算回前台
        skipNextForeground = isChangingConfigurations
    }

    private companion object {
        /** 下一次 onStart 不是回前台：进程里第一次显示（冷启动检查由 Application 发起）或配置变更重建。按进程记。 */
        var skipNextForeground = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        appRouting.onLaunchIntent(intent.dataString, coldStart = false)
        setIntent(LaunchIntentSanitizer.sanitize(intent, variantLaunchExtraAllowlist()))
        // 系统分享（FU-CLIP C6）：接收 Activity 已把候选交给剪贴板模块（这个 Intent 不带任何分享内容）；已在前台时立即处理，否则回前台时处理
        clipboard.onShareDelivered()
    }
}
