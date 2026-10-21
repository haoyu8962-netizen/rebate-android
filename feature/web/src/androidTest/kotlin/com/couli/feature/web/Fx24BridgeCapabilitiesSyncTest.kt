package com.couli.feature.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.web.Fx24BridgeSyncFixture.Companion.supports
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 三端口径 A：执行真实启动脚本；has 真值采用 H5 SDK nativeSupports 的同一数组判断式，不复制 SDK。 */
@RunWith(AndroidJUnit4::class)
class Fx24BridgeCapabilitiesSyncTest {
    private lateinit var fixture: Fx24BridgeSyncFixture
    private val probe: PageProbe get() = fixture.probe

    @Before
    fun start() {
        fixture = Fx24BridgeSyncFixture()
    }

    @After
    fun close() {
        if (::fixture.isInitialized) fixture.close()
    }

    @Test
    fun currentTrustedDocumentLosesAndRegainsCapabilityWithoutNavigation() {
        fixture.open()
        val document = probe.read("window.fx24Document")
        assertEquals("true", probe.read(supports("clipboard.read")))

        fixture.publish(setOf("clipboard.read"))
        fixture.awaitSupports("clipboard.read", false)
        assertEquals(document, probe.read("window.fx24Document"))
        assertEquals("true", probe.read(supports("app.getEnv")))
        assertEquals("true", probe.read("Object.isFrozen(window.__REBATE_BRIDGE__.methods)"))

        fixture.publish(emptySet())
        fixture.awaitSupports("clipboard.read", true)
        assertEquals(document, probe.read("window.fx24Document"))
        assertEquals("true", probe.read("Object.isFrozen(window.__REBATE_BRIDGE__.methods)"))
    }

    @Test
    fun refreshSeesUpdatedBootstrapBeforePageFinishedCanRepairTheList() {
        fixture.open()
        assertEquals("true", probe.read("window.fx24InitialMethods.includes('clipboard.read')"))
        fixture.publish(setOf("clipboard.read"))
        fixture.reload()

        assertEquals("false", probe.read("window.fx24InitialMethods.includes('clipboard.read')"))
        assertEquals("true", probe.read("window.fx24InitialMethods.includes('app.getEnv')"))
        assertEquals("false", probe.read(supports("clipboard.read")))
    }

    @Test
    fun newDocumentBootstrapRestoresMethodsRemovedFromDisabledList() {
        fixture.open(setOf("clipboard.read"))
        assertEquals("false", probe.read("window.fx24InitialMethods.includes('clipboard.read')"))
        fixture.publish(emptySet())
        fixture.load("fx24-next")

        assertEquals("true", probe.read("window.fx24InitialMethods.includes('clipboard.read')"))
        assertEquals("true", probe.read(supports("clipboard.read")))
    }

    @Test
    fun missingAndWrongTokensCannotReplaceTheMethodsArray() {
        fixture.open(setOf("clipboard.read"))
        probe.read("window.fx24SavedMethods = window.__REBATE_BRIDGE__.methods; true")
        for (arguments in listOf("", "undefined, ['clipboard.read']", "null, ['clipboard.read']", "'wrong-token', ['clipboard.read']")) {
            assertEquals("页面伪造更新必须被拒绝：$arguments", "false", probe.read("window.__couliBridgeMethods($arguments)"))
            assertEquals("true", probe.read("window.fx24SavedMethods === window.__REBATE_BRIDGE__.methods"))
            assertEquals("false", probe.read(supports("clipboard.read")))
            assertEquals("true", probe.read(supports("app.getEnv")))
        }
        // 真令牌的原生更新仍能生效，防止「更新函数始终拒绝」造成假通过。
        fixture.publish(emptySet())
        fixture.awaitSupports("clipboard.read", true)
    }

    @Test
    fun methodsAndUpdaterStayReadOnlyAndFrozenAfterNativeUpdates() {
        fixture.open()
        val result =
            probe.read(
                """
                (function () {
                  'use strict';
                  var bridge = window.__REBATE_BRIDGE__, methods = bridge.methods, update = window.__couliBridgeMethods;
                  function rejected(action) { try { action(); return false; } catch (e) { return e instanceof TypeError; } }
                  return {
                    assignMethods: rejected(function () { bridge.methods = []; }),
                    editItem: rejected(function () { methods[0] = 'fake.method'; }),
                    appendItem: rejected(function () { methods.push('fake.method'); }),
                    redefineMethods: rejected(function () { Object.defineProperty(bridge, 'methods', {value: []}); }),
                    assignUpdater: rejected(function () { window.__couliBridgeMethods = function () {}; }),
                    redefineUpdater: rejected(function () {
                      Object.defineProperty(window, '__couliBridgeMethods', {value: function () {}});
                    }),
                    deleteUpdater: rejected(function () { delete window.__couliBridgeMethods; }),
                    sameMethods: bridge.methods === methods,
                    sameUpdater: window.__couliBridgeMethods === update,
                    frozenBridge: Object.isFrozen(bridge), frozenMethods: Object.isFrozen(methods)
                  };
                })()
                """.trimIndent(),
            )
        val checks = Json.parseToJsonElement(result).jsonObject
        assertEquals("所有篡改与冻结检查都必须返回", 11, checks.size)
        checks.forEach { (name, value) -> assertEquals(name, "true", value.jsonPrimitive.content) }

        fixture.publish(setOf("clipboard.read"))
        fixture.awaitSupports("clipboard.read", false)
        assertEquals("true", probe.read("Object.isFrozen(window.__REBATE_BRIDGE__.methods)"))
        assertEquals("true", probe.read(supports("app.getEnv")))
    }

    @Test
    fun untrustedMainFrameReceivesNeitherCompletionNorConfigPushes() {
        fixture.open()
        val trustedPushes = fixture.pushes
        assertTrue("可信页加载完成应实际补推过", trustedPushes > 0)
        fixture.loadUntrusted()
        assertEquals("true", probe.read("location.origin === '${Fx24BridgeSyncFixture.UNTRUSTED}'"))
        assertEquals("undefined", Json.parseToJsonElement(probe.read("typeof window.__REBATE_BRIDGE__")).jsonPrimitive.content)
        assertEquals(trustedPushes, fixture.pushes)

        fixture.publish(setOf("clipboard.read"))
        assertEquals(trustedPushes, fixture.pushes)
        assertEquals("0", probe.read("window.fx24UntrustedCalls"))
        // 回到可信源，新文档首段脚本必须已拿到禁用后的列表，证明上一条配置确实被消费。
        fixture.load("fx24-return")
        assertEquals("false", probe.read("window.fx24InitialMethods.includes('clipboard.read')"))
    }

    @Test
    fun revokedCurrentOriginReceivesNoPushWhenDisabledMethodsChange() {
        fixture.open(observeUpdates = true)
        val document = probe.read("window.fx24Document")
        val initialCalls = probe.read("window.fx24UpdateCalls").toInt()
        assertTrue("观察器必须看到真实加载完成补推调用更新函数", initialCalls > 0)
        assertEquals("true", probe.read(supports("clipboard.read")))

        // 正对照：同一文档仍可信时，配置变化确实进入真实更新函数并改变列表。
        fixture.publish(setOf("clipboard.read"))
        fixture.awaitSupports("clipboard.read", false)
        val callsBeforeRevocation = probe.read("window.fx24UpdateCalls").toInt()
        assertEquals(initialCalls + 1, callsBeforeRevocation)
        val pushesBeforeRevocation = fixture.pushes
        val methodsBeforeRevocation = probe.read("window.__REBATE_BRIDGE__.methods")
        probe.read("window.fx24SavedMethods = window.__REBATE_BRIDGE__.methods; true")

        // 同一次配置撤销 bridge_origins 中的当前来源并解除禁用；不导航，安装时的来源规则仍含它。
        fixture.publish(emptySet(), nextTrustedOrigins = emptySet())

        assertEquals("撤销后原生不得发起推送", 0, fixture.pushes - pushesBeforeRevocation)
        assertEquals("撤销后更新函数零调用", 0, probe.read("window.fx24UpdateCalls").toInt() - callsBeforeRevocation)
        assertEquals("撤销后列表内容不变", methodsBeforeRevocation, probe.read("window.__REBATE_BRIDGE__.methods"))
        assertEquals("撤销后数组对象不变", "true", probe.read("window.fx24SavedMethods === window.__REBATE_BRIDGE__.methods"))
        assertEquals("false", probe.read(supports("clipboard.read")))
        assertEquals(document, probe.read("window.fx24Document"))
        assertEquals("true", probe.read("location.origin === '${Fx24BridgeSyncFixture.ORIGIN}'"))
    }

    @Test
    fun unchangedEffectiveMethodsDoNotRepeatPushes() {
        fixture.open()
        val initial = fixture.pushes
        assertTrue("先确认观察器看得到真实加载完成补推", initial > 0)
        fixture.publish(setOf("unknown.method"))
        assertEquals(initial, fixture.pushes)
        fixture.publish(emptySet())
        assertEquals(initial, fixture.pushes)

        fixture.publish(setOf("clipboard.read", "app.getEnv"))
        fixture.awaitSupports("clipboard.read", false)
        assertEquals(initial + 1, fixture.pushes)
        fixture.publish(setOf("app.getEnv", "clipboard.read", "unknown.method"))
        assertEquals("禁用名单顺序和未知项变化不改变声明列表", initial + 1, fixture.pushes)
        fixture.publish(emptySet())
        fixture.awaitSupports("clipboard.read", true)
        assertEquals(initial + 2, fixture.pushes)
    }

    @Test
    fun pageFinishedRepushesCurrentListEvenWithoutAnotherConfigEmission() {
        fixture.open()
        fixture.publish(setOf("clipboard.read"))
        fixture.awaitSupports("clipboard.read", false)
        val beforeReload = fixture.pushes
        fixture.reload()

        assertTrue("真实控制器 onPageFinished 必须再次调用传输层补推", fixture.pushes > beforeReload)
        assertEquals("false", probe.read(supports("clipboard.read")))
        assertEquals("true", probe.read(supports("app.getEnv")))
    }
}
