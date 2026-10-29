package com.couli.app.wallet

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.wallet.WalletTags
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-0728 U4（F-AND-70 ②）：W92 错误条语气波及的三处无截图调用方——结构断言（ERROR 语气：assertive、红字 500 / 14、红色浅底、【重试】可点）。
 * 不改产品；底版即应全绿（W92 已合并），用来把这三处的外观钉住，截图入口见 [Fu0728ErrorBarsWalletVisualSnapshotTest]。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Fu0728ErrorBarStructureTest {
    @get:Rule val rule = createComposeRule()
    private val scenes by lazy { Fu0728ErrorBarScenes(rule) }

    @After fun close() = scenes.close()

    @Test fun walletRefreshErrorUsesTheErrorTone() {
        scenes.walletRefreshError()
        scenes.assertErrorTone(WalletTags.WALLET_REFRESH_ERROR, Fu0728ErrorBarScenes.WALLET_RETRY)
    }

    @Test
    @Config(qualifiers = "w390dp-h1000dp-xhdpi")
    fun recordsRefreshErrorUsesTheErrorTone() {
        scenes.recordsRefreshError()
        scenes.assertErrorTone(WalletTags.RECORDS_REFRESH_ERROR, Fu0728ErrorBarScenes.RECORDS_RETRY)
    }

    @Test fun openErrorUsesTheErrorTone() {
        scenes.openError()
        scenes.assertErrorTone(WalletTags.OPEN_ERROR_TEXT, WalletTags.OPEN_ERROR_RETRY)
    }
}
