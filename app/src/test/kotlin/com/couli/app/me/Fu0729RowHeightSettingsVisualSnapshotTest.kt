package com.couli.app.me

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-0729 ① 截图入口（brief §6）：设置主页行高 52 / 节距 53 的整页图，放 src/test/snapshots/settings-v/，名字带 `fu0729-…-row52`。
 * 类名以 SettingsVisualSnapshotTest 结尾：随 verify.sh 既有的 `--tests "*SettingsVisualSnapshotTest"` 一步比对，不改 CI 脚本。
 * 每个入口截图前先核行高 52（量外框），所以底版（48）在单测步就红；既有 Settings-v2 等基准由实现方按 §6 重录，本类不替代它们。
 * 只声明入口；测试作者不 record / verify、不生成基准。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1039dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Fu0729RowHeightSettingsVisualSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { SettingsVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    @Test fun memberLight() = page("fu0729-Settings-v2-row52-light", Fu0729Rows.member)

    @Test fun memberDark() = page("fu0729-SettingsDark-row52-dark", Fu0729Rows.member, dark = true)

    @Test
    @Config(qualifiers = "w390dp-h780dp-xhdpi")
    fun guestLight() = page("fu0729-SettingsGuest-v2-row52-light", Fu0729Rows.guest, session = "guest_consented")

    @Test
    @Config(qualifiers = "w390dp-h800dp-xhdpi")
    fun basicLight() = page("fu0729-SettingsBasic-row52-light", Fu0729Rows.basic, session = "basic")

    private fun page(
        name: String,
        rows: List<String>,
        session: String = "settings_member",
        dark: Boolean = false,
    ) {
        host.launch(session = session, dark = dark)
        Fu0729Rows.assertRows52(host, rows, name)
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(FRAME_SETTLE_MS)
        rule.onRoot().captureRoboImage("$DIR/$name.png")
    }

    private companion object {
        const val DIR = "src/test/snapshots/settings-v"
        const val FRAME_SETTLE_MS = 300L
    }
}
