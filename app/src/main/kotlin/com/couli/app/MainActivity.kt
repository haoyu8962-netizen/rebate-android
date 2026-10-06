package com.couli.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.app.routing.AppNavHost
import com.couli.app.routing.AppRouting
import com.couli.app.routing.LaunchIntentSanitizer
import com.couli.core.config.EnvironmentGate
import com.couli.core.designsystem.CouliTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 单 Activity。启动 Intent 与运行中收到的深链都先交给 [AppRouting]（排队 → 统一入口守卫 → Router），
 * 不直接按 Intent 里的路由名打开任何页面（03 §4.4）。TODO(APP-02b): 隐私闸门、Tab；TODO(APP-02c): 强更。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var environmentGate: EnvironmentGate

    @Inject
    lateinit var appRouting: AppRouting

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 重建（旋转、进程恢复）时不重放启动意图：它是一次性的。
        if (savedInstanceState == null) appRouting.onLaunchIntent(intent?.dataString, coldStart = true)
        // 原始 data 已交给 Router；Navigation 只能看到清洗后的 Intent（不带 data 与框架导航 extras，R1-01）
        intent = LaunchIntentSanitizer.sanitize(intent, variantLaunchExtraAllowlist())
        setContent {
            val gateState by environmentGate.state.collectAsStateWithLifecycle()
            val trace by appRouting.lastTrace.collectAsStateWithLifecycle()
            LaunchedEffect(gateState) { appRouting.drain() }
            CouliTheme {
                RootScreen(
                    gateState = gateState,
                    onRetry = { environmentGate.retry() },
                    settledContent = {
                        AppNavHost(routing = appRouting, home = { VariantRootContent(trace = trace) })
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        appRouting.onLaunchIntent(intent.dataString, coldStart = false)
        setIntent(LaunchIntentSanitizer.sanitize(intent, variantLaunchExtraAllowlist()))
    }
}
