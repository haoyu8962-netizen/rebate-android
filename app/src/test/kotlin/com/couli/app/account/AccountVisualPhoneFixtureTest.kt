package com.couli.app.account

import com.couli.core.me.account.AccountDraftTexts
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-13v-b fixture 自检（specs/account-visual-vectors.json 追加的 segmentB 组，G-D15：a 段测试守卫没有冻结该文件，按「只追加 b 段组」写；a 段各组逐字节不变由
 * AccountVisualPhoneGuardTest.fixtureKeepsSegmentAByteIdentical 核）。键名避开 gitleaks 敏感词（a 段 keysAvoidSensitiveWords 走全文件，同样覆盖本组）；
 * b 段 20 张画板每张都有截图或「不对照」去向；截图名与截图入口源码逐一对应；保留草稿值与现行一致（G-D08 措辞不改）。底版上应全绿。
 */
class AccountVisualPhoneFixtureTest {
    private val b = AccountVisualFixtures.obj("segmentB")

    private fun list(name: String): List<String> = b.getValue(name).jsonArray.map { it.jsonPrimitive.content }

    @Test fun everyBoardOfSegmentBHasASnapshotOrANotComparedReason() {
        val shots = list("snapshots")
        val notCompared = b.getValue("notCompared").jsonObject.keys
        AccountVisualFixtures.boards("b").forEach { board ->
            val name = AccountVisualFixtures.text("name", board)
            val covered = shots.any { it.startsWith(name) } || notCompared.any { it.contains(name) }
            assertTrue("画板 $name 有截图或不对照原因", covered)
        }
        assertEquals("截图名不重复", shots.size, shots.toSet().size)
        assertTrue("两张深色稿出深色", shots.contains("PhoneChangeCodeDark-cells-GD04-dark") && shots.contains("BindPhoneDark-dark"))
    }

    @Test fun snapshotNamesMatchTheSnapshotEntries() {
        val source =
            File(
                AccountVisualFixtures.root,
                "app/src/test/kotlin/com/couli/app/account/PhoneAccountVisualSnapshotTest.kt",
            ).readText()
        list("snapshots").forEach { assertTrue("截图入口里有 \"$it\"", source.contains("\"$it\"")) }
        val entries = Regex("""shot\("([^"]+)"\)""").findAll(source).map { it.groupValues[1] }.toList()
        assertEquals("入口与名单一一对应", list("snapshots").toSet(), entries.toSet())
    }

    @Test fun keptDraftsMatchTheCurrentDrafts() {
        b.getValue("draftsKept").jsonObject.forEach { (key, value) ->
            val current = AccountDraftTexts.DRAFTS[key] ?: LoginDraftTextsProbe.raw(key)
            assertEquals("保留不改的草稿值：$key", value.jsonPrimitive.content, current)
        }
    }

    @Test fun newDraftKeysAreTheTwoG17KeysOnly() {
        val drafts = b.getValue("drafts").jsonObject
        assertEquals(setOf("invite.bind_phone_guide.benefit", "invite.bind_phone_guide.merge_hint"), drafts.keys)
        drafts.values.forEach { assertFalse("新句不含旧说法「可在期限内填写邀请码」", it.jsonPrimitive.content.contains("邀请码")) }
    }

    @Test fun pendingSeedsAndPhonesStayMaskedAndBodiless() {
        val fields = b.getValue("pendingBodies").jsonObject.getValue("fields") as JsonArray
        assertEquals("原体只有 phone + code 两个字段（宿主内存里拼）", listOf("phone", "code"), fields.map { it.jsonPrimitive.content })
        listOf("phone_change", "bind_phone").forEach {
            assertTrue("未决摘要只放脱敏号码：$it", Regex("""^1\d{2}\*{4}\d{4}$""").matches(AccountVisualFixtures.pendingSummary(it)))
        }
        val merge = b.getValue("mergeable30411").jsonObject
        assertEquals("mergeable", merge.getValue("reason").jsonPrimitive.content)
        assertEquals(AccountVisualFixtures.phone("bindMasked"), merge.getValue("maskedTarget").jsonPrimitive.content)
        val raw = AccountVisualFixtures.file.readText()
        assertFalse("不含像令牌的串", Regex("""(?i)(tok-|access-|refresh-|bearer|step_up_token)""").containsMatchIn(raw))
    }

    @Test fun forbiddenWordingCoversGD05GD08GD09() {
        val forbidden = list("forbiddenOnPage")
        assertTrue(forbidden.contains("秒后可重新获取"))
        assertTrue(forbidden.contains(AccountVisualFixtures.phone("current")))
        assertTrue(forbidden.contains("收不到验证码"))
    }
}
