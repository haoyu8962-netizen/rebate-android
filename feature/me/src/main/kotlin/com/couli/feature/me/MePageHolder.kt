package com.couli.feature.me

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 页面执行器的保存处（Android R1-08，03 §10.4，共性第 24 条）：放在回退栈条目的 ViewModel 里——配置重建（折叠 / 展开、窗口大小变化、字号变化）只重建界面，
 * 执行器与它的表单草稿、子页、确认框原样保留；真正出栈（[onCleared]）或身份变了（换账号、退出、撤回同意：按新身份重建执行器）才调执行器的离开出口清理
 * 敏感内容。执行器在构造时固定身份（APP-09 第 65 条），这里只按身份比较、不重新绑定。
 */
internal class MePageHolder : ViewModel() {
    private var identity: Any? = null
    private var controller: Any? = null
    private var leave: () -> Unit = {}

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> controller(
        identity: Any,
        create: () -> T,
        leave: (T) -> Unit,
    ): T {
        val existing = controller
        if (existing != null && this.identity == identity) return existing as T
        // 身份变了：旧执行器作废（清草稿、撤销读请求），按新身份重建
        this.leave()
        val made = create()
        this.identity = identity
        controller = made
        this.leave = { leave(made) }
        return made
    }

    override fun onCleared() {
        leave()
        leave = {}
        controller = null
        identity = null
        super.onCleared()
    }
}

/** 取本回退栈条目里、按 [identity] 的页面执行器（[key] 区分同一条目里的不同页面）。 */
@Composable
internal fun <T : Any> rememberPageController(
    key: String,
    identity: Any,
    create: () -> T,
    leave: (T) -> Unit,
): T {
    val holder: MePageHolder = viewModel(key = "me_page_$key") { MePageHolder() }
    return remember(holder, identity) { holder.controller(identity, create, leave) }
}
