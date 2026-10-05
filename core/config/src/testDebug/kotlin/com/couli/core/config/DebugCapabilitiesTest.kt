package com.couli.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugCapabilitiesTest {
    @Test
    fun prodKeepsOnlySwitchServer() {
        val capabilities = DebugCapabilities { AppEnvironment.PROD }

        DebugCapability.entries.forEach { capability ->
            assertEquals(
                "prod 下 $capability",
                capability == DebugCapability.SWITCH_SERVER,
                capabilities.isAvailable(capability),
            )
        }
    }

    @Test
    fun nonProdEnvironmentsKeepEveryCapability() {
        AppEnvironment.entries.filter { it != AppEnvironment.PROD }.forEach { environment ->
            val capabilities = DebugCapabilities { environment }
            DebugCapability.entries.forEach { capability ->
                assertTrue("$environment 下 $capability", capabilities.isAvailable(capability))
            }
        }
    }

    @Test
    fun evaluatesTheCurrentEnvironmentOnEveryCall() {
        var environment = AppEnvironment.TEST
        val capabilities = DebugCapabilities { environment }
        assertTrue(capabilities.isAvailable(DebugCapability.DIAGNOSTICS_PANEL))

        environment = AppEnvironment.PROD

        assertFalse(capabilities.isAvailable(DebugCapability.DIAGNOSTICS_PANEL))
        assertTrue(capabilities.isAvailable(DebugCapability.SWITCH_SERVER))
    }
}
