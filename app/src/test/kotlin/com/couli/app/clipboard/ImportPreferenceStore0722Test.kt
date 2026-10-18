package com.couli.app.clipboard

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.clipboard.ImportPreferenceStore
import com.couli.core.config.CouliPreferenceNamespaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FU-AND-0722：真实 SharedPreferences 适配器；新接口约定见本任务 tests-handback.md。
 * 按同步存储接口返回后断言；重建适配器验证可读回，不规定 commit/apply、JSON、集合实现或内部键排列。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class ImportPreferenceStore0722Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 1_800_000_000_123L
    private val raw get() = context.getSharedPreferences(CouliPreferenceNamespaces.CLIPBOARD, Context.MODE_PRIVATE)

    private fun reopen(): ImportPreferenceStore = ClipboardPreferences(context, SampleCipher).importPreferences(clock = { now })

    @Before
    fun clear() {
        assertTrue(raw.edit().clear().commit())
    }

    @Test
    fun r01_freshInstallIsUnsetAndPrivacyConsentAloneCannotAuthorizeReading() {
        val store = reopen()
        val state = store.snapshot()
        val allowed = store.isAutoReadAllowed("1")
        val seen = store.hasSeenNotice("1")
        val pending = store.consentQueue.pending()
        assertNull(state.enabled)
        assertNull(state.consentVersion)
        assertNull(state.consentedAtMillis)
        assertNull(state.noticeSeenVersion)
        assertFalse(allowed)
        assertFalse(seen)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun r01_legacyTrueAndFalseAreDeletedAndNeitherCountsAsPurposeConsent() {
        for (legacyValue in listOf(true, false)) {
            assertTrue(
                raw
                    .edit()
                    .clear()
                    .putBoolean("device_auto_detect", legacyValue)
                    .commit(),
            )
            val store = reopen()
            val state = store.snapshot()
            val retainedLegacy = raw.contains("device_auto_detect")
            val allowed = store.isAutoReadAllowed("1")
            assertNull("旧值 $legacyValue 不能迁成 on 或 off", state.enabled)
            assertNull(state.consentVersion)
            assertFalse(retainedLegacy)
            assertFalse(allowed)
            assertEquals(state, reopen().snapshot())
        }
    }

    @Test
    fun r03_enablePersistsExactStringVersionInjectedTimeAndDeviceOutbox() {
        val store = reopen()
        val acceptedAt = now
        store.enable("purpose-02")
        now += 86_400_000L
        val rebuilt = reopen()
        val state = rebuilt.snapshot()
        val pending = rebuilt.consentQueue.pending()
        assertEquals(true, state.enabled)
        assertEquals("purpose-02", state.consentVersion)
        assertEquals(acceptedAt, state.consentedAtMillis)
        assertTrue(rebuilt.isAutoReadAllowed("purpose-02"))
        assertTrue(rebuilt.hasSeenNotice("purpose-02"))
        assertEquals(1, pending.size)
        val consent = pending.single()
        assertEquals("clipboard_auto_import", consent.type)
        assertEquals("purpose-02", consent.version)
        assertEquals(acceptedAt, consent.acceptedAtMillis)
        assertEquals("device", consent.subject)
    }

    @Test
    fun r04_notNowPersistsSeenButLeavesChoiceAndConsentUnset() {
        reopen().markNoticeSeen("purpose-02")
        val rebuilt = reopen()
        val state = rebuilt.snapshot()
        val allowed = rebuilt.isAutoReadAllowed("purpose-02")
        val pending = rebuilt.consentQueue.pending()
        assertTrue(rebuilt.hasSeenNotice("purpose-02"))
        assertNull(state.enabled)
        assertNull(state.consentVersion)
        assertNull(state.consentedAtMillis)
        assertFalse(allowed)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun r05_changedVersionInvalidatesConsentAndSeenWithoutRewritingOnOrOff() {
        val store = reopen()
        store.enable("02")
        val original = store.snapshot()
        val originalQueue = store.consentQueue.pending()
        // 字符串比较，"02" 和 "2" 不能当成同一个版本。
        val allowedNew = store.isAutoReadAllowed("2")
        val seenNew = store.hasSeenNotice("2")
        assertFalse(allowedNew)
        assertFalse(seenNew)
        assertEquals(original.enabled, reopen().snapshot().enabled)
        assertEquals(originalQueue, reopen().consentQueue.pending())
        store.setOff()
        val off = reopen()
        val offAllowed = off.isAutoReadAllowed("2")
        val offSeen = off.hasSeenNotice("2")
        assertEquals(false, off.snapshot().enabled)
        assertFalse(offAllowed)
        assertFalse(offSeen)
    }

    @Test
    fun r05_seenWithoutConsentAlsoExpiresWithTheVersion() {
        val store = reopen()
        store.markNoticeSeen("1")
        val same = store.hasSeenNotice("1")
        val changed = store.hasSeenNotice("2")
        assertTrue(same)
        assertFalse(changed)
        assertNull(reopen().snapshot().enabled)
    }

    @Test
    fun g20_switchOffPreservesPurposeConsentAndDoesNotEnqueueAWithdrawal() {
        val store = reopen()
        store.enable("1")
        val original = store.snapshot()
        val originalQueue = store.consentQueue.pending()
        now += 1_000L
        store.setOff()
        val rebuilt = reopen()
        val allowed = rebuilt.isAutoReadAllowed("1")
        assertEquals(original.copy(enabled = false), rebuilt.snapshot())
        assertTrue(rebuilt.hasSeenNotice("1"))
        assertFalse(allowed)
        assertEquals(originalQueue, rebuilt.consentQueue.pending())
    }

    @Test
    fun r06_privacyWithdrawalClearsLocalChoiceWithoutAddingConsentReports() {
        val store = reopen()
        store.enable("purpose-02")
        val before = store.consentQueue.pending()
        assertEquals(1, before.size)
        store.clearOnPrivacyWithdraw()
        val rebuilt = reopen()
        val state = rebuilt.snapshot()
        val seen = rebuilt.hasSeenNotice("purpose-02")
        val allowed = rebuilt.isAutoReadAllowed("purpose-02")
        val pending = rebuilt.consentQueue.pending()
        assertNull(state.enabled)
        assertNull(state.consentVersion)
        assertNull(state.consentedAtMillis)
        assertNull(state.noticeSeenVersion)
        assertNull(state.deniedAtMillis)
        assertFalse(seen)
        assertFalse(allowed)
        assertNoNewImportConsents(before, pending)
    }

    @Test
    fun r06_registeredWithdrawalCleanerAlsoClearsTheImportStore() {
        val store = reopen()
        store.enable("1")
        val before = store.consentQueue.pending()
        assertEquals(1, before.size)
        ClipboardPreferences(context, SampleCipher).clearHashes()
        val rebuilt = reopen()
        val state = rebuilt.snapshot()
        val pending = rebuilt.consentQueue.pending()
        assertNull(state.enabled)
        assertNull(state.consentVersion)
        assertNull(state.consentedAtMillis)
        assertNull(state.noticeSeenVersion)
        assertNoNewImportConsents(before, pending)
    }
}
