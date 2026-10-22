package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.wallet.WalletDraftTexts
import com.couli.core.wallet.WalletTexts
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawRecordsVisualDictionaryTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawRecordsVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w21AndW32StateAndProgressReadInjectedDictionaryRatherThanLiteralChinese() {
        val keys = mutableSetOf<String>()
        host.texts =
            object : WalletTexts by WalletDraftTexts {
                override fun text(key: String): String {
                    keys += key
                    return if (key.startsWith("withdrawal_status.")) "字典-$key" else WalletDraftTexts.text(key)
                }

                override fun format(
                    key: String,
                    vars: Map<String, String>,
                ): String {
                    keys += key
                    return if (key == "withdrawal.draft.processing") "字典进度" else WalletDraftTexts.format(key, vars)
                }
            }
        host.launch("paying")
        rule.recordText("字典-withdrawal_status.PAYING.title", "PAYING").assertIsDisplayed()
        rule.recordText("字典进度", "PAYING").assertIsDisplayed()
        assertTrue(keys.containsAll(listOf("withdrawal_status.PAYING.title", "withdrawal.draft.processing")))
    }
}
