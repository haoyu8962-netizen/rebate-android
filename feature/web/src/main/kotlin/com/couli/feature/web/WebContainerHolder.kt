package com.couli.feature.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** 建 WebView 的方式（仪器测试替换成不连网络的替身；生产就是系统 WebView）。 */
internal val LocalWebViewFactory = staticCompositionLocalOf<(Context) -> WebView> { { context -> WebView(context) } }

/**
 * 一个容器路由实例自己的 WebView、控制器与页面状态（Android 第 1 轮评审 R1-04；APP-04 三端共性第 28、36 条）。
 * 跟随所在的 ViewModelStoreOwner——在 NavHost 里就是这条路由的回退栈条目：
 * - **被别的页面盖住**（Navigation 把被盖住的条目移出组合）、ui.setNavBar 切换 immersive、加载失败显示错误页：都不是出栈，
 *   WebView 原样保留，重新进入组合时挂回同一个实例（地址、历史、表单、在途调用都在）；
 * - **真正出栈**：[teardown] 立即作废这个实例的全部桥调用、停止加载并销毁 WebView。只拆一次。
 *   - 关闭按钮、nav.close、系统返回关闭：容器自己先拆再交外层出栈；
 *   - 清栈、被替换、强更替换导航栈：路由一确定移出回退栈就同步拆除（Android 第 2 轮评审 R2-03、共性第 47 条），不等 Navigation
 *     的退场过渡结束后才清除回退栈条目——见 [WebContainers.FollowBackStack]；
 *   - [onCleared] 只做最终释放（前两种情况下已拆过，这里不再重复）。
 * WebView 用 [MutableContextWrapper] 包 Activity：Activity 重建后换成新的 Activity，不持有已销毁的那个。
 */
internal open class WebContainerHolder : ViewModel() {
    // 有意持有：WebView 要跨「被盖住」保留（R1-04、共性第 28 条）。上下文是 MutableContextWrapper，每次进入组合换成当前 Activity；
    // 回退栈条目清除（含 Activity 结束）时 onCleared 拆除并置空，不会比路由活得更久
    @SuppressLint("StaticFieldLeak")
    private var wrapper: MutableContextWrapper? = null
    private var teardownAction: (() -> Unit)? = null
    private var invalidateAction: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    /** 这个实例的 WebView（拆除后为 null）。 */
    @SuppressLint("StaticFieldLeak") // 同上：随路由出栈拆除
    var webView: WebView? = null
        private set

    /** 已拆除。 */
    var tornDown: Boolean = false
        private set

    /** 页面加载失败或容器不可用：错误页盖在 WebView 上（不移除 WebView）。 */
    var failed by mutableStateOf(false)

    /** 关闭整个容器（最新一次组合给的回调）。 */
    var onClose: () -> Unit = {}

    /** 取这个实例的 WebView（第一次时用 [create] 建）；已挂在别处（上一次组合）的先摘下来。 */
    fun obtain(
        context: Context,
        create: (Context) -> WebView,
    ): WebView {
        val existing = webView
        if (existing != null) {
            wrapper?.baseContext = context
            existing.detach()
            return existing
        }
        val contextWrapper = MutableContextWrapper(context)
        wrapper = contextWrapper
        return create(contextWrapper).also { webView = it }
    }

    /**
     * 登记拆除动作：[invalidate] 在出栈时立即执行（作废在途调用、停止加载）；[destroy]（拆传输层、销毁 WebView）放到主线程的下一条
     * 消息——本容器若是被一次桥调用（nav.open 清栈、nav.close）关掉的，那次调用先按结果结算并回给页面，再拆（共性第 43 条）。
     */
    fun onTeardown(
        invalidate: () -> Unit,
        destroy: () -> Unit,
    ) {
        invalidateAction = invalidate
        teardownAction = destroy
    }

    /** 关闭容器：先拆除（立即作废调用、停止加载），再交外层出栈。 */
    fun close() {
        teardown()
        onClose()
    }

    /** 路由真正出栈时拆除；重复调用无效。 */
    fun teardown() {
        if (tornDown) return
        tornDown = true
        webView?.detach()
        invalidateAction?.invoke()
        invalidateAction = null
        teardownAction?.let { destroy -> main.post { destroy() } }
        teardownAction = null
        webView = null
        wrapper = null
    }

    /**
     * 换一份 WebView（可信容器首次没加载成、重试时按新配置重建，R3-03）：拆掉现有的（立即作废调用、停止加载，下一条消息里销毁），下一次
     * [obtain] 再建新的。容器实例仍在（不是出栈拆除）；已拆除的不动。
     */
    fun replaceWebView() {
        if (tornDown) return
        webView?.detach()
        invalidateAction?.invoke()
        invalidateAction = null
        teardownAction?.let { destroy -> main.post { destroy() } }
        teardownAction = null
        webView = null
        wrapper = null
    }

    override fun onCleared() = teardown()

    private fun View.detach() {
        (parent as? ViewGroup)?.removeView(this)
    }
}

/**
 * 容器实例与所在回退栈条目的对应（R2-03、共性第 47 条）：每个容器实例按它的 ViewModelStore（Navigation 里就是回退栈条目的；
 * Activity 重建后同一条目沿用同一个 ViewModelStore）登记。外壳把导航的回退栈交给 [FollowBackStack]：回退栈一变（popBackStack、
 * 带 popUpTo 的 navigate 返回之前就已更新），不在栈里的条目上的容器实例立即拆除。被盖住、仍在栈中的不动。只在主线程使用。
 */
object WebContainers {
    private val bound = WeakHashMap<ViewModelStore, WebContainerHolder>()

    /** 登记 [holder] 属于 [store] 所在的条目（每次进入组合都可以调，重复登记无效果）。 */
    internal fun bind(
        store: ViewModelStore,
        holder: WebContainerHolder,
    ) {
        if (!holder.tornDown) bound[store] = holder
    }

    /**
     * 回退栈现在是 [owners]：登记过、但条目已不在栈里的容器实例立即拆除。空列表（导航图还没设置，或 Activity 重建时新的
     * NavController 还没恢复回退栈）不算出栈，什么都不做。
     */
    fun retain(owners: List<ViewModelStoreOwner>) {
        if (owners.isEmpty()) return
        val live = owners.mapNotNullTo(HashSet()) { runCatching { it.viewModelStore }.getOrNull() }
        val gone = bound.entries.filter { it.key !in live }
        gone.forEach { bound.remove(it.key) }
        gone.forEach { it.value.teardown() }
    }

    /**
     * 让容器实例跟随 [backStack]（Navigation 的 `NavController.currentBackStack`）：在 `Dispatchers.Main.immediate` 上收集，
     * 回退栈在主线程上更新时就在同一条消息里拆除出栈的容器实例，不等退场过渡结束。
     */
    @Composable
    @Suppress("FunctionNaming") // 组合函数
    fun FollowBackStack(backStack: StateFlow<List<ViewModelStoreOwner>>) {
        LaunchedEffect(backStack) {
            withContext(Dispatchers.Main.immediate) { backStack.collect(::retain) }
        }
    }
}

/** 容器组合时登记自己所在的条目（[WebContainers]）。 */
@Composable
internal fun BindToBackStackEntry(holder: WebContainerHolder) {
    val store = LocalViewModelStoreOwner.current?.viewModelStore
    remember(store, holder) { store?.also { WebContainers.bind(it, holder) } }
}
