@file:OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)

package com.couli.core.designsystem

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 来源证明独立于组件自报；实际图形仍由 OAuthButton / ThirdPartyLoginRow 两态基准复核。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class Ds02OAuthAssetTest {
    @get:Rule val rule = createComposeRule()

    @get:Rule val assets = TemporaryFolder()

    @Test
    fun approvedOfficialVectorResourcesArePackagedUnchanged() {
        val approved = approvedAssets()
        if (!requireApprovalState(approved)) {
            assertPendingRowRejectsGenericChat()
            return
        }
        var context: Context? = null
        rule.setContent { context = LocalContext.current }
        rule.waitForIdle()
        val resources = requireNotNull(context).resources
        listOf(OAuthProvider.WECHAT, OAuthProvider.HUAWEI).forEach { provider ->
            val prefix = provider.name
            val expectedName = approved.getProperty("$prefix.resource").orEmpty()
            val expectedHash = approved.getProperty("$prefix.sha256").orEmpty()
            assertTrue("$prefix 官方资源名尚未核准", expectedName.isNotBlank())
            assertTrue("$prefix 官方来源尚未核准", approved.getProperty("$prefix.source").orEmpty().isNotBlank())
            assertTrue("$prefix 官方资源哈希尚未核准", expectedHash.matches(Regex("[0-9a-f]{64}")))
            val id = oauthIconResource(provider)
            assertEquals(expectedName, resources.getResourceEntryName(id))
            val parser = resources.getXml(id)
            parser.use {
                while (it.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) it.next()
                assertEquals("官方标识应是矢量 drawable", "vector", it.name)
            }
            val source = File("src/main/res/drawable/$expectedName.xml")
            assertTrue(source.isFile)
            val actualHash = MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals("资源被改成自绘替代物：$prefix", expectedHash, actualHash)
        }
    }

    @Test
    fun loginRowActuallyDrawsTheApprovedResourcesInsteadOfAChatGlyph() {
        val approved = approvedAssets()
        if (!requireApprovalState(approved)) {
            assertPendingRowRejectsGenericChat()
            return
        }
        var context: Context? = null
        var referenceId by mutableStateOf(0)
        var referenceBackdrop by mutableStateOf(Color.Black)
        var referenceWidth by mutableStateOf(72)
        var referenceHeight by mutableStateOf(72)
        rule.setContent {
            context = LocalContext.current
            CouliTheme {
                Column {
                    LoginRowForAssets()
                    if (referenceId != 0) {
                        Box(Modifier.background(referenceBackdrop)) {
                            Image(
                                painter = painterResource(referenceId),
                                contentDescription = null,
                                modifier =
                                    Modifier
                                        .layout { measurable, _ ->
                                            val child = measurable.measure(Constraints.fixed(referenceWidth, referenceHeight))
                                            layout(referenceWidth, referenceHeight) { child.place(0, 0) }
                                        }.testTag("official-reference"),
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val resources = requireNotNull(context).resources
        listOf("WECHAT", "HUAWEI").forEach { provider ->
            val name = approved.getProperty("$provider.resource").orEmpty()
            assertTrue("必须先人工核准官方素材", name.isNotBlank())
            val id = resources.getIdentifier(name, "drawable", requireNotNull(context).packageName)
            assertTrue("核准素材未打包", id != 0)
            val bounds = rule.onNodeWithTag("oauth-icon/$provider", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            rule.runOnIdle {
                referenceId = id
                referenceWidth = bounds.width.roundToInt()
                referenceHeight = bounds.height.roundToInt()
            }
            val actual = pixels("oauth-icon/$provider", provider)
            rule.runOnIdle { referenceBackdrop = Color.Black }
            val black = pixels("official-reference", "$provider-reference-black")
            rule.runOnIdle { referenceBackdrop = Color.Magenta }
            val magenta = pixels("official-reference", "$provider-reference-magenta")
            assertApprovedPixels(provider, actual, black, magenta)
        }
    }

    @androidx.compose.runtime.Composable
    private fun LoginRowForAssets() {
        ThirdPartyLoginRow(
            true,
            true,
            ThirdPartyLoginTexts("微信登录", "华为账号登录", "登录遇到问题", "其他登录方式", "上次登录"),
            {},
            {},
        )
    }

    private fun assertApprovedPixels(
        provider: String,
        actual: IntArray,
        black: IntArray,
        magenta: IntArray,
    ) {
        assertEquals(black.size, actual.size)
        assertEquals(black.size, magenta.size)
        // 双底色中不变的像素才是官方图的实色；透明背景允许由官方圆钮提供。
        val opaque = black.indices.filter { near(black[it], magenta[it]) }
        assertTrue("官方图不能是空画布", opaque.isNotEmpty())
        assertTrue("$provider 实际图形不等于官方资源", opaque.all { near(actual[it], black[it]) })
    }

    private fun approvedAssets(): Properties =
        Properties().apply {
            val stream = Ds02OAuthAssetTest::class.java.getResourceAsStream("/ds02-approved-oauth-assets.properties")
            requireNotNull(stream).use { load(it) }
        }

    @Test
    fun shoppingPlatformImagesStillLeaveLoginAssetsPending() {
        val directory = assets.newFolder("shopping")
        listOf("taobao.png", "jd.svg", "pinduoduo.webp", "tmall.xml", "alipay.pdf").forEach {
            File(directory, it).writeText("shopping-platform fixture")
        }
        File(directory, "wechat-readme.md").writeText("登录素材仍未交付")
        assertFalse(requireApprovalState(Properties(), directory))
    }

    @Test
    fun eitherLoginAssetOrPartialApprovalRequiresFullApproval() {
        val directory = assets.newFolder("login")
        listOf("wechat.svg", "WeChat-mark.PNG", "huawei-login.xml", "HUAWEI-official.ZIP").forEach { name ->
            val file = File(directory, name).apply { writeText("login fixture") }
            assertThrows("$name 到位不能静默挂起", AssertionError::class.java) {
                requireApprovalState(Properties(), directory)
            }
            file.delete()
        }
        val partial = Properties().apply { setProperty("WECHAT.resource", "wechat") }
        assertThrows(AssertionError::class.java) { requireApprovalState(partial, directory) }
        val complete =
            Properties().apply {
                listOf("WECHAT", "HUAWEI").forEach { provider ->
                    listOf("resource", "sha256", "source").forEach { setProperty("$provider.$it", "approved") }
                }
            }
        // 这里只验证分支选择；核准后的哈希、打包资源和渲染严格检查仍由原两项测试执行。
        assertTrue(requireApprovalState(complete, directory))
        assertThrows(AssertionError::class.java) { requireApprovalState(Properties(), File(directory, "missing")) }
    }

    private fun requireApprovalState(
        approved: Properties,
        directory: File = platformDirectory(),
    ): Boolean {
        val complete =
            listOf("WECHAT", "HUAWEI").all { provider ->
                listOf("resource", "sha256", "source").all { approved.getProperty("$provider.$it").orEmpty().isNotBlank() }
            }
        assertTrue("素材目录路径错误，不能当作尚未到位", directory.isDirectory)
        val supplied =
            directory.walkTopDown().any {
                it.isFile && (it.name.lowercase().startsWith("wechat") || it.name.lowercase().startsWith("huawei")) &&
                    it.extension.lowercase() in
                    setOf("svg", "png", "webp", "pdf", "xml", "jpg", "jpeg", "zip", "ai", "eps")
            }
        if (supplied || approved.values.any { it.toString().isNotBlank() }) {
            assertTrue("原件已交付或核准表只填了一部分：必须完成核准", complete)
        }
        return complete
    }

    private fun platformDirectory(): File {
        val root =
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .first { File(it, "contract.lock").isFile }
        return System.getenv("COULI_DIR")?.let { File(it, "design/icons/platform") }
            ?: generateSequence(root) { it.parentFile }.map { File(it, "couli/design/icons/platform") }.first { it.isDirectory }
    }

    /** 未到位允许留空并明确标注；若画标识，不能是通用聊天气泡。不是 Ignore/Assume。 */
    private fun assertPendingRowRejectsGenericChat() {
        var referenceIcon by mutableStateOf(CouliIcons.chat)
        rule.setContent {
            CouliTheme {
                Column {
                    LoginRowForAssets()
                    Image(
                        referenceIcon,
                        null,
                        Modifier.size(24.dp).testTag("chat-reference"),
                        colorFilter = ColorFilter.tint(Color.Black),
                    )
                }
            }
        }
        // 统一尺寸与着色后，拒绝当前组件库常见通用占位图。反射只读测试参照，无产品接线。
        val references =
            listOf("getChat", "getUser", "getImage", "getHelp").associateWith { name ->
                rule.runOnIdle {
                    referenceIcon =
                        CouliIcons.javaClass.getMethod(name).invoke(CouliIcons) as ImageVector
                }
                shape("chat-reference", name)
            }
        listOf("WECHAT", "HUAWEI").forEach { provider ->
            rule
                .onNodeWithTag("oauth-pending/$provider", useUnmergedTree = true)
                .assertIsDisplayed()
                .assertTextEquals("待官方素材")
            val icons =
                rule.onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag("oauth-icon/$provider"),
                    useUnmergedTree = true,
                )
            if (icons.fetchSemanticsNodes().isNotEmpty()) {
                val actual = shape("oauth-icon/$provider", provider)
                assertTrue("不能用空图冒充标识", actual.any { it })
                references.forEach { (name, reference) ->
                    val union = actual.indices.count { actual[it] || reference[it] }.coerceAtLeast(1)
                    val changed = actual.indices.count { actual[it] != reference[it] }
                    assertTrue("$provider 仍是通用 $name 占位（忽略尺寸与着色）", changed.toFloat() / union > 0.30f)
                }
            }
        }
    }

    private fun shape(
        tag: String,
        name: String,
    ): BooleanArray {
        pixels(tag, "pending-$name")
        val source = requireNotNull(ImageIO.read(File("build/ds02-official-assets/pending-$name.png")))
        val scaled = java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        scaled.createGraphics().apply {
            drawImage(source, 0, 0, 32, 32, null)
            dispose()
        }
        val background = scaled.getRGB(0, 0)
        return BooleanArray(32 * 32) { index ->
            val color = scaled.getRGB(index % 32, index / 32)
            if (background ushr 24 < 16) color ushr 24 > 64 else !near(color, background)
        }
    }

    private fun near(
        a: Int,
        b: Int,
    ): Boolean = listOf(0, 8, 16, 24).all { abs((a ushr it and 255) - (b ushr it and 255)) <= 2 }

    private fun pixels(
        tag: String,
        name: String,
    ): IntArray {
        val file = File("build/ds02-official-assets/$name.png").apply { parentFile?.mkdirs() }
        rule.onNodeWithTag(tag, useUnmergedTree = true).captureRoboImage(
            file.path,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record),
        )
        val image = requireNotNull(ImageIO.read(file))
        return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    }
}
