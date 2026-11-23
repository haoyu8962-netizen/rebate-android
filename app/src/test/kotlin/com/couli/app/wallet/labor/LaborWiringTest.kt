package com.couli.app.wallet.labor

import com.couli.app.wallet.visual.WithdrawVisualFixtures
import com.couli.core.network.ApiResult
import com.couli.feature.wallet.LaborBackend
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-LABOR-AGREEMENT-API 生产接线（照实名 D10-887 的先例）：debug 用进程内假服务，staging / release 用 RemoteLaborBackend（release 不再为 null）。
 * CI 只跑 testDebugUnitTest，Release / Staging 的接线靠选择函数 + 源码扫描钉住。选择函数在 HEAD 上不存在，按名反射取（取不到由断言判红）。
 */
class LaborWiringTest {
    private class Marker : LaborBackend {
        override suspend fun current(): ApiResult = ApiResult.LocalReject("call_cancelled")

        override suspend fun sign(
            version: String,
            textSha256: String,
        ): ApiResult = ApiResult.LocalReject("call_cancelled")
    }

    private val fake = Marker()
    private val real = Marker()

    @Test fun debugUsesFakeBackend() = assertSame(fake, LaborRemoteReflect.choose("debug", fake, real))

    @Test fun debugWithoutFakeFallsBackToReal() = assertSame(real, LaborRemoteReflect.choose("debug", null, real))

    @Test fun stagingUsesRealBackendEvenWithFakeAvailable() = assertSame(real, LaborRemoteReflect.choose("staging", fake, real))

    @Test fun releaseUsesRealBackendInsteadOfNull() = assertSame(real, LaborRemoteReflect.choose("release", null, real))

    @Test fun releaseIgnoresAnyFake() = assertSame(real, LaborRemoteReflect.choose("release", fake, real))

    @Test fun appModuleWiresChooserWithRealBackendAndBuildKind() {
        val source = squash(read("app/src/main/kotlin/com/couli/app/AppModule.kt"))
        assertTrue("AppModule 经选择函数接劳务后端", source.contains("labor=chooseLaborBackend(BuildConfig.BUILD_KIND,"))
        assertTrue("AppModule 给真实实现", source.contains("RemoteLaborBackend("))
        assertFalse("不再把 variantLaborBackend 直接当生产后端", source.contains("labor=variantLaborBackend(context),"))
    }

    @Test fun remoteBackendLivesInItsOwnFileAndCallsBothContractOperations() {
        val remote = File(WithdrawVisualFixtures.root, REMOTE_FILE)
        assertTrue("真实后端落在 RemoteLaborBackend.kt（单类文件按类名命名，ktlint filename / detekt MatchingDeclarationName；文件名避开 key / api 字样）", remote.isFile)
        val source = remote.readText()
        assertTrue("调 getLaborAgreement", source.contains("\"getLaborAgreement\""))
        assertTrue("调 signLaborAgreement", source.contains("\"signLaborAgreement\""))
        assertFalse("不打印", Regex("""\b(Log\.[vdiwe]|println|print\(|Timber|printStackTrace)""").containsMatchIn(source))
    }

    @Test fun wiringLivesOutsidePinnedRealNameFiles() {
        assertTrue("选择函数落在 LaborWiring.kt（不改实名资金基线里的 RealNameWiring.kt）", File(WithdrawVisualFixtures.root, WIRING_FILE).isFile)
    }

    @Test fun releaseVariantHasNoFakeLabor() {
        val source = read("app/src/release/kotlin/com/couli/app/VariantWallet.kt")
        assertFalse("Release 制品里不存在假劳务服务", source.contains("Fake") || source.contains("fakeWallet"))
    }

    @Test fun debugFeaturesKeepFakeLaborBackend() {
        val source = read("app/src/debugFeatures/kotlin/com/couli/app/VariantWallet.kt")
        assertTrue("Debug 假服务照旧", source.contains("fakeWallet(context).laborBackend"))
    }

    @Test fun laborPortDocStatesTheRealImplementation() {
        val ports = read("feature/wallet/src/main/kotlin/com/couli/feature/wallet/WalletPorts.kt")
        val doc = ports.substringBefore("interface LaborBackend").substringAfterLast("/**")
        assertTrue("LaborBackend 的 KDoc 写明生产实现 RemoteLaborBackend", doc.contains("RemoteLaborBackend"))
        assertFalse("KDoc 不再说只由 Debug 假服务实现", doc.contains("只由 Debug 假服务实现"))
    }

    private fun read(path: String): String = File(WithdrawVisualFixtures.root, path).readText()

    private fun squash(text: String): String = text.replace(Regex("\\s+"), "")

    private companion object {
        const val REMOTE_FILE = "feature/wallet/src/main/kotlin/com/couli/feature/wallet/RemoteLaborBackend.kt"
        const val WIRING_FILE = "app/src/main/kotlin/com/couli/app/wallet/LaborWiring.kt"
    }
}
