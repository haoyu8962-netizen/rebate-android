package com.couli.app.wallet.realname

import com.couli.app.wallet.visual.WithdrawVisualFixtures
import com.couli.feature.wallet.WalletTags
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.shadows.ShadowLog

/** 姓名、身份证号只进 POST /v1/me/realname 请求体（哨兵）；源码不打印、请求不走视图层（源码扫描）。 */
class RealnameFundsPrivacyTest : RealnameFundsHarness() {
    private val sentinels get() = listOf(normalizedName, normalizedId, typedName.trim(), typedId)

    private fun containsSentinel(text: String?): Boolean = sentinels.any { text.orEmpty().contains(it) }

    /** 依次走 30406、断连、成功三次提交。 */
    private fun threeAttempts() {
        submitReply = { index ->
            when (index) {
                0 -> envelope("null", code = 30406)
                1 -> disconnect()
                else -> success()
            }
        }
        launch()
        enterRealNameFromWallet()
        fillVectorForm()
        submitAndAgree(1)
        idleUntil { errorBar() != null }
        resubmit()
        idleUntil { submitEnabled() }
        resubmit()
        idleUntil { realnameVerified() }
    }

    @Test fun sentinelsOnlyInSubmitBody() {
        threeAttempts()
        val calls = synchronized(requests) { requests.toList() }
        assertTrue("实名提交带了哨兵（前提）", calls.filter { it.path == SUBMIT_PATH }.all { containsSentinel(it.body) })
        calls.forEach { call ->
            assertFalse("地址、查询串不含哨兵", containsSentinel(call.path) || containsSentinel(call.query))
            assertFalse("请求头不含哨兵", call.headers.any { (name, value) -> containsSentinel(name) || containsSentinel(value) })
            if (call.path != SUBMIT_PATH) assertFalse("其他请求的请求体不含哨兵", containsSentinel(call.body))
        }
    }

    @Test fun sentinelsNotLoggedPersistedOrTracked() {
        threeAttempts()
        assertFalse("日志不含哨兵", ShadowLog.getLogs().any { containsSentinel(it.msg) || containsSentinel(it.tag) })
        assertFalse("埋点不含哨兵", synchronized(tracked) { tracked.toList() }.any { containsSentinel(it.toString()) })
        val leaks =
            sequenceOf(context.filesDir, context.noBackupFilesDir, context.cacheDir, File(context.applicationInfo.dataDir, "shared_prefs"))
                .filter { it.exists() }
                .flatMap { it.walkTopDown() }
                .filter { it.isFile }
                .filter { file -> containsSentinel(runCatching { file.readText() }.getOrNull()) }
                .toList()
        assertTrue("本机文件不含哨兵（${leaks.size} 个文件命中）", leaks.isEmpty())
    }

    @Test fun formClearedAfterSuccessAndLeaving() {
        threeAttempts()
        assertTrue("成功后已离开实名页", top()?.name != "RealName")
        submitReply = { success() }
        verifiedOnServer = false
        composeRule.runOnUiThread { wallet.forgetRealname() }
        open("RealName")
        idleUntil { has(WalletTags.REALNAME_NAME) }
        assertTrue("再进实名页输入已清空", fieldText(WalletTags.REALNAME_NAME).isEmpty())
        assertTrue("再进实名页身份证号已清空", fieldText(WalletTags.REALNAME_ID).isEmpty())
    }

    @Test fun productionSourcesWiredWithoutLogging() {
        val root = WithdrawVisualFixtures.root
        val remote = File(root, "feature/wallet/src/main/kotlin/com/couli/feature/wallet/RealNameRemote.kt")
        assertTrue("真实后端落在 RealNameRemote.kt（文件名避开 key / api 字样，D10-1026）", remote.isFile)
        val source = remote.readText()
        assertTrue("真实后端接 submitRealname", source.contains("\"submitRealname\""))
        assertTrue("真实后端记同意", source.contains("\"recordConsent\"") && source.contains("\"id_verification\""))
        assertTrue("channel 固定 realname_sheet", source.contains("\"realname_sheet\""))
        val scanned =
            listOf(
                remote,
                File(root, "feature/wallet/src/main/kotlin/com/couli/feature/wallet/IdentityControllers.kt"),
                File(root, "feature/wallet/src/main/kotlin/com/couli/feature/wallet/IdentityScreens.kt"),
            )
        val forbidden = Regex("""\b(Log\.[vdiwe]|println|print\(|Timber|printStackTrace)""")
        scanned.forEach { file -> assertFalse("${file.name} 不打印", forbidden.containsMatchIn(file.readText())) }
        val views = File(root, "feature/wallet/src/main/kotlin/com/couli/feature/wallet/IdentityScreens.kt").readText()
        assertFalse("实名请求不在视图层（G-W24）", views.contains("submitRealname") || views.contains("recordConsent"))
        val joined = source + scanned.joinToString("") { it.readText() }
        assertFalse("源码不把姓名 / 身份证号拼进字符串", joined.contains("\$idNo\"") || joined.contains("\$name\""))
    }
}
