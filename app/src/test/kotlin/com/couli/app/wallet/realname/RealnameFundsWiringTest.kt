package com.couli.app.wallet.realname

import com.couli.app.wallet.chooseRealNameBackend
import com.couli.app.wallet.visual.WithdrawVisualFixtures
import com.couli.core.network.ApiResult
import com.couli.feature.wallet.RealNameBackend
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生产接线（D10-887，brief §3）：staging / release 用真实实名后端，debug 用假服务。CI 只跑 testDebugUnitTest，Release / Staging 的接线靠选择函数 + 源码扫描钉住。
 */
class RealnameFundsWiringTest {
    private class Marker : RealNameBackend {
        override suspend fun submit(
            name: String,
            idNo: String,
            idempotencyKey: String,
        ): ApiResult = ApiResult.LocalReject("call_cancelled")
    }

    private val fake = Marker()
    private val real = Marker()

    @Test fun debugUsesFakeBackend() = assertSame(fake, chooseRealNameBackend("debug", fake, real))

    @Test fun debugWithoutFakeFallsBackToReal() = assertSame(real, chooseRealNameBackend("debug", null, real))

    @Test fun stagingUsesRealBackendEvenWithFakeAvailable() = assertSame(real, chooseRealNameBackend("staging", fake, real))

    @Test fun releaseUsesRealBackend() = assertSame(real, chooseRealNameBackend("release", null, real))

    @Test fun releaseIgnoresAnyFake() = assertSame(real, chooseRealNameBackend("release", fake, real))

    @Test fun appModuleWiresChooserWithRealBackendAndBuildKind() {
        val source = squash(File(WithdrawVisualFixtures.root, "app/src/main/kotlin/com/couli/app/AppModule.kt").readText())
        assertTrue("AppModule 经选择函数接实名后端", source.contains("realname=chooseRealNameBackend(BuildConfig.BUILD_KIND,"))
        assertTrue("AppModule 给真实实现", source.contains("RemoteRealNameBackend("))
        assertFalse("不再把 variantRealNameBackend 直接当生产后端", source.contains("realname=variantRealNameBackend(context),"))
    }

    @Test fun releaseVariantHasNoFakeRealname() {
        val source = File(WithdrawVisualFixtures.root, "app/src/release/kotlin/com/couli/app/VariantWallet.kt").readText()
        assertFalse("Release 制品里不存在假实名服务", source.contains("Fake") || source.contains("fakeWallet"))
    }

    @Test fun debugFeaturesKeepFakeBackend() {
        val source = File(WithdrawVisualFixtures.root, "app/src/debugFeatures/kotlin/com/couli/app/VariantWallet.kt").readText()
        assertTrue("Debug 假服务照旧", source.contains("realnameBackend"))
    }

    @Test fun laborBackendStaysUnwiredInRelease() {
        val source = File(WithdrawVisualFixtures.root, "app/src/release/kotlin/com/couli/app/VariantWallet.kt").readText()
        assertTrue("本单不改劳务后端（Release 仍未就绪）", squash(source).contains("variantLaborBackend(context:Context):LaborBackend?=null"))
    }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), "")
}
