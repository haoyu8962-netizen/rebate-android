@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.clipboard

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.ProcessLifecycleInitializer
import androidx.startup.AppInitializer
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.clipboard.ClipRead
import java.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 真正的 ProcessLifecycleOwner；虚拟 Looper 推进延迟，不复制 Android 的轮次算法。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class ClipboardProcess0724Test {
    @Test
    fun processStopStartsANewRoundButImmediateActivityReplacementDoesNot() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Application>()
            // Robolectric 不会自动创建合并清单中的 Startup provider；先走真实 eager 初始化入口。
            Robolectric.setupContentProvider(InitializationProvider::class.java, context.packageName + ".androidx-startup")
            val owner = AppInitializer.getInstance(context).initializeComponent(ProcessLifecycleInitializer::class.java)
            Clipboard0724Fixture(this, lifecycle = owner.lifecycle).use { f ->
                var activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                f.clipboard.onWindowFocus(true)
                f.settle()
                assertEquals(1, f.system.reads)
                val epoch =
                    checkNotNull(
                        f.clipboard.importCoordinator
                            .snapshot()
                            .epoch,
                    )
                activity.pause().stop().destroy()
                activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
                f.clipboard.onWindowFocus(true)
                f.settle()
                assertEquals(
                    "进程没收到 stop 的 Activity 重建不是新轮",
                    epoch,
                    f.clipboard.importCoordinator
                        .snapshot()
                        .epoch,
                )
                assertEquals(1, f.system.reads)
                activity.pause().stop()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
                f.settle()
                f.system.content = ClipRead.Text("https://u.jd.com/next0724")
                activity.start().resume()
                f.clipboard.onWindowFocus(true)
                f.settle()
                assertEquals(
                    epoch + 1,
                    f.clipboard.importCoordinator
                        .snapshot()
                        .epoch,
                )
                assertEquals(2, f.system.reads)
                assertEquals(2, f.parseBodies().size)
                assertTrue(
                    f.clipboard.importCoordinator
                        .snapshot()
                        .consumed,
                )
                activity.pause().stop().destroy()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            }
        }
}
