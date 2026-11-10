package com.couli.app.me

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.me.MeTags
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 空值 / 非 https：组件在调用图片源之前就按失败处理（假源不过滤协议，所以不能靠 App 的 AppProductImages 兜住），显示内置人形。
 * 现行产品码上这一组本来就绿（恒显示兜底、从不加载）：它们是「非 https 也加载」变异的杀点，不是先红用例。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1032dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeAvatarInvalidUrlTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { MeAvatarTestHost(rule) }

    @After fun close() = host.close()

    @Test fun emptyStringUsesPersonWithoutCallingLoader() = rejected("")

    @Test fun httpNeverReachesLoader() = rejected("http://avatar.invalid/account-a.png")

    @Test fun fileNeverReachesLoader() = rejected("file:///avatar.png")

    @Test fun contentNeverReachesLoader() = rejected("content://avatar/account-a")

    @Test fun protocolRelativeUrlNeverReachesLoader() = rejected("//avatar.invalid/account-a.png")

    @Test fun dataUriNeverReachesLoader() = rejected("data:image/png;base64,iVBORw0KGgo=")

    /** 契约 Me.avatar_url 是必填非空类型 string：JSON null 仍按现行 MeJson 整条坏数据（失败卡 + 重试），解码零改动，也不去加载。 */
    @Test fun jsonNullStaysContractBadDataAndNeverReachesLoader() {
        host.launch(null)
        rule.meTag(MeTags.ME_ERROR).assertExists()
        rule.meTag(MeTags.ME_NICKNAME).assertDoesNotExist()
        assertTrue("坏数据不得触发头像加载", host.images.requests.isEmpty())
    }

    private fun rejected(url: String) {
        host.launch(url)
        assertTrue("空值和非 https 必须在调用图片源之前被拒绝：${host.images.requests}", host.images.requests.isEmpty())
        rule.assertAvatarFallback(host)
        rule.assertAvatarDecorative()
    }
}
