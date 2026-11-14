package com.couli.core.router

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-13a G01、G21、G43：DeleteAccount 的 since 三端都是 null——默认按升级提示；Debug 路由可用性覆盖放行后，参数一律静默丢弃、照常打开；
 * 深链入口没有声明（in_app、push）被拦；覆盖不影响别的路由。
 */
class App13aRouteAvailabilityTest {
    private fun state(
        overrides: Set<String> = emptySet(),
        update: UpdateState = UpdateState.OK,
        fromForceUpdatePage: Boolean = false,
        session: SessionState = SessionState.PHONE_BOUND,
    ) = GuardState(
        PrivacyState.CONSENTED,
        update,
        session,
        environmentSettled = true,
        appVersion = "1.0.0",
        isDebugBuild = false,
        fromForceUpdatePage = fromForceUpdatePage,
        availabilityOverrides = overrides,
    )

    private val params = JsonObject(mapOf("phone" to JsonPrimitive("13900001234"), "status" to JsonPrimitive("cooling")))

    // modify_existing：CT-17i（锁 d47f660e5）DeleteAccount since.android 改为 1.0.0，since=null 的例子换成 Favorites，ruling D10-1664
    @Test
    fun sinceNullDefaultsToUpgradePrompt() {
        val decision = EntryGuard.decide(RouteRequest(SINCE_NULL, JsonObject(emptyMap()), RouteSource.IN_APP), state())
        assertEquals(GuardKind.UPGRADE, decision.kind)
        assertEquals(GuardReason.UNKNOWN_ROUTE, decision.reason)
        assertFalse(RouteAvailability.available(SINCE_NULL, "1.0.0"))
        assertFalse(SINCE_NULL in RouteAvailability.all("1.0.0"))
    }

    // modify_existing：钉住 CT-17i——DeleteAccount since.android=1.0.0，不加覆盖在 1.0.0 即可用、可打开，ruling D10-1664
    @Test
    fun deleteAccountSince100IsAvailableWithoutOverride() {
        assertTrue(RouteAvailability.available(DELETE, "1.0.0"))
        assertTrue(DELETE in RouteAvailability.all("1.0.0"))
        val decision = EntryGuard.decide(RouteRequest(DELETE, JsonObject(emptyMap()), RouteSource.IN_APP), state())
        assertTrue(decision.opensPage)
    }

    @Test
    fun overrideOpensAndSilentlyDropsParams() {
        val decision = EntryGuard.decide(RouteRequest(DELETE, params, RouteSource.IN_APP), state(setOf(DELETE)))
        assertTrue(decision.opensPage)
        assertTrue("参数全部丢弃", decision.params.isEmpty())
        assertEquals(2, decision.paramsDropped)
        assertTrue(DELETE in RouteParams.silentDropRoutes)
        assertFalse("不在资金四页（不记 route_params_dropped）", DELETE in RouteParams.dropAllParamsRoutes)
        assertTrue(RouteAvailability.available(DELETE, "1.0.0", setOf(DELETE)))
    }

    @Test
    fun deepLinkBlockedEvenWithOverride() {
        val decision = EntryGuard.decide(RouteRequest(DELETE, JsonObject(emptyMap()), RouteSource.DEEPLINK), state(setOf(DELETE)))
        assertEquals(GuardKind.REJECT, decision.kind)
        assertEquals(GuardReason.ENTRY_NOT_ALLOWED, decision.reason)
    }

    @Test
    fun guestGoesToLoginAndForceUpdateSecondaryEntryWorks() {
        val guest =
            EntryGuard.decide(
                RouteRequest(DELETE, JsonObject(emptyMap()), RouteSource.IN_APP),
                state(setOf(DELETE), session = SessionState.GUEST),
            )
        assertEquals(GuardKind.REQUIRE_LOGIN, guest.kind)
        val forced =
            EntryGuard.decide(
                RouteRequest(DELETE, JsonObject(emptyMap()), RouteSource.IN_APP),
                state(setOf(DELETE), update = UpdateState.BELOW_MIN, fromForceUpdatePage = true, session = SessionState.RESTRICTED),
            )
        assertTrue(forced.opensPage)
        val notFromPage =
            EntryGuard.decide(
                RouteRequest(DELETE, JsonObject(emptyMap()), RouteSource.IN_APP),
                state(setOf(DELETE), update = UpdateState.BELOW_MIN),
            )
        assertEquals(GuardKind.FORCE_UPDATE, notFromPage.kind)
    }

    @Test
    fun overrideDoesNotAffectOtherRoutes() {
        val other = EntryGuard.decide(RouteRequest("LinkLanding", JsonObject(emptyMap()), RouteSource.IN_APP), state(setOf(DELETE)))
        assertEquals(GuardKind.UPGRADE, other.kind)
        val settings = EntryGuard.decide(RouteRequest("Settings", JsonObject(emptyMap()), RouteSource.IN_APP), state(setOf(DELETE)))
        assertTrue(settings.opensPage)
    }

    private companion object {
        const val DELETE = "DeleteAccount"

        // modify_existing：d47f660e5 routes.json 里 Favorites 的 since.android=null，作 since=null 的例子，ruling D10-1664
        const val SINCE_NULL = "Favorites"
    }
}
