package com.couli.core.config

import org.junit.Assert.assertNull
import org.junit.Test

/** FU-X-STAGING-HOSTS-android 测试 v3：PROD 不设流式入口（D10-598 ①）；字段缺省为 null。main 源码集，release 构建同样成立。 */
class ProdStreamBaseTest {
    @Test fun prodHasNoStreamBase() {
        assertNull(ProdEndpoints.endpoints.streamBaseUrl)
    }

    @Test fun streamBaseDefaultsToNull() {
        assertNull(
            EnvironmentEndpoints(AppEnvironment.PROD, "https://api.prod.couli.invalid", "https://h5.prod.couli.invalid").streamBaseUrl,
        )
    }
}
