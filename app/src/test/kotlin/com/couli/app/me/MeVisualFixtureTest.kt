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
                    "9da8db187987dab2ddddd0ff131cd37d0867249e0e778cdb8cf67f47457e5940",
                "core/me/src/main/kotlin/com/couli/core/me/MeJson.kt" to "65eb62b5571856f11d06c82cb3c7da3a957ad9ec69eb0c8f71ae5a560d910e9c",
                "contract.lock" to "ee878ac376054c0ad652bcde9442038b29c20ead7266afd9495446cf1213cd03",
                // 钉值随主干合法变更更新：APP-08v-b AmountGrid valueSizes（7db97ce）
                "core/designsystem/src/main/kotlin/com/couli/core/designsystem/WalletComponents.kt" to
                    "ba9c7019e59c17a107e73e7f67349ec2bafe22bf81f8445422b7f4253433d611",
                "app/src/main/kotlin/com/couli/app/me/AppMe.kt" to "259029cb6d425f61bd767c6a52c9bd31a31f2790e4fe03d575d7e0724b394f25",
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
