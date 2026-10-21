package com.couli.app.login

import com.couli.core.privacy.CredentialCleanerRegistry
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.feature.login.LastLoginMethodBackend
import com.couli.feature.login.LastLoginMethodStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginWithdrawal17aTest {
    @Test
    fun failedMethodDeletionKeepsWithdrawalPendingAndColdStartRetries() {
        val backend = FailingMethodBackend()
        val methods = LastLoginMethodStore(backend)
        val registry = CredentialCleanerRegistry()
        methods.installCleaner(registry)
        val privacy = WithdrawalStore()
        val gate = gate(privacy, registry)
        methods.record("wechat")
        val old = methods.revision
        backend.fail = true
        assertTrue(runCatching { gate.withdraw() }.isFailure)
        assertTrue(privacy.withdrawalCleanupPending)
        assertEquals("wechat", backend.value)
        backend.fail = false
        assertFalse(methods.record("sms", old))
        val restarted = gate(privacy, registry)
        restarted.coldStart()
        assertFalse(privacy.withdrawalCleanupPending)
        assertNull(methods.read())
        assertTrue(restarted.credentialAccess.readable())
    }

    private fun gate(
        store: PrivacyStore,
        cleaners: CredentialCleanerRegistry,
    ) = PrivacyGate(store, { 1_700_000_000_000L }, "1").apply {
        withdrawalCleanup = cleaners::cleanAll
    }
}

private class FailingMethodBackend : LastLoginMethodBackend {
    var value: String? = null
    var fail = false

    override fun read() = value

    override fun write(method: String?): Boolean {
        if (fail) return false
        value = method
        return true
    }
}

private class WithdrawalStore : PrivacyStore {
    override var decisionRaw = "consented"
    override var consentedVersion: String? = "1"
    override var consentedAtMillis: Long? = 0L
    override var basicMode = false
    override var withdrawalCleanupPending = false
}
