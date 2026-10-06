package com.couli.core.router

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** APP-02c：强更检查中（F-ALL-03）与 below_min 时删除持久化待办（F-AND-07）。 */
class RouterUpdateCheckingTest {
    private val empty = JsonObject(emptyMap())

    private class NoopNavigator : RouteNavigator {
        val events = mutableListOf<String>()

        override fun open(
            route: String,
            params: JsonObject,
            readonly: Boolean,
        ) {
            events += "open:$route"
        }

        override fun showUpgradePrompt(request: RouteRequest) {
            events += "upgrade:${request.route}"
        }

        override fun requestConsent(request: RouteRequest) {
            events += "consent:${request.route}"
        }

        override fun blocked(
            request: RouteRequest,
            decision: GuardDecision,
        ) {
            events += "blocked:${request.route}"
        }
    }

    private fun state(update: UpdateState) =
        GuardState(
            privacy = PrivacyState.CONSENTED,
            update = update,
            session = SessionState.GUEST,
            environmentSettled = true,
            appVersion = "1.0.0",
            isDebugBuild = false,
        )

    @Test
    fun externalSourcesDeferWhileCheckingButInAppDoesNot() {
        val checking = state(UpdateState.CHECKING)
        for (source in RouteSource.entries.filter { it.isExternalOrAutomatic }) {
            val decision = EntryGuard.decide(RouteRequest("Search", empty, source), checking)
            assertEquals("$source", GuardKind.DEFER, decision.kind)
            assertEquals("$source", GuardReason.UPDATE_CHECKING, decision.reason)
        }
        val inApp = EntryGuard.decide(RouteRequest("Search", empty, RouteSource.IN_APP), checking)
        assertNotEquals(GuardKind.DEFER, inApp.kind)
    }

    @Test
    fun pendingResumeWhileCheckingDefersAndKeepsRecord() {
        var update = UpdateState.OK
        val backend = MemoryPendingBackend()
        val router = Router({ state(update) }, PendingActionStore(backend) { 0L }, NoopNavigator())
        router.open(RouteRequest("OrderList", empty, RouteSource.IN_APP))
        assertNotNull(backend.read())
        update = UpdateState.CHECKING
        val decision = router.resumePending(enterPrerequisite = false)
        assertEquals(GuardKind.DEFER, decision?.kind)
        assertEquals(GuardReason.UPDATE_CHECKING, decision?.reason)
        assertNotNull("检查中只等待，不删待办", backend.read())
    }

    @Test
    fun belowMinResumeIsQueuedOnWarmStartAndGuardDeletesRecord() {
        val queue = StartupIntentQueue()
        val belowMin = StartupReadiness(environmentSettled = true, privacy = PrivacyState.CONSENTED, update = UpdateCheck.BELOW_MIN)
        assertTrue("F-AND-07：below_min 时恢复照样排队", queue.offer(StartupIntent.ResumePending, coldStart = false, belowMin, 0L))
        val released = queue.drain(belowMin, 0L)
        assertEquals(UpdateState.BELOW_MIN, released.single().update)

        var update = UpdateState.OK
        val backend = MemoryPendingBackend()
        val router = Router({ state(update) }, PendingActionStore(backend) { 0L }, NoopNavigator())
        router.open(RouteRequest("OrderList", empty, RouteSource.IN_APP))
        assertNotNull(backend.read())
        update = UpdateState.BELOW_MIN
        router.resumePending(enterPrerequisite = false)
        assertNull("below_min：守卫删除持久化待办", backend.read())
    }
}
