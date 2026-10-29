package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.productPixels
import com.couli.core.wallet.PayoutAccountForm
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** W69–W76：未绑定 / 银行卡表单的版式；先正后反，像素在实际绘制结果上取。请求次数与表单身份另由计数断言守住。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1100dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PayoutVisualFormTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PayoutIdentityVisualHost(rule) }
    private val p by lazy { PayoutIdentityProbe(rule) }

    @After fun close() = host.close()

    @Test fun w69ContentStartsEightBelowNavbarAndCardsKeepSixteenSideMargin() {
        unbound()
        val d = density()
        val intro = p.bounds(t("payout.intro"))
        assertEquals("W69 首块距顶栏 8dp", 8f, (intro.top - p.navBottom(t("payout.title"))) / d, 1f)
        val y =
            p
                .tag(WalletTags.PAYOUT_METHOD_ALIPAY)
                .fetchSemanticsNode()
                .boundsInRoot.center.y
        val pixels = rule.productPixels()
        val canvas = host.color("background.canvas").argb()
        val surface = host.color("background.surface").argb()
        assertTrue("W69 卡片左缘在 16dp：15dp 处画布", near(pixels.pixel(15 * d, y), canvas, 10))
        assertTrue("W69 卡片左缘在 16dp：17dp 处白卡", near(pixels.pixel(17 * d, y), surface, 4))
        assertTrue("W69 卡片右缘在 374dp：373dp 处白卡", near(pixels.pixel(373 * d, y), surface, 4))
        assertTrue("W69 卡片右缘在 374dp：375dp 处画布", near(pixels.pixel(375 * d, y), canvas, 10))
    }

    @Test fun w70IntroSitsOutsideCardsAsFourteenSecondaryIndentedFour() {
        unbound()
        val d = density()
        val style = p.style(t("payout.intro"))
        assertEquals(14f, style.fontSize.value, .01f)
        assertEquals(host.color("text.secondary"), style.color)
        assertEquals("W70 说明句左缩进 4（16+4）", 20f, p.glyphLeft(t("payout.intro")) / d, 1f)
        val intro = p.bounds(t("payout.intro"))
        val pixels = rule.productPixels()
        assertTrue(
            "W70 说明句在卡外：字左侧是画布色而不是白卡",
            near(pixels.pixel(18 * d, intro.center.y), host.color("background.canvas").argb(), 4),
        )
        assertTrue(
            "W70 说明句在收款方式卡之上",
            intro.bottom <=
                p
                    .tag(WalletTags.PAYOUT_METHOD_ALIPAY)
                    .fetchSemanticsNode()
                    .boundsInRoot.top,
        )
    }

    @Test fun w71MethodChoicesAreTheirOwnCardSeparatedFromFields() {
        unbound()
        val d = density()
        val bank = p.tag(WalletTags.PAYOUT_METHOD_BANK).fetchSemanticsNode().boundsInRoot
        val label = p.bounds(t("payout.alipay_account"))
        assertTrue("字段在单选之后", label.top > bank.bottom)
        val column = Rect(30 * d, bank.bottom, 31 * d, label.top)
        val canvasRows = rule.productPixels().countNear(column, host.color("background.canvas").argb(), 8)
        assertTrue("W71 单选卡与表单卡之间必须露出画布（独立两张卡），实际画布行=${canvasRows / d}dp", canvasRows >= 4 * d)
    }

    @Test fun w71SelectedRadioIsTwentyFourWithSevenBrandRingAndUnselectedTwentyFourControlRing() {
        unbound()
        val d = density()
        val pixels = rule.productPixels()
        val alipay = p.tag(WalletTags.PAYOUT_METHOD_ALIPAY).fetchSemanticsNode().boundsInRoot
        val ring = pixels.boxOf(Rect(alipay.left, alipay.top, alipay.left + 30 * d, alipay.bottom), host.color("brand.primary").argb(), 30)
        assertNotNull("选中圈用品牌色", ring)
        ring!!
        assertEquals("W71 选中圈 24dp 宽", 24f, ring.width / d, 1f)
        assertEquals("W71 选中圈 24dp 高", 24f, ring.height / d, 1f)
        assertTrue("圈心留白", near(pixels.pixel(ring.center.x, ring.center.y), host.color("background.surface").argb(), 12))
        assertTrue(
            "W71 选中描边 7dp：距外缘 5dp 处仍是品牌色",
            near(pixels.pixel(ring.left + 5 * d, ring.center.y), host.color("brand.primary").argb(), 30),
        )
        val bank = p.tag(WalletTags.PAYOUT_METHOD_BANK).fetchSemanticsNode().boundsInRoot
        val idle = pixels.boxOf(Rect(bank.left, bank.top, bank.left + 30 * d, bank.bottom), host.color("border.control").argb(), 30)
        assertNotNull("未选圈用控件描边色", idle)
        assertEquals("W71 未选圈也是 24dp", 24f, idle!!.width / d, 1f)
    }

    @Test fun w71SelectedTitleIsSemiboldUnselectedRegularDescriptionTwelve() {
        unbound()
        val selected = p.style(t("payout.alipay"))
        assertEquals(17f, selected.fontSize.value, .01f)
        assertEquals("W71 选中项 600", FontWeight.W600, selected.fontWeight)
        assertEquals("W71 未选项 400", FontWeight.W400, p.style(t("payout.bank")).fontWeight ?: FontWeight.W400)
        val desc = p.style(t("payout.alipay_desc"))
        assertEquals(12f, desc.fontSize.value, .01f)
        assertEquals(host.color("text.secondary"), desc.color)
    }

    @Test fun w71HairlineDividerBetweenTheTwoOptions() {
        unbound()
        val d = density()
        val alipay = p.tag(WalletTags.PAYOUT_METHOD_ALIPAY).fetchSemanticsNode().boundsInRoot
        val bank = p.tag(WalletTags.PAYOUT_METHOD_BANK).fetchSemanticsNode().boundsInRoot
        // 卡片 16..374dp，卡内左右 16 内距
        val band = Rect(32 * d, alipay.bottom - 3 * d, 358 * d, bank.top + 3 * d)
        val line = rule.productPixels().boxOf(band, host.color("border.subtle").argb(), 4)
        assertNotNull("W71 两项之间要有 1dp 分隔线", line)
        assertTrue("分隔线横贯卡内（左右 16 内距）", line!!.width >= band.width - 2 * d)
        assertTrue("分隔线是细线", line.height <= 2 * d)
    }

    @Test fun w71OptionsAreSelectableGroupWithSeparateFortyEightTargetsAndChoosingWritesNothing() {
        unbound()
        val d = density()
        val alipay = p.tag(WalletTags.PAYOUT_METHOD_ALIPAY).fetchSemanticsNode()
        val bank = p.tag(WalletTags.PAYOUT_METHOD_BANK).fetchSemanticsNode()
        val group =
            generateSequence(alipay.parent) { it.parent }.firstOrNull {
                it.config.getOrNull(SemanticsProperties.SelectableGroup) !=
                    null
            }
        assertNotNull("W71 两个单选项在 selectableGroup 内", group)
        assertTrue("同一组", generateSequence(bank.parent) { it.parent }.any { it.id == group!!.id })
        listOf(alipay, bank).forEach { assertTrue("单选行命中 ≥48dp", it.touchBoundsInRoot.height >= 48 * d) }
        assertRecordTargetsSeparate(alipay.touchBoundsInRoot, bank.touchBoundsInRoot)
        p.tag(WalletTags.PAYOUT_METHOD_BANK).performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals(
            true,
            p
                .tag(WalletTags.PAYOUT_METHOD_BANK)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Selected),
        )
        p.tag(WalletTags.PAYOUT_CARD).assertExists()
        assertEquals("选方式零写请求", 0, host.api.writes().size)
        assertEquals("选方式不生成幂等键", 0, host.keys)
    }

    @Test fun w72LabelToBoxEightAndFieldsSixteenApart() {
        unbound()
        val d = density()
        val logonLabel = p.bounds(t("payout.alipay_account"))
        val logonBox = p.fieldBox(WalletTags.PAYOUT_LOGON)
        assertEquals("W72 标签与框间 8dp", 8f, (logonBox.top - logonLabel.bottom) / d, 1f)
        val payeeLabel = p.bounds(t("payout.payee"))
        assertEquals("W72 卡内字段间 16dp", 16f, (payeeLabel.top - logonBox.bottom) / d, 1f)
        assertEquals("W72 姓名标签与框间 8dp", 8f, (p.fieldBox(WalletTags.PAYOUT_PAYEE).top - payeeLabel.bottom) / d, 1f)
    }

    @Test fun w73PayeeHintHugsNameBoxAsTwelveSecondary() {
        unbound()
        val d = density()
        val hint = p.bounds(t("payout.payee_note"))
        val box = p.fieldBox(WalletTags.PAYOUT_PAYEE)
        assertEquals("W73 提示紧贴姓名框下 8dp", 8f, (hint.top - box.bottom) / d, 1f)
        val style = p.style(t("payout.payee_note"))
        assertEquals(12f, style.fontSize.value, .01f)
        assertEquals(host.color("text.secondary"), style.color)
        assertTrue("提示在保存说明之前", hint.bottom < p.bounds(t("payout.step_up_note")).top)
    }

    @Test fun w74StepUpNoteIsInfoBarOutsideFormCard() = infoBar(t("payout.step_up_note"))

    @Test fun w75BankFormKeepsEditableBankNameAndUsesBoardSentenceInsteadOfThreeFactorPromise() {
        unbound()
        p.tag(WalletTags.PAYOUT_METHOD_BANK).performTouchInput { click(center) }
        rule.waitForIdle()
        host.type(WalletTags.PAYOUT_CARD, PayoutIdentityVisualFixtures.form("bank", "cardNo"))
        host.type(WalletTags.PAYOUT_BANK_NAME, PayoutIdentityVisualFixtures.form("bank", "bankName"))
        assertEquals(
            "G-W23 保留开户行输入",
            PayoutIdentityVisualFixtures.form("bank", "bankName"),
            p
                .tag(WalletTags.PAYOUT_BANK_NAME)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.EditableText)
                ?.text,
        )
        assertEquals(
            "W75 银行卡也只说保存时需要短信验证（CAP-X-08 前不承诺三要素核验）",
            0,
            rule.onAllNodesWithText("银行卡是否一致", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size,
        )
        assertEquals(1, rule.onAllNodesWithText(t("payout.step_up_note"), useUnmergedTree = true).fetchSemanticsNodes().size)
        infoBarAssertions(t("payout.step_up_note"))
        host.type(WalletTags.PAYOUT_PAYEE, PayoutIdentityVisualFixtures.form("bank", "payeeName"))
        host.api.write = PayoutIdentityVisualFixtures.ok()
        p.tag(WalletTags.PAYOUT_SAVE).performTouchInput { click(center) }
        rule.waitForIdle()
        val saves = host.api.calls.filter { it.operationId == "savePayoutAccount" }
        assertEquals("真实点击保存恰一次", 1, saves.size)
        val body = WithdrawVisualFixtures.obj(checkNotNull(saves.single().body))
        assertEquals("请求体字段不变：开户行仍由用户填写", "招商银行", body.getValue(PayoutAccountForm.BANK_NAME).jsonPrimitive.content)
        assertEquals("bank_card", body.getValue(PayoutAccountForm.PAYOUT_METHOD).jsonPrimitive.content)
        assertTrue(body.containsKey(PayoutAccountForm.CARD_NO))
    }

    @Test fun w76SaveBarHasNoSurfaceBackgroundAndStillSavesOnce() {
        unbound()
        val d = density()
        val save = p.tag(WalletTags.PAYOUT_SAVE).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "W76 底栏无底色：按钮外侧是画布色",
            near(rule.productPixels().pixel(8 * d, save.center.y), host.color("background.canvas").argb(), 4),
        )
        host.type(WalletTags.PAYOUT_LOGON, PayoutIdentityVisualFixtures.form("alipay", "alipayAccount"))
        host.type(WalletTags.PAYOUT_PAYEE, PayoutIdentityVisualFixtures.form("alipay", "payeeName"))
        host.api.write = PayoutIdentityVisualFixtures.ok()
        p.tag(WalletTags.PAYOUT_SAVE).performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals(1, host.api.calls.count { it.operationId == "savePayoutAccount" })
        assertEquals("一次保存只生成一个键", 1, host.keys)
    }

    @Test fun localValidationKeepsFieldErrorsUnderFieldsAndWritesNothing() {
        unbound()
        p.tag(WalletTags.PAYOUT_SAVE).performTouchInput { click(center) }
        rule.waitForIdle()
        p.tag(WalletTags.PAYOUT_FIELD_ERROR_PREFIX + PayoutAccountForm.ALIPAY_LOGON_ID).assertExists()
        p.tag(WalletTags.PAYOUT_FIELD_ERROR_PREFIX + PayoutAccountForm.PAYEE_NAME).assertExists()
        val error = p.tag(WalletTags.PAYOUT_FIELD_ERROR_PREFIX + PayoutAccountForm.ALIPAY_LOGON_ID).fetchSemanticsNode().boundsInRoot
        assertTrue("账号错误在账号框下", error.top >= p.fieldBox(WalletTags.PAYOUT_LOGON).bottom)
        assertTrue("账号错误在姓名标签之上", error.bottom <= p.bounds(t("payout.payee")).top)
        assertEquals("本地校验失败零写请求", 0, host.api.writes().size)
        assertEquals(0, host.keys)
        rule.mainClock.advanceTimeBy(300)
        rule.waitForIdle()
        assertEquals("首个错误字段只播报一次", 1, host.spoken.count { it == t("error.20001") })
    }

    private fun infoBar(text: String) {
        unbound()
        infoBarAssertions(text)
    }

    private fun infoBarAssertions(text: String) {
        val d = density()
        val style = p.style(text)
        assertEquals("INFO 条 14", 14f, style.fontSize.value, .01f)
        assertEquals("INFO 条主文字色", host.color("text.primary"), style.color)
        val bounds = p.bounds(text)
        val pixels = rule.productPixels()
        assertTrue(
            "INFO 条浅灰底（在卡外，不是白卡上的纯文字）",
            near(pixels.pixel(18 * d, bounds.center.y), host.color("background.muted").argb(), 2),
        )
        val left = p.glyphLeft(text)
        val icon = Rect(left - 30 * d, bounds.top, left - 4 * d, bounds.top + 22 * d)
        assertTrue(
            "INFO 条左侧 20dp 次文字色信息图标",
            pixels.countNear(icon, host.color("text.secondary").argb(), 40) > 12 * d,
        )
        pixels.assertInfoIconInk(icon, host, "INFO 条图标")
    }

    private fun unbound() {
        host.api.payout = PayoutIdentityVisualFixtures.ok(PayoutIdentityVisualFixtures.payout("unbound"))
        host.launch(PayoutIdentityPage.PAYOUT)
    }

    private fun density(): Float = p.layoutOf(WalletTags.PAYOUT_PAGE).density.density

    private fun t(key: String) = WalletDraftTexts.text(key)
}
