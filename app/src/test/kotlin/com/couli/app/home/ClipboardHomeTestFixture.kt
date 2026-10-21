package com.couli.app.home

import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.core.network.ApiClient
import com.couli.core.network.SessionTokens
import kotlinx.coroutines.Dispatchers

/** 旧剪贴板集成测试也用真实首页列表，空 Box 无法验证 G-V02 合并后的头部。须在主线程调用。 */
internal fun clipboardTestHome(
    client: ApiClient,
    tokens: SessionTokens,
    routing: AppRouting,
    privacy: AppPrivacy,
): AppHome =
    AppHome(
        client = client,
        tokens = tokens,
        routing = routing,
        privacy = privacy,
        port = HomeVisualPort(HomeVisualFixtures.page("default_home")),
        lkg = null,
        prefs = null,
        images = HomeVisualFixtures.images,
        envKey = { "test" },
        configData = { HomeVisualFixtures.config },
        track = {},
        serverForced = {},
        cardTexts = HomeVisualFixtures.cardTexts,
        appVersion = "1.10.0",
        io = Dispatchers.Main.immediate,
    ).also { it.connect() }
