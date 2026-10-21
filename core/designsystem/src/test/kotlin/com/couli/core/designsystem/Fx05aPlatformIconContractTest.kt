package com.couli.core.designsystem

import androidx.compose.ui.unit.dp
import com.couli.generated.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** F-X-05a V1 / V4 / V5：编码入口不依赖显示文案，不把 pdd 当文件名。 */
class Fx05aPlatformIconContractTest {
    @Test
    fun firstBatchCodesResolveToOriginalPackagedFiles() {
        mapOf("taobao" to "taobao", "jd" to "jd", "pdd" to "pinduoduo").forEach { (code, file) ->
            assertEquals("platform/$file.png", platformIconAssetPath(code))
            assertEquals(code, Platform.fromRaw(code).raw)
        }
    }

    @Test
    fun homeGridCodesHaveTheSamePublicEntryPoint() {
        listOf("vip", "douyin", "meituan", "eleme", "fliggy", "didi").forEach { code ->
            assertEquals("APP-11v A7: $code", "platform/$code.png", platformIconAssetPath(code))
        }
    }

    @Test
    fun missingAndUnknownCodesDoNotInventAnIcon() {
        listOf("tmall", "wechat", "wechat_pay", "alipay", "wecom", "huawei", "apple", "kuaishou", "suning").forEach {
            assertNull(it, platformIconAssetPath(it))
        }
        listOf("", "future_shop", "__unknown__", "pinduoduo", "淘宝", "../taobao", "TAOBAO").forEach {
            assertNull(it, platformIconAssetPath(it))
        }
        assertEquals(Platform.UNKNOWN, Platform.fromRaw("tmall"))
    }

    @Test
    fun sizePresetsAreFourteenAndFortyFourDp() {
        assertEquals(14.dp, PlatformIconSize.BADGE.dp)
        assertEquals(44.dp, PlatformIconSize.HOME.dp)
    }
}
