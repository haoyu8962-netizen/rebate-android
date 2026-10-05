package com.couli.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.core.config.EnvironmentGate
import com.couli.core.designsystem.CouliTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** 单 Activity。TODO(APP-02): AppShell（启动、隐私闸门、Tab、路由、深链、强更）。 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var environmentGate: EnvironmentGate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val gateState by environmentGate.state.collectAsStateWithLifecycle()
            CouliTheme {
                RootScreen(
                    gateState = gateState,
                    onRetry = { environmentGate.retry() },
                )
            }
        }
    }
}
