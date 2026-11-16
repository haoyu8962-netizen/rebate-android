package com.couli.app.me

import com.couli.core.me.MeJson
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeVisualFixtureTest {
    @Test fun completeVisualReportsParseAndNeverInventWalletFields() {
        val profiles = MeVisualFixtures.visual.getValue("profiles").jsonObject
        assertEquals(8, profiles.size)
        profiles.forEach { (name, raw) ->
            assertNotNull(name, MeJson.me(raw))
            assertEquals(
                setOf("available_fen", "withdrawable_fen", "estimated_total_fen"),
                raw.jsonObject
                    .getValue("balance")
                    .jsonObject.keys,
            )
        }
        assertEquals(16, MeJson.me(MeVisualFixtures.profile("me_long"))!!.nickname.length)
        assertEquals(1234L, MeJson.me(MeVisualFixtures.profile("me_no_sum"))!!.withdrawableFen)
        assertEquals(5678L, MeJson.me(MeVisualFixtures.profile("me_no_sum"))!!.estimatedTotalFen)
    }

    @Test fun gm18ExecutorFirst192LinesAreExactlyFrozen() {
        val source = File(MeVisualFixtures.root, "feature/me/src/main/kotlin/com/couli/feature/me/MeTabController.kt").readText()
        val prefix = source.lineSequence().take(192).joinToString("\n") + "\n"
        assertEquals("a31b089a0d0ef105c7b5b7459c5920ec569e0de2c52a18778ea7535784476259", sha(prefix.toByteArray()))
    }

    @Test fun protectedRuntimeAndSharedWalletDefaultHaveZeroChanges() {
        val frozen =
            mapOf(
                "feature/me/src/main/kotlin/com/couli/feature/me/MeRepository.kt" to
                    "c2c17ef3ed3b96631eabcf5d09ea987f6262ff055ff18324fcdb56e023256d8d",
                "core/me/src/main/kotlin/com/couli/core/me/MePresentation.kt" to
                    "7dc5f1551dd8af3a36226ff259b7b64f4fc0247d4258e0b2a17542bf4ab2c86b",
                // modify_existing（#24）re-pin ruling D10-1670：FU-AND-STEPUP-SUMMARY Me 加 masked_phone（B1-02zzza）
                "core/me/src/main/kotlin/com/couli/core/me/MeJson.kt" to "af5f873d68b50ea3378abef0dd4860c47737e6756a28b4bf9033e1e76230009f",
                // 钉值随锁升级更新：FU-X-CTA-KEY-DRIFT 锁 a89c1ec3a（D10-857，#24）
                // modify_existing（#24）re-pin ruling D10-1670：锁 d47f660e5（FU-AND-STEPUP-SUMMARY）
                "contract.lock" to "0ef8f287fee288204af64118d18491848ee977225ab798a9965a28acfd082b75",
                // 钉值随主干合法变更更新：APP-08v-b AmountGrid valueSizes（7db97ce）
                "core/designsystem/src/main/kotlin/com/couli/core/designsystem/WalletComponents.kt" to
                    "c05adc8af581f6f48ed367ebd7c3f5fd05e98a8760c4353eda510c952b77532b",
                // modify_existing（#24）re-pin ruling D10-1709：FU-AND-STEPUP-SUMMARY 身份未知补单，隐私撤回按请求体声明照发（03 §4.2）
                "app/src/main/kotlin/com/couli/app/me/AppMe.kt" to "f21305b0c06a62d2881b4071b04ab7e2875e708d0412048d467704677eafd21f",
                "app/src/main/kotlin/com/couli/app/me/MeRoutes.kt" to "48b82188f8e1b71fffcda62e54f18baece2ed8b867517c2fd6a7bfbaa41e0fc2",
                "app/src/main/kotlin/com/couli/app/me/AndroidMeSystem.kt" to
                    "6cf6830ec9410d66088de9e8bc26c5dcab446845bab11e57bc1e6fdc85cef18d",
                "app/src/main/kotlin/com/couli/app/me/AppMeTexts.kt" to "b49f72964fd4e27a8a6ff3fab7dc578b65ecd0a9909cb2bbde88db25e6b0029b",
            )
        frozen.forEach { (path, expected) -> assertEquals(path, expected, sha(File(MeVisualFixtures.root, path).readBytes())) }
    }

    @Test fun m14EarningsUsesExistingBarChartIcon() {
        val dir = File(MeVisualFixtures.root, "feature/me/src/main/kotlin/com/couli/feature/me")
        val source = dir.listFiles()!!.filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertTrue("收益看板使用已有barChart", Regex("\"me\\.grid\\.earnings\"\\s*->\\s*CouliIcons\\.barChart").containsMatchIn(source))
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
