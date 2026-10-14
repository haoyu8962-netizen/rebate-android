package com.couli.app.followup

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.DebugFakeLoginServer
import com.couli.app.DebugMockGate
import com.couli.app.RawFakeServerSwitch
import com.couli.app.buildDefaultEnvironment
import com.couli.app.trade.AppProductImages
import com.couli.app.trade.variantImageSource
import com.couli.app.update.variantUpdateChecker
import com.couli.core.config.AppEnvironment
import com.couli.core.config.EnvironmentSwitchCleanerRegistry
import com.couli.core.config.EnvironmentSwitcher
import com.couli.core.update.UpdateCheckOutcome
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-0714 第 1 轮评审 R1-05、R1-06（F-X-02）：
 * - R1-05：商品图加载器的位图缓存与在途解码结果绑定「环境 + 替身许可」：在 test 经假服务加载过假图的同一个加载器切到 prod 后不再交出缓存的假图；
 *   取图 / 解码期间切到 prod 的结果丢弃（不交、不缓存）；假服务的图片分支在交付前复核闸门（现画期间切到 prod 按断连结束）。
 * - R1-06：版本检查替身接统一闸门 [DebugMockGate.of]：staging 下假服务开关关闭走真实检查器、打开给假结果、等待期间关闭改走真实检查器。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FollowupAnd0714FixR1DebugTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val switcher = EnvironmentSwitcher(context, buildDefaultEnvironment(), EnvironmentSwitchCleanerRegistry())
    private val ready = DebugMockGate.environmentReady

    @Before
    fun setUp() {
        DebugMockGate.environmentReady = { true }
        setSwitch(true)
        switcher.switchTo(AppEnvironment.TEST)
    }

    @After
    fun tearDown() {
        setSwitch(false)
        switcher.switchTo(AppEnvironment.TEST)
        DebugMockGate.environmentReady = ready
    }

    @OptIn(RawFakeServerSwitch::class)
    private fun setSwitch(on: Boolean) {
        DebugMockGate.setSwitch(context, on)
    }

    private fun prod() = switcher.switchTo(AppEnvironment.PROD)

    private fun test() = switcher.switchTo(AppEnvironment.TEST)

    // ---------------------------------------------------------------- R1-05 商品图

    private val realNetwork = AtomicInteger()

    /** App 的 HTTP 客户端同一做法：假服务拦截器在前；真实网络用一个立即失败的拦截器代替（记次数）。 */
    private fun http(drawer: (String) -> ByteArray = { FAKE_BYTES }) =
        OkHttpClient
            .Builder()
            .addInterceptor(DebugFakeLoginServer(DebugMockGate.stateDir(context), null, DebugMockGate.of(context), drawer))
            .addInterceptor {
                realNetwork.incrementAndGet()
                throw IOException("real network not reachable in unit test")
            }.build()

    private val decodes = AtomicInteger()

    private fun loader(
        http: OkHttpClient = http(),
        duringDecode: () -> Unit = {},
    ) = AppProductImages(http, source = variantImageSource(context)) {
        decodes.incrementAndGet()
        duringDecode()
        ImageBitmap(1, 1)
    }

    @Test
    fun r1_05_same_loader_switched_to_prod_never_returns_cached_fake_image_and_switching_back_reloads() {
        val images = loader()
        val first = runBlocking { images.load(URL) }
        assertNotNull("test：假服务现画", first)
        assertSame("同一环境命中缓存", first, runBlocking { images.load(URL) })
        assertEquals(1, decodes.get())

        prod()
        assertNull("同一个加载器切到 prod：不交出缓存的假图", runBlocking { images.load(URL) })
        assertEquals("prod 走真实网络", 1, realNetwork.get())
        assertEquals("没有再解码", 1, decodes.get())

        test()
        assertNotNull("切回 test 恢复（缓存已按来源清掉，重新取图）", runBlocking { images.load(URL) })
        assertEquals(2, decodes.get())

        // 关掉假服务（环境不变）同样不交缓存
        setSwitch(false)
        assertNull(runBlocking { images.load(URL) })
    }

    @Test
    fun r1_05_switching_to_prod_while_decoding_drops_the_result_and_does_not_cache_it() {
        val images = loader(duringDecode = { if (decodes.get() == 1) prod() })
        assertNull("解码期间切到 prod：丢弃", runBlocking { images.load(URL) })
        test()
        assertNotNull("切回 test：没有缓存那张，重新取图解码", runBlocking { images.load(URL) })
        assertEquals(2, decodes.get())
    }

    @Test
    fun r1_05_switching_to_prod_while_the_fake_server_draws_the_image_delivers_no_fake_image() {
        val drawing = http { prod().let { FAKE_BYTES } }
        try {
            drawing.newCall(Request.Builder().url(URL).build()).execute().close()
            fail("现画期间切到 prod 不得交出假图")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("gate closed"))
        }
        assertEquals("也不改发到真实网络", 0, realNetwork.get())
        test()
        val images = loader(http { prod().let { FAKE_BYTES } })
        assertNull(runBlocking { images.load(URL) })
        assertEquals(0, decodes.get())
    }

    // ---------------------------------------------------------------- R1-06 版本检查

    @Test
    fun r1_06_staging_update_checker_follows_the_full_gate_switch_off_on_and_off_while_waiting() {
        switcher.switchTo(AppEnvironment.STAGING)
        val real = AtomicInteger()
        val checker =
            variantUpdateChecker(context) {
                real.incrementAndGet()
                UpdateCheckOutcome.Failure
            }
        runBlocking {
            setSwitch(false)
            assertEquals("staging、假服务关：真实检查器", UpdateCheckOutcome.Failure, checker.check())
            assertEquals(1, real.get())

            setSwitch(true)
            assertTrue("staging、假服务开：调试假结果", checker.check() is UpdateCheckOutcome.Success)
            assertEquals(1, real.get())

            val waiting = async { checker.check() }
            delay(FLIP_AFTER_MS)
            setSwitch(false)
            assertEquals("等待期间关掉假服务：改走真实检查器", UpdateCheckOutcome.Failure, waiting.await())
            assertEquals(2, real.get())
        }
    }

    private companion object {
        const val URL = "https://img.example.test/p/1.png"
        const val FLIP_AFTER_MS = 100L
        val FAKE_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
    }
}
