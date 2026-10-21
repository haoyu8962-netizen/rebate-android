package com.couli.feature.login

import com.couli.core.privacy.CredentialCleanerRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastLoginMethod17aTest {
    @Test
    fun allowListRejectsPersonalDataAndAppleAndRevisionsProtectClearsAndNewerWrites() {
        val backend = MethodBackend17a()
        val store = LastLoginMethodStore(backend)
        listOf("sms", "wechat", "huawei", "carrier_one_tap").forEach {
            assertTrue(store.record(it))
            assertEquals(it, store.read())
        }
        listOf("apple", "huawei_one_tap", "13800138000", "sms:13800138000", "", "unknown").forEach {
            val revision = store.revision
            assertFalse(store.record(it))
            assertEquals(revision, store.revision)
            assertEquals("carrier_one_tap", store.read())
        }
        val old = store.revision
        store.clear()
        assertFalse(store.record("sms", old))
        assertNull(store.read())
        val beforeNewer = store.revision
        store.record("wechat")
        assertFalse(store.record("sms", beforeNewer))
        assertEquals("wechat", store.read())
        assertEquals(listOf("sms", "wechat", "huawei", "carrier_one_tap", null, "wechat"), backend.writes)
    }

    @Test
    fun invalidPersistedValueCannotBecomeALoginLabel() {
        listOf("apple", "13800138000", "sms:13800138000", "unknown").forEach { raw ->
            val backend = MethodBackend17a().also { it.value = raw }
            assertNull(LastLoginMethodStore(backend).read())
        }
    }

    @Test
    fun failedClearIsReportedAndInvalidatesOldWritersEvenBeforeRetry() {
        val backend = MethodBackend17a()
        val store = LastLoginMethodStore(backend)
        store.record("wechat")
        val revision = store.revision
        backend.writable = false
        assertTrue("删除失败必须向清理入口报错", runCatching { store.clear() }.isFailure)
        backend.writable = true
        assertFalse(store.record("sms", revision))
        store.clear()
        assertNull(store.read())
    }

    @Test
    fun smsWritesOnlyAfterSessionIsSavedAndIncludesRestrictedLogins() {
        listOf(false, true).forEach { restricted ->
            Login17aFixture(restricted).use { f ->
                f.methods.record("wechat")
                f.enterCode()
                f.input("123456", true)
                assertEquals("wechat", f.methods.read())
                f.respond(20002)
                assertEquals("wechat", f.methods.read())
                f.input("654321", true)
                f.tokens.afterSave = { assertEquals("保存同步区内不能提前写方式", "wechat", f.methods.read()) }
                f.respond(0, sessionScope = if (restricted) "deletion_only" else "full")
                assertEquals(listOf(LoginOutcome.LOGGED_IN), f.completed)
                assertEquals("sms", f.methods.read())
                assertEquals(listOf("wechat", "sms"), f.backend.writes)
            }
        }
    }

    @Test
    fun saveFailureAndAttemptInvalidatedJustAfterSaveDoNotRecordMethod() {
        listOf(false, true).forEach { invalidate ->
            Login17aFixture().use { f ->
                f.methods.record("wechat")
                f.enterCode()
                f.input("123456", true)
                if (invalidate) f.tokens.afterSave = { f.service.invalidateAttempt() } else f.tokens.failSave = true
                f.respond(0)
                assertEquals("wechat", f.methods.read())
                assertEquals(listOf("wechat"), f.backend.writes)
                assertTrue(f.completed.isEmpty())
            }
        }
    }

    @Test
    fun clearedOrNewerMethodCannotBeRestoredByAnOlderSuccessfulLogin() {
        listOf(false, true).forEach { newer ->
            Login17aFixture().use { f ->
                f.enterCode()
                f.input("123456", true)
                if (newer) {
                    f.methods.record("wechat")
                } else {
                    val registry = CredentialCleanerRegistry()
                    f.methods.installCleaner(registry)
                    registry.cleanAll()
                }
                f.respond(0)
                // 确认旧请求真的成功结算；只凭取消请求导致没写方式会假绿。
                assertEquals(listOf(LoginOutcome.LOGGED_IN), f.completed)
                assertEquals(if (newer) "wechat" else null, f.methods.read())
                assertEquals(listOf(if (newer) "wechat" else null), f.backend.writes)
            }
        }
    }

    @Test
    fun pageEntriesReadTheSharedStoreAndHideLabelsWhenTheProviderDisappears() {
        listOf(false, true).forEach { restricted ->
            Login17aFixture(restricted).use { f ->
                f.methods.record("wechat")
                assertEquals("wechat", f.model.entries.lastMethod)
                f.model.bind(LoginPageConfig(f.service, Login17aFixture.Keys, restricted, oauth = f.oauth, thirdParty = emptyList()))
                assertNull(f.model.entries.lastMethod)
                assertTrue(
                    f.model.entries.providers
                        .isEmpty(),
                )
                f.model.bind(f.config)
                assertEquals("wechat", f.model.entries.lastMethod)
                f.methods.clear()
                assertNull(f.model.entries.lastMethod)
            }
        }
    }

    @Test
    fun labelsOnlyReferToProvidersActuallyShown() {
        listOf(emptyList(), listOf("wechat"), listOf("huawei", "wechat")).forEach { visible ->
            listOf(null, "sms", "wechat", "huawei", "carrier_one_tap", "apple").forEach { last ->
                val entries = LoginPageEntries.of(visible, last)
                assertEquals(visible, entries.providers)
                assertEquals(last?.takeIf { it in visible }, entries.lastMethod)
            }
        }
    }
}
