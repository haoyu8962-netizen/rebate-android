package com.couli.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** V1 / V4 / V5 / V6：独立原图参照 + 三种 source 令牌逐项探针，不能用一个整体换色蒙混。 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class Fx05aPlatformRenderingTest(
    private val dark: Boolean,
) {
    @get:Rule val rule = createComposeRule()

    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun badgeHasOriginalFourteenDpImageAtTheLeftAndChineseText() {
        var sample by mutableStateOf(badges.first())
        var legacy by mutableStateOf(false)
        rule.setContent {
            CouliTheme(darkTheme = dark) {
                Column {
                    PlatformBadge(
                        name = sample.name,
                        platformCode = if (legacy) null else sample.code,
                        modifier = Modifier.testTag("actual"),
                    )
                    ReferenceBadge(sample)
                }
            }
        }
        badges.forEach { next ->
            listOf(false, true).forEach { oldCall ->
                rule.runOnIdle {
                    sample = next
                    legacy = oldCall
                }
                compare("${next.code}, legacy=$oldCall, dark=$dark")
            }
        }
    }

    @Test
    fun bothSizesDrawTheSameOfficialImageEvenWhenARemoteUrlIsProvided() {
        var code by mutableStateOf("taobao")
        var size by mutableStateOf(PlatformIconSize.BADGE)
        var remote by mutableStateOf<String?>(null)
        rule.setContent {
            CouliTheme(darkTheme = dark) {
                Column {
                    Box(Modifier.background(CouliColors.backgroundCanvas).testTag("actual")) {
                        PlatformIcon(platformCode = code, size = size, remoteUrl = remote)
                    }
                    Box(Modifier.background(CouliColors.backgroundCanvas).testTag("reference")) {
                        Fx05aOfficialReference(if (code == "pdd") "pinduoduo" else code, size.dp)
                    }
                }
            }
        }
        val cases = homeCodes.flatMap { platform -> PlatformIconSize.entries.map { platform to it } }
        cases.forEach { (platform, preset) ->
            listOf(null, "https://unused.invalid/replacement.png").forEach { address ->
                rule.runOnIdle {
                    code = platform
                    size = preset
                    remote = address
                }
                rule.onNodeWithTag("actual").assertWidthIsEqualTo(preset.dp).assertHeightIsEqualTo(preset.dp)
                compare("$platform / $preset / $address")
            }
        }
    }

    @Test
    fun sourceBackgroundBorderAndTextEachConsumeTheirThemeTokenWithoutTintingTheIcon() {
        var overrides by mutableStateOf(emptyMap<String, Color>())
        var remote by mutableStateOf<String?>(null)
        var reference by mutableStateOf(false)
        rule.setContent {
            CouliTheme(darkTheme = dark, colorOverrides = overrides) {
                Column {
                    // 两者轮流占同一位置，避免圆角抗锯齿因绝对位置不同而产生假阳性。
                    if (reference) {
                        ReferenceBadge(badges.first())
                    } else {
                        PlatformBadge(
                            name = "淘宝",
                            platformCode = "taobao",
                            remoteIconUrl = remote,
                            modifier = Modifier.testTag("actual"),
                        )
                    }
                }
            }
        }
        compareAtSamePosition("default dark=$dark") { reference = it }
        listOf("source.background", "source.border", "source.text").forEach { key ->
            listOf(Color(0xFF19CBE1), Color(0xFFE137AC)).forEach { sentinel ->
                rule.runOnIdle {
                    overrides = mapOf(key to sentinel)
                    remote = "https://unused.invalid/replacement.png"
                }
                compareAtSamePosition("$key / $sentinel / dark=$dark") { reference = it }
            }
        }
    }

    @Test
    fun standaloneMissingIconsTakeNoSpaceWhileOfficialIconsActuallyDraw() {
        var code by mutableStateOf("taobao")
        var size by mutableStateOf(PlatformIconSize.BADGE)
        rule.setContent {
            CouliTheme(darkTheme = dark) {
                Column {
                    // 包装层没有 size / padding，不会替被测组件保留尺寸，也不依赖其透传 modifier。
                    Box(Modifier.testTag("icon-slot")) {
                        PlatformIcon(platformCode = code, size = size, remoteUrl = "https://unused.invalid/replacement.png")
                    }
                }
            }
        }
        listOf(PlatformIconSize.BADGE, PlatformIconSize.HOME).forEach { preset ->
            rule.runOnIdle {
                code = "taobao"
                size = preset
            }
            assertOfficialIconDraws(preset)
            missingCodes.forEach { missing ->
                rule.runOnIdle { code = missing }
                val measured = rule.onNodeWithTag("icon-slot").fetchSemanticsNode().size
                assertEquals("$missing / $preset 缺图不占宽度（px）", 0, measured.width)
                assertEquals("$missing / $preset 缺图不占高度（px）", 0, measured.height)
            }
            rule.runOnIdle { code = "taobao" }
            assertOfficialIconDraws(preset)
        }
    }

    private fun assertOfficialIconDraws(size: PlatformIconSize) {
        rule.onNodeWithTag("icon-slot").assertWidthIsEqualTo(size.dp).assertHeightIsEqualTo(size.dp)
        val image = rule.fx05aPixels("icon-slot", temporary.root)
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        assertTrue("有图平台必须实际绘制，不能只保留空白尺寸", pixels.any { it != pixels.first() })
    }

    @Test
    fun explicitCodeWinsOverTheDisplayNameAndRemoteAddress() {
        var sample by mutableStateOf(BadgeSample("pdd", "平台来源", "pinduoduo"))
        rule.setContent {
            CouliTheme(darkTheme = dark) {
                Column {
                    PlatformBadge(
                        name = sample.name,
                        platformCode = sample.code,
                        remoteIconUrl = "https://unused.invalid/replacement.png",
                        modifier = Modifier.testTag("actual"),
                    )
                    ReferenceBadge(sample)
                }
            }
        }
        compare("中文名不能替代显式编码")
        rule.runOnIdle { sample = BadgeSample("future_shop", "淘宝", null) }
        compare("未知显式编码不能借中文名错误匹配淘宝图")
    }

    private fun compare(label: String) {
        rule.waitForIdle()
        assertFx05aPixelsEqual(label, rule.fx05aPixels("reference", temporary.root), rule.fx05aPixels("actual", temporary.root))
    }

    private fun compareAtSamePosition(
        label: String,
        showReference: (Boolean) -> Unit,
    ) {
        rule.runOnIdle { showReference(false) }
        rule.waitForIdle()
        val actualPosition =
            rule
                .onNodeWithTag("actual", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot.topLeft
        val actual = rule.fx05aPixels("actual", temporary.root)
        rule.runOnIdle { showReference(true) }
        rule.waitForIdle()
        val referencePosition =
            rule
                .onNodeWithTag("reference", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot.topLeft
        assertEquals("参照与实际必须在相同的绝对位置", actualPosition, referencePosition)
        assertFx05aPixelsEqual(label, rule.fx05aPixels("reference", temporary.root), actual)
    }

    /** 与设计规范一致的参照排版，图直接解码官方原件；不复用被测徽标、resolver 或任何产品图标。 */
    @Composable
    private fun ReferenceBadge(sample: BadgeSample) {
        val tokens = couliColorTokens()
        val shape = RoundedCornerShape(CouliRadius.pill)
        Row(
            modifier =
                Modifier
                    .testTag("reference")
                    .heightIn(min = CouliSizes.badgeHeight)
                    .clip(shape)
                    .background(tokens.getValue("source.background"))
                    .border(CouliSizes.hairline, tokens.getValue("source.border"), shape)
                    .padding(horizontal = CouliSpacing.space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
        ) {
            sample.file?.let { Fx05aOfficialReference(it, 14.dp) }
            Text(sample.name, style = CouliTypography.caption, color = tokens.getValue("source.text"), softWrap = false)
        }
    }

    private data class BadgeSample(
        val code: String,
        val name: String,
        val file: String?,
    )

    companion object {
        private val homeCodes = listOf("taobao", "jd", "pdd", "vip", "douyin", "meituan", "eleme", "fliggy", "didi")
        private val missingCodes =
            listOf("tmall", "wechat", "wechat_pay", "alipay", "wecom", "huawei", "apple", "kuaishou", "suning", "future_shop", "")
        private val badges =
            listOf(
                BadgeSample("taobao", "淘宝", "taobao"),
                BadgeSample("jd", "京东", "jd"),
                BadgeSample("pdd", "拼多多", "pinduoduo"),
                BadgeSample("tmall", "天猫", null),
                BadgeSample("wechat", "微信", null),
                BadgeSample("future_shop", "future_shop", null),
                BadgeSample("", "未知平台", null),
            )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun parameters(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
    }
}
