package com.couli.app.routing

import android.os.Looper
import androidx.navigation.NavController

/**
 * 导航票据（APP-12 G09；鸿蒙 R1-08 / R2-03 / R3-01 的 Android 对应，共性第 8、14、17 条）：网络层在每个逻辑调用入口取一次（[ticket]），10006 的统一落点在响应时
 * 核对「发起之后用户没有再导航、没进过后台」。票据 = 经 Router 的导航次数（[TracingNavigator] 每次落到 NavHost 加 1，A → B → A 也算导航过）+ 栈顶条目（含系统返回
 * 这类不经 Router 的出栈）+ 选中的 Tab + 进后台次数。
 * - 栈顶在主线程直接读 NavController（同一条消息里已完成的导航立即可见，避免鸿蒙 R3-01「请求捕获了导航中途的旧栈」）；别的线程读最近一次回退栈通知里的值；
 * - 取不到（还没有外壳）为 null，落点一律不放行。
 */
class NavTickets {
    /** 一张票据。 */
    data class Ticket(
        val serial: Long,
        val top: String?,
        val tab: String?,
        val background: Long,
    )

    @Volatile private var serial = 0L

    @Volatile private var cachedTop: String? = null

    @Volatile private var tab: String? = null

    @Volatile private var background = 0L

    @Volatile private var controller: NavController? = null

    fun attach(navController: NavController) {
        controller = navController
    }

    fun detach(navController: NavController) {
        if (controller === navController) controller = null
    }

    /** 经 Router 落到 NavHost 的一次导航（push、切 Tab、强更、升级提示；主线程，导航之前）。 */
    fun onNavigate() {
        serial += 1
    }

    /** 回退栈变化（主线程）。 */
    fun onBackStack(top: String?) {
        cachedTop = top
    }

    fun onTab(route: String) {
        tab = route
    }

    /** 真正进入后台（MainActivity.onStop，配置变更除外）。 */
    fun onBackground() {
        background += 1
    }

    private fun top(): String? {
        val nav = controller ?: return null
        return if (Looper.myLooper() == Looper.getMainLooper()) runCatching { nav.currentBackStackEntry?.id }.getOrNull() else cachedTop
    }

    /** 此刻的票据；没有外壳为 null。 */
    fun ticket(): Ticket? {
        if (controller == null) return null
        return Ticket(serial, top(), tab, background)
    }

    /** 只看导航次数与进后台次数（退出登录的落点：发起后的页面清理由会话换代带走，不算用户导航，R1-09）。 */
    fun routerTicket(): Pair<Long, Long> = serial to background
}
