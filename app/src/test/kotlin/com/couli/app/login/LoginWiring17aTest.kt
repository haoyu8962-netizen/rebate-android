package com.couli.app.login

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.AppModule
import com.couli.app.clipboard.ClipboardReviewCipher0724
import com.couli.app.clipboard.ClipboardReviewLogin0724Graph
import com.couli.app.clipboard.ClipboardReviewLogin0724Wire
import com.couli.core.config.AppEnvironment
import com.couli.core.privacy.CredentialCleanerRegistry
import com.couli.feature.login.LoginStep
import com.couli.feature.login.LoginViewModel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** C05/C07/C09：真实 AppModule 工厂、ConfigStore、Router、清理注册表；不在测试里手接帮助/清理回调。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], shadows = [ClipboardReviewLogin0724Wire::class, ClipboardReviewCipher0724::class])
class LoginWiring17aTest {
    @Test
    fun codeHelpReadsCurrentLoginHelpFromConfigAndFallsBackForEveryInvalidShape() {
        val valid = """{"route":"Help","params":{"article_id":"sms-help-17a"}}"""
        ClipboardReviewLogin0724Graph(config(valid)).use { graph ->
            val model = LoginViewModel(graph.login.pageConfig(false))
            try {
                model.onPhoneChange("13800138000")
                model.onGetCode()
                model.onConfirmAgree()
                graph.until("发码结算") { !model.state.value.sending && model.state.value.step == LoginStep.CODE }
                assertTrue(model.state.value.resendSec > 0)
                model.help()
                graph.settle()
                assertHelp(graph, "sms-help-17a")
                val cases =
                    listOf(
                        """{"route":"Help","params":{"article_id":"updated-help"}}""" to "updated-help",
                        "null" to null,
                        """{"route":"Settings","params":{"article_id":"bad"}}""" to null,
                        """{"route":"Help","params":{"article_id":""}}""" to null,
                        """{"route":"Help","params":{"article_id":42}}""" to null,
                        """{"route":"Help","params":{"article_id":"   "}}""" to null,
                    )
                listOf("{}", """{"help_links":{}}""", """{"help_links":[]}""").forEach { json ->
                    graph.configJson = json
                    graph.config.store.onForeground()
                    model.help()
                    graph.settle()
                    assertHelp(graph, null)
                }
                cases.forEach { (entry, expected) ->
                    graph.configJson = config(entry)
                    graph.config.store.onForeground()
                    model.help()
                    graph.settle()
                    assertHelp(graph, expected)
                }
            } finally {
                model.viewModelScope.cancel()
            }
        }
    }

    @Test
    fun customerServiceDefaultStillOpensHelpAfterLoginEntryCleanup() {
        ClipboardReviewLogin0724Graph().use { graph ->
            graph.login.customerService("Login")
            graph.settle()
            assertHelp(graph, null)
        }
    }

    @Test
    fun productionStoreIsSharedSavedWithOneKeySurvivesLogoutAndEnvironmentButNotWithdrawal() {
        ClipboardReviewLogin0724Graph().use { graph ->
            val methods = graph.lastLoginMethod
            assertSame(methods, graph.login.service.lastLoginMethod)
            graph.loginAs("13800138000")
            assertEquals("sms", methods.read())
            val prefs = graph.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            assertEquals(mapOf("last_method" to "sms"), prefs.all)
            val file = File(graph.context.applicationInfo.dataDir, "shared_prefs/$PREFS.xml")
            assertTrue("确实同步落盘", file.isFile)
            val xml =
                javax.xml.parsers.DocumentBuilderFactory
                    .newInstance()
                    .newDocumentBuilder()
                    .parse(file)
            val item = xml.getElementsByTagName("string")
            assertEquals(1, item.length)
            assertEquals(
                "last_method",
                item
                    .item(0)
                    .attributes
                    .getNamedItem("name")
                    .nodeValue,
            )
            assertEquals("sms", item.item(0).textContent)
            assertFalse(file.readText().contains("13800138000"))
            assertEquals("同步落盘后用新命名空间冷读", "sms", coldRead(graph.context))
            graph.logout()
            assertNull(graph.tokens.accessToken)
            assertEquals("sms", methods.read())
            graph.cleaners.switching(AppEnvironment.TEST, AppEnvironment.STAGING) {
                assertEquals("G24：真实命名空间清理完成、提交新环境前仍保留", "sms", methods.read())
            }
            assertEquals(mapOf("last_method" to "sms"), prefs.all)
            assertEquals("sms", coldRead(graph.context))
            graph.privacy.withdraw()
            graph.settle()
            assertNull(methods.read())
            assertTrue(prefs.all.isEmpty())
            assertNull(coldRead(graph.context))
        }
    }

    /** 复制 XML 到全新名字，不命中 Android 的 SharedPreferences 进程缓存。 */
    private fun coldRead(context: Context): String? {
        val directory = File(context.applicationInfo.dataDir, "shared_prefs")
        val persisted = File(directory, "$PREFS.xml")
        val name = "method-17a-restart-${UUID.randomUUID()}"
        if (persisted.exists()) persisted.copyTo(File(directory, "$name.xml"))
        val freshContext =
            object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this

                override fun getSharedPreferences(
                    key: String,
                    mode: Int,
                ): SharedPreferences = context.getSharedPreferences(if (key == PREFS) name else key, mode)
            }
        return try {
            AppModule.lastLoginMethodStore(freshContext, CredentialCleanerRegistry()).read()
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    private fun assertHelp(
        graph: ClipboardReviewLogin0724Graph,
        article: String?,
    ) {
        val outcome = checkNotNull(graph.routing.router.lastOutcome.value)
        assertEquals("Help", outcome.request.route)
        val expected = if (article == null) JsonObject(emptyMap()) else JsonObject(mapOf("article_id" to JsonPrimitive(article)))
        assertEquals(expected, outcome.request.params)
        assertTrue(outcome.decision.opensPage)
    }

    private fun config(entry: String): String =
        JsonObject(
            mapOf("help_links" to Json.parseToJsonElement("""{"login_help":$entry}""").jsonObject),
        ).toString()

    private companion object {
        const val PREFS = "couli_last_login_method"
    }
}
