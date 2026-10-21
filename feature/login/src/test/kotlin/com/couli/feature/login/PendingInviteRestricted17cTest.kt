package com.couli.feature.login

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow

/** 只旁观容器读入口并调用原方法；不是用替身模拟容器，也不改 LoginService。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], shadows = [PendingInviteReadShadow17c::class])
class PendingInviteRestricted17cTest {
    @Test
    fun restrictedServiceDoesNotEvenReadTheContainerAndOrdinaryLoginIsTheControl() =
        runTest {
            PendingInvite17cFixture(backgroundScope).use { f ->
                f.pending.set("K7M2QX")
                f.observePendingAtEntry = false // 请求探针自己也不能读，否则会污染不读断言。
                val probe = Shadow.extract<PendingInviteReadShadow17c>(f.pending)
                probe.reads = 0
                f.submit(restricted = true)
                assertEquals("受限登录已结算，peek/state/consume 均未调用", 0, probe.reads)
                assertEquals(null, f.code(0))
                f.submit()
                assertTrue("对照：正常登录确实经过同一实例的读入口", probe.reads > 0)
                assertEquals("K7M2QX", f.code(1))
            }
        }
}

@Implements(value = PendingInviteCode::class, isInAndroidSdk = false)
class PendingInviteReadShadow17c {
    @RealObject
    private lateinit var real: PendingInviteCode
    var reads = 0

    @Implementation
    fun peek(): String? {
        reads += 1
        return Shadow.directlyOn(real, PendingInviteCode::class.java, "peek")
    }

    @Implementation
    fun consumeForRequest(): String? {
        reads += 1
        return Shadow.directlyOn(real, PendingInviteCode::class.java, "consumeForRequest")
    }

    @Implementation
    fun getState(): StateFlow<String?> {
        reads += 1
        return Shadow.directlyOn(real, PendingInviteCode::class.java, "getState")
    }
}
