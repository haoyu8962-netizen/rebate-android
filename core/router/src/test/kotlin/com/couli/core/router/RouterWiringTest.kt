package com.couli.core.router

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 向量之外的接线：Router 执行、pending 恢复、启动意图排队、推送落点、版本比较。 */
class RouterWiringTest {
    private val empty = JsonObject(emptyMap())

    private class RecordingNavigator : RouteNavigator {
        val events = mutableListOf<String>()

        override fun open(
            route: String,
            params: JsonObject,
            readonly: Boolean,
        ) {
            events += "open:$route:${if (readonly) "ro" else "rw"}:$params"
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
            events += "blocked:${request.route}:${decision.reason}"
        }
    }

    private fun state(session: SessionState = SessionState.GUEST) =
        GuardState(
            privacy = PrivacyState.CONSENTED,
            update = UpdateState.OK,
            session = session,
            environmentSettled = true,
            appVersion = "1.0.0",
            isDebugBuild = false,
        )

    @Test
    fun guestOpeningLoginRouteRegistersPendingAndResumesAfterLogin() {
        var now = 1_000L
        var session = SessionState.GUEST
        val backend = MemoryPendingBackend()
        val store = PendingActionStore(backend) { now }
        val navigator = RecordingNavigator()
        val router = Router({ state(session) }, store, navigator)

        val decision = router.open(RouteRequest("OrderDetail", JsonObject(mapOf("order_id" to JsonPrimitive("o-1"))), RouteSource.IN_APP))
        assertEquals(GuardKind.REQUIRE_LOGIN, decision.kind)
        assertEquals("open:Login:rw:{}", navigator.events.last())
        assertNotNull(backend.read())

        now += 60_000L
        session = SessionState.LOGGED_IN
        val resumed = router.onPrerequisiteCompleted()
        assertEquals(GuardKind.OPEN, resumed?.kind)
        assertEquals("open:OrderDetail:rw:{\"order_id\":\"o-1\"}", navigator.events.last())
        assertNull("打开后删除", backend.read())
    }

    @Test
    fun pendingKeptWhileStillMissingPrerequisiteAndDroppedAfterTimeout() {
        var now = 0L
        var session = SessionState.GUEST
        val backend = MemoryPendingBackend()
        val router = Router({ state(session) }, PendingActionStore(backend) { now }, RecordingNavigator())
        router.open(RouteRequest("InviteShare", empty, RouteSource.IN_APP))
        session = SessionState.LOGGED_IN
        now = 1_000L
        assertEquals(GuardKind.REQUIRE_BIND_PHONE, router.onPrerequisiteCompleted()?.kind)
        assertNotNull("仍缺绑手机：保留", backend.read())
        session = SessionState.PHONE_BOUND
        now += PendingActionStore.TTL_MILLIS + 1
        assertNull("超时丢弃", router.resumePending())
        assertNull(backend.read())
    }

    @Test
    fun sensitiveParamsAreNotRegistered() {
        val backend = MemoryPendingBackend()
        val router = Router({ state() }, PendingActionStore(backend) { 0L }, RecordingNavigator())
        router.open(RouteRequest("Search", JsonObject(mapOf("access_token" to JsonPrimitive("x"))), RouteSource.IN_APP))
        assertNull(backend.read())
    }

    @Test
    fun realnameRequiredWhenPhoneBoundButNotVerified() {
        var verified = false
        val backend = MemoryPendingBackend()
        val navigator = RecordingNavigator()
        val router =
            Router(
                { state(SessionState.PHONE_BOUND).copy(realnameVerified = verified) },
                PendingActionStore(backend) { 0L },
                navigator,
            )
        val decision = router.open(RouteRequest("Withdraw", empty, RouteSource.IN_APP))
        assertEquals(GuardKind.REQUIRE_REALNAME, decision.kind)
        assertEquals("require_realname", decision.kindRaw)
        assertEquals("RealName", decision.route)
        assertEquals("open:RealName:rw:{}", navigator.events.last())
        assertNull("提现不登记 pending_action（BR-ID-10 敏感操作，R1-02）", backend.read())
        verified = true
        assertNull("实名完成后不自动打开提现", router.onPrerequisiteCompleted())
        assertTrue(navigator.events.none { it.startsWith("open:Withdraw") })
        // 用户自己再点：已实名，照常打开
        assertEquals(GuardKind.OPEN, router.open(RouteRequest("Withdraw", empty, RouteSource.IN_APP)).kind)
        // 登录态只到 login 的仍按顺序先绑手机
        assertEquals(
            GuardKind.REQUIRE_BIND_PHONE,
            EntryGuard.decide(RouteRequest("Withdraw", empty, RouteSource.IN_APP), state(SessionState.LOGGED_IN)).kind,
        )
    }

    @Test
    fun paramsSchemaIsCheckedForEverySource() {
        val bad = JsonObject(mapOf("order_id" to JsonPrimitive(1)))
        val missing = empty
        for (source in listOf(RouteSource.IN_APP, RouteSource.BRIDGE, RouteSource.PUSH, RouteSource.PENDING_ACTION)) {
            for (params in listOf(bad, missing)) {
                val decision = EntryGuard.decide(RouteRequest("OrderDetail", params, source), state(SessionState.LOGGED_IN))
                assertEquals("$source $params", GuardReason.PARAMS_INVALID, decision.reason)
            }
        }
        val deeplink = EntryGuard.decide(RouteRequest("Search", JsonObject(mapOf("q" to JsonPrimitive(1))), RouteSource.DEEPLINK), state())
        assertEquals(GuardReason.PARAMS_INVALID, deeplink.reason)
        val extra = EntryGuard.decide(RouteRequest("Home", JsonObject(mapOf("x" to JsonPrimitive("1"))), RouteSource.BRIDGE), state())
        assertEquals(GuardReason.PARAMS_INVALID, extra.reason)
    }

    @Test
    fun externalAndWebPagesRequireAllowlistedTargets() {
        fun url(value: String) = JsonObject(mapOf("url" to JsonPrimitive(value)))
        val external = RouteRequest("ExternalPage", url("https://shop.example.invalid/a"), RouteSource.IN_APP)
        val web = RouteRequest("WebPage", url("https://h5.example.invalid/x"), RouteSource.IN_APP)
        assertEquals("缺省空名单：全拒", GuardReason.TARGET_NOT_ALLOWED, EntryGuard.decide(external, state()).reason)
        assertEquals(GuardReason.TARGET_NOT_ALLOWED, EntryGuard.decide(web, state()).reason)
        val allowed = state().copy(externalHosts = listOf("SHOP.example.invalid"), bridgeOrigins = listOf("https://h5.example.invalid"))
        assertEquals(GuardKind.OPEN, EntryGuard.decide(external, allowed).kind)
        assertEquals(GuardKind.OPEN, EntryGuard.decide(web, allowed).kind)
        val pushExternal = external.copy(source = RouteSource.PUSH.raw)
        assertEquals(GuardKind.REJECT, EntryGuard.decide(pushExternal, state()).kind)
        val deeplinkExternal = external.copy(source = RouteSource.DEEPLINK.raw)
        assertEquals(GuardKind.OPEN, EntryGuard.decide(deeplinkExternal, allowed).kind)
        val subdomain = RouteRequest("ExternalPage", url("https://evil.shop.example.invalid/"), RouteSource.IN_APP)
        assertEquals(GuardKind.REJECT, EntryGuard.decide(subdomain, allowed).kind)
        val userInfo = RouteRequest("ExternalPage", url("https://shop.example.invalid@evil.invalid/"), RouteSource.IN_APP)
        assertEquals(GuardKind.REJECT, EntryGuard.decide(userInfo, allowed).kind)
        val otherPort = RouteRequest("WebPage", url("https://h5.example.invalid:8443/x"), RouteSource.IN_APP)
        assertEquals(GuardKind.REJECT, EntryGuard.decide(otherPort, allowed).kind)
    }

    @Test
    fun consentRequiredRegistersPendingAndContinuesWithPrerequisites() {
        var privacy = PrivacyState.BASIC_MODE
        var session = SessionState.GUEST
        val backend = MemoryPendingBackend()
        val navigator = RecordingNavigator()
        val router =
            Router({ state(session).copy(privacy = privacy.raw) }, PendingActionStore(backend) { 0L }, navigator)
        assertEquals(GuardKind.REQUIRE_CONSENT, router.open(RouteRequest("Wallet", empty, RouteSource.IN_APP)).kind)
        assertEquals("consent:Wallet", navigator.events.last())
        assertNotNull("要补同意也登记", backend.read())
        privacy = PrivacyState.CONSENTED
        assertEquals(GuardKind.REQUIRE_LOGIN, router.onPrerequisiteCompleted()?.kind)
        assertEquals("同意后按前置继续：进登录页", "open:Login:rw:{}", navigator.events.last())
        assertNotNull(backend.read())
        session = SessionState.LOGGED_IN
        assertEquals(GuardKind.OPEN, router.onPrerequisiteCompleted()?.kind)
        assertEquals("open:Wallet:rw:{}", navigator.events.last())
        assertNull(backend.read())
    }

    @Test
    fun agentCardPendingIsDroppedOnPrivacyBlock() {
        var privacy = PrivacyState.CONSENTED
        var session = SessionState.GUEST
        val backend = MemoryPendingBackend()
        val navigator = RecordingNavigator()
        val router = Router({ state(session).copy(privacy = privacy.raw) }, PendingActionStore(backend) { 0L }, navigator)
        router.open(RouteRequest("Wallet", empty, RouteSource.AGENT_CARD))
        assertEquals(RouteSource.AGENT_CARD.raw, PendingActionStore(backend) { 0L }.load()?.origin)
        privacy = PrivacyState.NOT_DECIDED
        session = SessionState.LOGGED_IN
        assertNull("隐私阻断：删除不执行", router.onPrerequisiteCompleted())
        assertNull(backend.read())
        privacy = PrivacyState.CONSENTED
        assertNull("同意后也不补打开", router.resumePending())
        assertTrue(navigator.events.none { it.startsWith("open:Wallet") })

        // Agent 卡片遇隐私未决：不登记
        privacy = PrivacyState.NOT_DECIDED
        assertEquals(GuardReason.AGENT_CONSENT_NO_REPLAY, router.open(RouteRequest("Wallet", empty, RouteSource.AGENT_CARD)).reason)
        assertNull(backend.read())
    }

    @Test
    fun pushLookupResultIsJudgedWithLatestState() =
        runBlocking {
            var update = UpdateState.OK
            var session = SessionState.LOGGED_IN
            val navigator = RecordingNavigator()
            val router =
                Router(
                    { state(session).copy(update = update.raw) },
                    PendingActionStore(MemoryPendingBackend()) { 0L },
                    navigator,
                )
            val landing =
                PushLanding({
                    // 回查期间进入强更
                    update = UpdateState.BELOW_MIN
                    PushLookupResult.Found("Wallet", empty)
                })
            assertEquals(GuardKind.FORCE_UPDATE, router.openPush("m-1", landing)?.kind)

            update = UpdateState.OK
            val logout =
                PushLanding({
                    // 回查期间退出登录
                    session = SessionState.GUEST
                    PushLookupResult.Found("Wallet", empty)
                })
            assertEquals(GuardKind.REQUIRE_LOGIN, router.openPush("m-2", logout)?.kind)

            // 点击时未登录：登记 Messages、进登录页
            val backend = MemoryPendingBackend()
            val guestRouter = Router({ state(SessionState.GUEST) }, PendingActionStore(backend) { 0L }, navigator)
            assertEquals(GuardKind.REQUIRE_LOGIN, guestRouter.openPush("m-3", PushLanding({ error("不应回查") }))?.kind)
            assertTrue(backend.read().orEmpty().contains("Messages"))
        }

    @Test
    fun logoutClearsPending() {
        val backend = MemoryPendingBackend()
        val router = Router({ state() }, PendingActionStore(backend) { 0L }, RecordingNavigator())
        router.open(RouteRequest("Wallet", empty, RouteSource.IN_APP))
        assertNotNull(backend.read())
        router.onSessionEnded()
        assertNull(backend.read())
    }

    @Test
    fun rejectAndUpgradeGoToNavigatorWithoutOpening() {
        val navigator = RecordingNavigator()
        val router = Router({ state() }, PendingActionStore(MemoryPendingBackend()) { 0L }, navigator)
        router.open(RouteRequest("Wallet", empty, RouteSource.DEEPLINK))
        router.open(RouteRequest("NoSuchRoute", empty, RouteSource.DEEPLINK))
        assertEquals(listOf("blocked:Wallet:entry_not_allowed", "upgrade:NoSuchRoute"), navigator.events)
        assertEquals(
            "NoSuchRoute",
            router.lastOutcome.value
                ?.request
                ?.route,
        )
    }

    @Test
    fun fundPagesDropParamsBeforeNavigation() {
        val navigator = RecordingNavigator()
        val router = Router({ state(SessionState.PHONE_BOUND) }, PendingActionStore(MemoryPendingBackend()) { 0L }, navigator)
        val decision = router.open(RouteRequest("RealName", JsonObject(mapOf("name" to JsonPrimitive("x"))), RouteSource.BRIDGE))
        assertEquals(1, decision.paramsDropped)
        assertEquals("open:RealName:rw:{}", navigator.events.single())
    }

    @Test
    fun invalidParamsAreRejected() {
        val decision =
            EntryGuard.decide(RouteRequest("Search", JsonObject(mapOf("q" to JsonPrimitive(1))), RouteSource.IN_APP), state())
        assertEquals(GuardKind.REJECT, decision.kind)
        assertEquals(GuardReason.PARAMS_INVALID, decision.reason)
    }

    @Test
    fun unpublishedRouteIsTreatedAsUnknown() {
        val decision = EntryGuard.decide(RouteRequest("DeleteAccount", empty, RouteSource.IN_APP), state(SessionState.LOGGED_IN))
        assertEquals(GuardKind.UPGRADE, decision.kind)
        assertEquals(GuardReason.UNKNOWN_ROUTE, decision.reason)
    }

    @Test
    fun unknownSourceIsRejected() {
        val decision = EntryGuard.decide(RouteRequest("Home", empty, "notification"), state())
        assertEquals(GuardKind.REJECT, decision.kind)
    }

    @Test
    fun queueWaitsForGatesAndTimesOutUpdateCheckAsUnknown() {
        val queue = StartupIntentQueue()
        val link = StartupIntent.Link(RouteRequest("Home", empty, RouteSource.DEEPLINK))
        val waiting = StartupReadiness(environmentSettled = false, privacy = PrivacyState.NOT_DECIDED, update = UpdateCheck.CHECKING)
        assertTrue(queue.offer(link, coldStart = true, readiness = waiting, now = 0L))
        assertTrue(queue.drain(waiting, now = 10_000L).isEmpty())
        val privacyDone = waiting.copy(environmentSettled = true, privacy = PrivacyState.CONSENTED)
        assertTrue("闸门在 t=10s 通过：强更等待从此起算", queue.drain(privacyDone, now = 10_000L).isEmpty())
        assertTrue("检查中、未满 5 秒：继续等", queue.drain(privacyDone, now = 14_999L).isEmpty())
        val released = queue.drain(privacyDone, now = 15_000L)
        assertEquals(listOf(ReleasedIntent(link, UpdateState.UNKNOWN)), released)
        assertEquals(0, queue.size)
    }

    @Test
    fun queueReleasesImmediatelyWhenCheckHasResult() {
        val queue = StartupIntentQueue()
        val push = StartupIntent.Push("m-1")
        val ready = StartupReadiness(environmentSettled = true, privacy = PrivacyState.BASIC_MODE, update = UpdateCheck.BELOW_MIN)
        assertTrue(queue.offer(push, coldStart = true, readiness = ready, now = 0L))
        assertEquals(listOf(ReleasedIntent(push, UpdateState.BELOW_MIN)), queue.drain(ready, now = 1L))
    }

    @Test
    fun queueWaitsForEnvironmentEvenAfterTimeout() {
        val queue = StartupIntentQueue()
        val blocked = StartupReadiness(environmentSettled = false, privacy = PrivacyState.CONSENTED, update = UpdateCheck.OK)
        queue.offer(StartupIntent.Push("m"), coldStart = true, readiness = blocked, now = 0L)
        assertTrue(queue.drain(blocked, now = 60_000L).isEmpty())
        assertNull("闸门未通过：没有定时器，等状态变化", queue.nextDeadline(blocked))
    }

    @Test
    fun warmStartIntentIsDroppedOnPrivacyPopupOrForceUpdate() {
        val queue = StartupIntentQueue()
        val link = StartupIntent.Link(RouteRequest("Home", empty, RouteSource.DEEPLINK))
        val popup = StartupReadiness(environmentSettled = true, privacy = PrivacyState.NOT_DECIDED, update = UpdateCheck.OK)
        val force = StartupReadiness(environmentSettled = true, privacy = PrivacyState.CONSENTED, update = UpdateCheck.BELOW_MIN)
        assertFalse(queue.offer(link, coldStart = false, readiness = popup, now = 0L))
        assertFalse(queue.offer(link, coldStart = false, readiness = force, now = 0L))
        assertEquals(0, queue.size)
    }

    @Test
    fun pushLandingCoversGuestFailureTimeoutAndFound() =
        runBlocking {
            val found =
                PushLanding({ PushLookupResult.Found("OrderList", JsonObject(mapOf("scope" to JsonPrimitive("self")))) })
            assertEquals(PushLandingResult.LoginThenMessages, found.resolve("m-1", SessionState.GUEST))
            val opened = found.resolve("m-1", SessionState.LOGGED_IN) as PushLandingResult.Open
            assertEquals("OrderList", opened.request.route)
            assertEquals("push", opened.request.source)

            val messages = PushLandingResult.Open(PushLanding.messagesRequest())
            assertEquals(messages, PushLanding({ PushLookupResult.NotFound }).resolve("m-1", SessionState.LOGGED_IN))
            assertEquals(messages, PushLanding({ PushLookupResult.Failed }).resolve("m-1", SessionState.LOGGED_IN))
            assertEquals(messages, PushLanding({ PushLookupResult.Found("", empty) }).resolve("m-1", SessionState.LOGGED_IN))
            assertEquals(messages, PushLanding({ error("boom") }).resolve("m-1", SessionState.LOGGED_IN))
            val slow =
                PushLanding(
                    lookup = {
                        delay(10_000L)
                        PushLookupResult.Found("Wallet", empty)
                    },
                    timeoutMillis = 20L,
                )
            assertEquals(messages, slow.resolve("m-1", SessionState.LOGGED_IN))
        }

    @Test
    fun versionComparison() {
        assertEquals(0, AppVersion.compare("1.0", "1.0.0"))
        assertTrue(AppVersion.atLeast("1.10.0", "1.9.9"))
        assertTrue(AppVersion.atLeast("1.0.0-staging", "1.0.0"))
        assertFalse(AppVersion.atLeast("0.9.9", "1.0.0"))
        assertNull(AppVersion.compare("1.x", "1.0.0"))
        assertFalse(AppVersion.atLeast("", "1.0.0"))
    }

    @Test
    fun deepLinkRejectsFragmentDuplicateKeysAndBadEncoding() {
        val config = DeepLinkConfig(schemes = listOf("couli-dev"), hosts = listOf("links.example.invalid"))
        assertNull(DeepLinkParser.parse("couli-dev://r/Home#x", config))
        assertNull(DeepLinkParser.parse("couli-dev://r/Search?q=a&q=b", config))
        assertNull(DeepLinkParser.parse("couli-dev://r/Search?q=%E7%BA", config))
        assertNull(DeepLinkParser.parse("https://links.example.invalid:8443/r/Home", config))
        assertEquals(
            "a+b",
            DeepLinkParser
                .parse("couli-dev://r/Search?q=a+b", config)
                ?.params
                ?.get("q")
                ?.let { (it as JsonPrimitive).content },
        )
        assertEquals("Wallet", DeepLinkParser.parse("HTTPS://LINKS.example.invalid/r/Wallet", config)?.route)
        assertEquals("NoSuchRoute", DeepLinkParser.parse("couli-dev://r/NoSuchRoute?x=1", config)?.route)
    }
}
