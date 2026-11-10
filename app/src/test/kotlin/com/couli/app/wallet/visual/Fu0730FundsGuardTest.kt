package com.couli.app.wallet.visual

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FU-AND-0730 零改动护栏（只读源码扫描，底版 c1b3c29 应为绿；风格同 [WithdrawVisualFundsGuardTest]，不改该已合并守卫与其基线）。
 * - 资金逻辑整文件不变：WithdrawController（onAmountChange / setAmount 去抖与估算发起 / submit）、WithdrawAmountInput.accept 的规则
 *   （WithdrawLogic.kt）、WithdrawalText、WithdrawRecordsController、SensitiveFlow。
 * - 不新增文案键（WalletTexts.kt 不变）；组件库 core/designsystem/src/main 整目录不变。
 * - 提现页金额框仍只经 `model.onAmountChange(…, owner)` 一处进控制器；【全部提现】/ 档位 / 【提交】回调行逐字不变。
 * - 记录展开卡回调（收起 / 修改账号 / 复制 / 联系客服）各一处、逐字不变；明细金额各取服务端字段（不推算）。
 */
class Fu0730FundsGuardTest {
    private val root = WithdrawVisualFixtures.root

    @Test fun fundsLogicFilesAreByteIdenticalToC1b3c29() {
        val expected =
            mapOf(
                "feature/wallet/src/main/kotlin/com/couli/feature/wallet/WithdrawController.kt" to
                    "e54e41fb030260c1dea3ad7799a9ddf937c65ac25817f1cf45ef7f7da01230ae",
                "core/wallet/src/main/kotlin/com/couli/core/wallet/WithdrawLogic.kt" to
                    "633e202fb0f5b0a60ef41b70eeb266dd99a34e11da11dca45c1496fa4a14bb8d",
                "feature/wallet/src/main/kotlin/com/couli/feature/wallet/WithdrawRecordsController.kt" to
                    "3a5186491f9da46544d25822ad36569bf389dfcaa0fa19cdcc3c321cf20e8661",
                "core/wallet/src/main/kotlin/com/couli/core/wallet/WithdrawalText.kt" to
                    "d21a60f5a84c2757ddc8607db9e3fa376d16e476a7622fd5bfd0aea6aad88f7e",
                "feature/wallet/src/main/kotlin/com/couli/feature/wallet/SensitiveFlow.kt" to
                    "920ee6a46bdfaa31fb40b8dfc1348f70e03244e036223456f61d32fbfdcbac1d",
                "core/wallet/src/main/kotlin/com/couli/core/wallet/WalletTexts.kt" to
                    "acba4a6c6183795189f800f87c70ba46b0f2d903c195913f4079558db0c87cba",
            )
        expected.forEach { (path, sha) -> assertEquals("零改动：$path", sha, sha256(File(root, path).readBytes())) }
    }

    @Test fun designSystemSourcesAreUnchanged() {
        val dir = File(root, "core/designsystem/src/main")
        val files =
            dir
                .walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(dir).invariantSeparatorsPath }
                .sorted()
                .toList()
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { path ->
            digest.update(path.toByteArray())
            digest.update(0)
            digest.update(File(dir, path).readBytes())
        }
        assertEquals("组件库文件数", 46, files.size)
        assertEquals("组件库 core/designsystem/src/main 不改", DESIGN_SYSTEM_DIGEST, hex(digest.digest()))
    }

    @Test fun withdrawScreenCallbacksStillRouteThroughTheSameEntries() {
        val s = source("feature/wallet/src/main/kotlin/com/couli/feature/wallet/WithdrawScreen.kt")
        val routed = AMOUNT_ENTRY.findAll(s).count()
        assertEquals("金额框只经一处 model.onAmountChange(…, owner) 进控制器", 1, routed)
        assertEquals("页面里 model.onAmountChange( 只此一处", 1, count(s, "model.onAmountChange("))
        assertEquals("ViewModel 进控制器的金额入口仍是两处（草稿恢复 + 输入）", 2, count(s, "controller.onAmountChange("))
        listOf(
            "{ model.fillAll(version, owner) }.tapped(WalletTags.WITHDRAW_ALL)",
            "{ model.fillQuick(version, fen, owner) }.tapped(WalletTags.WITHDRAW_QUICK_PREFIX + fen)",
            "{ controller.submit(version, owner) }.tapped(WalletTags.WITHDRAW_SUBMIT)",
        ).forEach { assertEquals("回调逐字不变：$it", 1, count(s, it)) }
        assertEquals("不新增 controller.setAmount / 直写金额", 0, count(s, "amountText ="))
    }

    @Test fun recordCardCallbacksAndMoneyFieldsUnchanged() {
        val s = source("feature/wallet/src/main/kotlin/com/couli/feature/wallet/WithdrawRecordDetail.kt")
        listOf(
            "ctx.controller.toggle(version, id)",
            "ctx.controller.changeAccount(version, id)",
            "ctx.controller.copy(version, id)",
            "ctx.controller::contact",
        ).forEach { assertEquals("展开卡回调逐字不变、各一处：$it", 1, count(s, it)) }
        listOf("item.amountFen", "item.feeFen", "item.netFen").forEach { assertEquals("明细金额取服务端字段：$it", 1, count(s, it)) }
        assertEquals("不推算金额（明细里不出现分的加减）", 0, Regex("""Fen\s*[-+]\s*\w""").findAll(s).count())
    }

    private fun source(path: String) = File(root, path).readText()

    private fun count(
        s: String,
        needle: String,
    ) = s.windowed(needle.length).count { it == needle }

    private fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        const val DESIGN_SYSTEM_DIGEST = "fe766a67b22d38189db41d11923666c9e74807238cd48aa1a05ddea80a795059"
        val AMOUNT_ENTRY = Regex("""model\.onAmountChange\([^()]*, owner\)""")
    }
}
