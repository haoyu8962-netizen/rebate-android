package com.couli.feature.web

import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import com.couli.core.bridge.ClearingTrustedWebStore
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AndroidTrustedWebStore 的持久状态（D10-309 方案 ①⑤）：新配置文件名先持久化（变异 6）、旧配置文件持久记入 retired[] 且永不再挂
 * （变异 7 的设备侧；同进程删不掉，真删除由 JVM 端口替身证明，v4 口径见 [i7RetiredProfileIsPersistedAndNeverMountedAgain]）、
 * 存储路径在首次创建时判定并写盘、之后不中途换（变异 21）、首次启用 P 时一次性清默认配置文件里的旧可信数据（D10-310 (3)）。
 */
@RunWith(AndroidJUnit4::class)
class Fx25WebProfileTest {
    @Test
    fun i3RotatedProfileNameIsPersistedBeforeUseAndSurvivesRestart() {
        Fx25WebFixture().use { f ->
            val page = f.open()
            val before = f.onMain { f.store.current() }
            assertTrue(before.startsWith(AndroidTrustedWebStore.PROFILE_PREFIX))
            f.switch()
            assertTrue(f.settle())
            f.awaitDocument(page)
            val after = f.onMain { f.store.current() }
            assertNotEquals(before, after)
            assertTrue(after.startsWith(AndroidTrustedWebStore.PROFILE_PREFIX))
            f.restart()
            assertEquals("重启后仍用切号后的新配置文件，不回到旧的", after, f.onMain { f.store.current() })
        }
    }

    /**
     * v4（D10-310 (5)，编排 2026-10-27 23:04 照准）：系统 WebView 把本进程挂过的配置文件一直视为「在用」，同进程内 deleteProfile 删不掉，
     * 「最终删除」只能在下一次进程冷启动发生，设备单进程测不到（JVM R.i7… 用端口替身证明删除调用、in-use 保留、删成功移出）。
     * 设备上证明：事务不被删除阻塞；旧名已持久记入 retired[]（删掉了则不必在列）；删不掉时计埋点；旧配置文件不再挂到任何新 WebView，
     * 重启后也不回到旧配置文件；新文档读不到旧配置文件里的数据。
     */
    @Test
    fun i7RetiredProfileIsPersistedAndNeverMountedAgain() {
        Fx25WebFixture().use { f ->
            val page = f.open()
            f.seed(checkNotNull(f.onMain { page.web }))
            WebWait.until("夹具建存储时的删除尝试已结算") { f.onMain { f.store.discards.isNotEmpty() } }
            val discardsBefore = f.onMain { f.store.discards.size }
            val deferredBefore = f.deferredDiscards()
            val before = f.onMain { f.store.current() }
            f.switch()
            assertTrue("删除旧配置文件不阻塞事务", f.settle())
            f.awaitDocument(page)
            val after = f.onMain { f.store.current() }
            assertNotEquals(before, after)
            WebWait.until("事务后尝试过删除旧配置文件") { f.onMain { f.store.discards.size > discardsBefore } }
            val stillThere = f.onMain { before in ProfileStore.getInstance().allProfileNames }
            if (stillThere) {
                assertTrue("删不掉：旧名留在持久 retired[] 待冷启动删", before in f.retiredOnDisk())
                assertFalse(f.onMain { f.store.discards.last() })
                assertTrue("删不掉计埋点", f.deferredDiscards() > deferredBefore)
            }
            assertEquals(
                after,
                f
                    .persistedStoreState()
                    .getValue("active")
                    .jsonPrimitive.content,
            )
            assertTrue(f.onMain { after in ProfileStore.getInstance().allProfileNames })
            assertEquals("新 WebView 挂在新配置文件上", after, f.onMain { checkNotNull(page.web).profileName })
            assertEquals(Fx25WebFixture.EMPTY, f.readStorage(checkNotNull(f.onMain { page.web })))
            f.restart()
            assertEquals("重启后不回到旧配置文件", after, f.onMain { f.store.current() })
            if (stillThere) assertTrue("重启后 retired[] 仍记着旧名", before in f.retiredOnDisk())
            val cold = f.open("cold", expectLoaded = false)
            f.onMain { f.sync.observe(f.identity) }
            assertTrue(f.settle())
            f.awaitDocument(cold)
            assertEquals(after, f.onMain { checkNotNull(cold.web).profileName })
            assertTrue("旧配置文件永不再挂到任何新建的可信 WebView", f.onMain { (page.webs.drop(1) + cold.webs).none { it.profileName == before } })
            assertEquals(Fx25WebFixture.EMPTY, f.readStorage(checkNotNull(f.onMain { cold.web })))
        }
    }

    /**
     * v4 补：产品存储冷启动删残留在真实 ProfileStore 上的「删成功即移出、不留名单」。前提（环境探测，不成立则跳过并写明原因）：本进程从未
     * 挂到任何 WebView 的配置文件可以直接删除。造一份这样的孤儿 couli_trusted_* 后「重启」：新协调器的 discardPending 删掉它，
     * 且持久 retired[] 里没有它。挂过 WebView 的旧配置文件同进程删不掉，那条路径见 [i7RetiredProfileIsPersistedAndNeverMountedAgain]。
     */
    @Test
    fun i7UnmountedResidueIsDeletedOnColdStartAndLeavesNoName() {
        Fx25WebFixture().use { f ->
            val deletable =
                f.onMain {
                    val probe = "fx25_probe_" + UUID.randomUUID().toString().replace("-", "")
                    val store = ProfileStore.getInstance()
                    store.getOrCreateProfile(probe)
                    runCatching { store.deleteProfile(probe) }.getOrDefault(false) && probe !in store.allProfileNames
                }
            assumeTrue("环境前提：本进程未挂过 WebView 的配置文件可删（此系统 WebView 不满足则跳过）", deletable)
            // 夹具建存储时那次删除尝试先结算，删除才确定归下面的「重启」
            WebWait.until("夹具建存储时的删除尝试已结算") { f.onMain { f.store.discards.isNotEmpty() } }
            val orphan =
                AndroidTrustedWebStore.PROFILE_PREFIX +
                    UUID
                        .randomUUID()
                        .toString()
                        .replace("-", "")
                        .take(16)
            f.onMain { ProfileStore.getInstance().getOrCreateProfile(orphan) }
            assertTrue(f.onMain { orphan in ProfileStore.getInstance().allProfileNames })
            f.restart()
            WebWait.until("冷启动的删除尝试已结算") { f.onMain { f.store.discards.isNotEmpty() } }
            assertFalse("冷启动删掉了未挂载的残留", f.onMain { orphan in ProfileStore.getInstance().allProfileNames })
            assertFalse("删成功的不留在 retired[]", orphan in f.retiredOnDisk())
            assertTrue("活动配置文件不受影响", f.onMain { f.store.current() != orphan })
        }
    }

    /** 能力探测只在首次创建时判一次并写盘：之后即使探测结果变了也不换路径。 */
    @Test
    fun i2StorePathIsPinnedAtFirstCreation() {
        Fx25WebFixture().use { f ->
            val name = f.onMain { f.store.current() }
            var asked = 0
            f.restart(profilesSupported = {
                asked += 1
                false
            })
            val reopened = f.onMain { f.store.delegate as AndroidTrustedWebStore }
            assertTrue("已在 P 路径的安装不中途改走 F", f.onMain { reopened.profiles })
            assertEquals(name, f.onMain { reopened.current() })
            assertEquals("路径已写盘，不再探测", 0, asked)
        }
        Fx25WebFixture(Fx25WebMode.CLEARING).use { f ->
            assertEquals(ClearingTrustedWebStore.DEFAULT_STORE, f.onMain { f.store.current() })
            f.restart(profilesSupported = { true })
            val reopened = f.onMain { f.store.delegate as AndroidTrustedWebStore }
            assertFalse("已在 F 路径的安装不中途改走 P", f.onMain { reopened.profiles })
            assertEquals(ClearingTrustedWebStore.DEFAULT_STORE, f.onMain { reopened.current() })
        }
    }

    /**
     * D10-310 (3)：首次启用 P 时把默认配置文件里的旧可信数据（Cookie / localStorage / IndexedDB）按 F 清一次，只一次；外部配置文件不动。
     * v4：默认配置文件页开 DOM 存储（与可信 / 外部容器一致，否则 localStorage 为 null，做种本身就失败）；迁移的对象是持久数据，
     * sessionStorage 不跨 WebView 实例，不作为跨 WebView 的断言对象（每次读都是新 WebView）。迁移结算（discards 有结果）之后才读，
     * 结算结果本身不断言为 true：同进程里别的用例留下的 couli_trusted_* 仍在用、删不掉，会让 discardPending 如实报 false。
     */
    @Test
    fun i3FirstProfileEnableClearsLegacyDefaultProfileDataExactlyOnce() {
        Fx25WebFixture().use { f ->
            // 夹具自身首次建存储也会做一次迁移：等它结算，避免与下面的做种交错（交错会让「是新存储清掉的」不成立）
            WebWait.until("夹具建存储时的迁移已结算") { f.onMain { f.store.discards.isNotEmpty() } }
            val external = f.openExternal()
            f.seed(external)
            seedDefault(f)
            assertEquals("正对照：做种后默认配置文件里有旧数据", persistent(Fx25WebFixture.STORED), persistent(readDefault(f)))
            val fresh = "fx25_legacy_" + UUID.randomUUID().toString().replace("-", "")
            f.restart(storeKey = fresh)
            WebWait.until("首次启用 P：默认配置文件的旧数据清理已结算") { f.onMain { f.store.discards.isNotEmpty() } }
            assertEquals("默认配置文件里本用例的 Cookie 已清", "", defaultCookies(f))
            assertEquals("默认配置文件的持久数据已清", persistent(Fx25WebFixture.EMPTY), persistent(readDefault(f)))
            assertEquals("外部配置文件不动", Fx25WebFixture.STORED, f.readStorage(external))
            seedDefault(f)
            f.restart(storeKey = fresh)
            WebWait.until("再次创建：删除尝试已结算") { f.onMain { f.store.discards.isNotEmpty() } }
            assertEquals("只清一次：已记 legacyCleared", persistent(Fx25WebFixture.STORED), persistent(readDefault(f)))
            assertTrue(
                "迁移标记已持久",
                f
                    .persistedStoreState()
                    .getValue("legacyCleared")
                    .jsonPrimitive.boolean,
            )
        }
    }

    /** 跨 WebView 实例可比的持久部分（去掉 sessionStorage）。 */
    private fun persistent(storage: JsonObject): JsonObject = JsonObject(storage - "session")

    private fun defaultWebView(f: Fx25WebFixture): WebView =
        f
            .onMain {
                WebView(InstrumentationRegistry.getInstrumentation().targetContext).also {
                    it.settings.javaScriptEnabled = true
                    it.settings.domStorageEnabled = true
                    it.layoutParams = FrameLayout.LayoutParams(1, 1)
                    it.loadUrl("${f.origin}/legacy-default")
                }
            }.also { web ->
                f.probe(web).await("默认配置文件页加载完成", "document.readyState === 'complete' && !!window.fx25Document") { it == "true" }
            }

    private fun seedDefault(f: Fx25WebFixture) {
        val web = defaultWebView(f)
        try {
            f.seed(web)
        } finally {
            f.onMain { web.destroy() }
        }
    }

    private fun readDefault(f: Fx25WebFixture) =
        defaultWebView(f).let { web ->
            try {
                f.readStorage(web)
            } finally {
                f.onMain { web.destroy() }
            }
        }

    private fun defaultCookies(f: Fx25WebFixture): String =
        f.onMain {
            CookieManager.getInstance().flush()
            CookieManager
                .getInstance()
                .getCookie(f.origin)
                .orEmpty()
                .split("; ")
                .filter { it.startsWith(f.key) }
                .joinToString("; ")
        }
}
