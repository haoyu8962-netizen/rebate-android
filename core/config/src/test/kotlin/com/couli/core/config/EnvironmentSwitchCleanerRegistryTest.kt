package com.couli.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EnvironmentSwitchCleanerRegistryTest {
    @Test
    fun cleanAllRunsEveryCleanerInRegistrationOrder() {
        val calls = mutableListOf<String>()
        val registry = EnvironmentSwitchCleanerRegistry()
        registry.register { from, to -> calls += "first:${from.key}->${to.key}" }
        registry.register { from, to -> calls += "second:${from.key}->${to.key}" }

        registry.cleanAll(AppEnvironment.TEST, AppEnvironment.STAGING)

        assertEquals(listOf("first:test->staging", "second:test->staging"), calls)
    }

    @Test
    fun oneFailingCleanerDoesNotSkipTheOthers() {
        val calls = mutableListOf<String>()
        val failure = IllegalStateException("boom")
        val registry = EnvironmentSwitchCleanerRegistry()
        registry.register { _, _ -> throw failure }
        registry.register { _, _ -> calls += "second" }

        val thrown =
            runCatching { registry.cleanAll(AppEnvironment.TEST, AppEnvironment.PROD) }
                .exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals(listOf("second"), calls)
    }
}
