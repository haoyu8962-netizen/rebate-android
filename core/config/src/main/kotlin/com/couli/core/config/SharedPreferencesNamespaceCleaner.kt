package com.couli.core.config

import android.annotation.SuppressLint
import android.content.Context
import java.io.File

/**
 * 本 App 业务 SharedPreferences 名字的集中登记处。
 *
 * 约定：本 App 的业务 SharedPreferences 一律以 [PREFIX]（`couli.`）开头，并且**先在 [registered] 里登记再使用**。
 * 切换环境时 [SharedPreferencesNamespaceCleaner] 按登记名单清理——即使对应文件还没落盘（apply() 尚未写完）
 * 也会清掉内存里的实例；目录里实际存在的 `couli.*` 文件（含 `.xml.bak`）也会一并清理，作为漏登记的兜底。
 * 存储层实现后（TODO(APP-03)）各模块把自己的名字加到这里。
 */
object CouliPreferenceNamespaces {
    const val PREFIX = "couli."

    /** 会话相关（TODO(APP-03): 会话、令牌元数据）。 */
    const val SESSION = "couli.session"

    /** 页面与配置缓存（TODO(APP-03)）。 */
    const val CACHE = "couli.cache"

    /** 路由：pending_action（APP-02a；退出登录时由调用方 clear，切换环境时随命名空间清理）。 */
    const val ROUTER = "couli.router"

    /** 版本检查结论（APP-02c UpdateGate：最近一次成功检查、10405 强更、「暂不更新」记录）；属于连接的那套服务器，切换环境时清。 */
    const val UPDATE = "couli.update"

    /**
     * debug / staging 调试面板假数据得到的版本检查结论（APP-02c R1-03）：与 [UPDATE] 分开存，Release 不读这里，
     * 覆盖安装成 Release 后不会被假的强更结论误拦。切换环境时同样清。
     */
    const val UPDATE_DEBUG = "couli.update_debug"

    val registered: List<String> = listOf(SESSION, CACHE, ROUTER, UPDATE, UPDATE_DEBUG)

    /**
     * 本机隐私决定（APP-02b PrivacyGate：同意的决定、同意时的政策版本与点击时间、基本模式）。
     * **故意不以 [PREFIX] 开头，切换环境时不清理**：它是本机用户对本 App 隐私政策的决定，与连哪个服务器无关
     * （03 §3.6 切换环境要清的是会话、令牌、device_id、install_secret、LKG、页面缓存与 pending_action，不含同意决定）；
     * 换到新环境后由 PrivacyGate.onConsented 重新注册设备，并在新环境补报同意记录（BR-ID-12）。
     * 撤回同意（BR-ID-13）不删这个文件，而是把决定改写为基本模式；撤回要清的令牌、device_id、install_secret 在 [SESSION]
     * 命名空间（切换环境与撤回同意都会清），见 core:privacy 的 CredentialCleanerRegistry。
     */
    const val PRIVACY_DECISION = "couli_privacy"

    /**
     * 系统权限申请记录（APP-03b PermissionRequester：BR-ID-13 被拒时刻的节流记录、是否已申请过）。**故意不以 [PREFIX] 开头**：
     * 它记的是本机用户对系统授权框的决定，与连哪个服务器无关，切换环境不清；「清除缓存」也不清（03 §4.3）。
     */
    const val PERMISSION = "couli_permission"

    /**
     * 剪贴板识别（APP-07）：设备级开关「打开 App 自动识别剪贴板」与本机 hash 记录（自写内容、24 小时提示记录）。**故意不以 [PREFIX] 开头**：
     * 开关是本机用户的选择，与连哪个服务器、登录哪个账号无关（BR-ID-16），切换环境不清；hash 记录在撤回同意时由剪贴板模块清空。
     */
    const val CLIPBOARD = "couli_clipboard"

    /**
     * 金额隐藏开关（APP-08，BR-TEXT-10 细则「金额隐藏」）：本机偏好，换账号沿用、卸载即清。**故意不以 [PREFIX] 开头**：与连哪个服务器、登录哪个
     * 账号无关，切换环境不清，撤回同意也不清（不含个人信息）。
     */
    const val AMOUNT_MASK = "couli_amount_mask"

    /**
     * 订单断网缓存的「待清理」状态（APP-08 Android R3-02）：放在缓存目录之外，缓存目录故障时也能记下并在启动、读缓存前完成清理。**故意不以
     * [PREFIX] 开头**：切换环境、撤回同意本身就要清订单缓存，这个状态必须保留到订单缓存确认删完（由订单缓存自己清掉），不能被命名空间清理先抹掉。
     */
    const val ORDER_CACHE_PENDING = "couli_order_cache_pending"

    /** 钱包摘要断网缓存的「待清理」状态（APP-09，G22）：同 [ORDER_CACHE_PENDING]，故意不以 [PREFIX] 开头，由钱包缓存确认删完后自己清掉。 */
    const val WALLET_CACHE_PENDING = "couli_wallet_cache_pending"

    /**
     * 首页 LKG 的「待清理」状态（APP-11，G16）：同 [ORDER_CACHE_PENDING]，故意不以 [PREFIX] 开头，由首页 LKG 确认删完后自己清掉。
     * （公告关闭记录与弹窗计数 couli_home_prefs 不在这里：它们在撤回同意、切换环境时由首页清理器清，不属于保留项。）
     */
    const val HOME_LKG_PENDING = "couli_home_lkg_pending"

    /** 已登记、但切换环境时保留的 SharedPreferences（不在命名空间内）。 */
    val preservedAcrossEnvironmentSwitch: List<String> =
        listOf(PRIVACY_DECISION, PERMISSION, CLIPBOARD, AMOUNT_MASK, ORDER_CACHE_PENDING, WALLET_CACHE_PENDING, HOME_LKG_PENDING)

    init {
        registered.forEach { name ->
            require(name.startsWith(PREFIX)) { "SharedPreferences 名 $name 不以 $PREFIX 开头" }
        }
        preservedAcrossEnvironmentSwitch.forEach { name ->
            require(!name.startsWith(PREFIX)) { "保留的 SharedPreferences 名 $name 不能以 $PREFIX 开头（否则切换环境会被清掉）" }
        }
    }
}

/**
 * 切换环境时清理本 App 命名空间下的全部 SharedPreferences（03 §3.6）。
 *
 * 清理对象 = 登记名单 [registeredNames] ∪ 目录里实际存在的 `<前缀>*.xml` 与 `<前缀>*.xml.bak`。对每个名字：
 * 1. `getSharedPreferences(name).edit().clear().commit()`：同步清空进程内缓存的实例（含 apply() 尚未落盘的值），
 *    并等之前排队的写入完成；commit 返回 false 即失败。
 * 2. `deleteSharedPreferences(name)`：从缓存里移除并删除 `.xml` 与 `.xml.bak`；再显式删一次 `.bak` 并核对两个文件都不在。
 * 任一步失败抛 [IllegalStateException]，由调用方决定不切换 / 下次启动重试。
 *
 * 调试面板自己的选择（[DEBUG_SELECTION_PREFERENCES]）与「上次生效的环境」记录
 * （[EnvironmentStartupReconciler.STATE_PREFERENCES]）不在命名空间内，不随切换清除。
 */
class SharedPreferencesNamespaceCleaner(
    context: Context,
    private val registeredNames: Collection<String> = CouliPreferenceNamespaces.registered,
    private val namespacePrefix: String = CouliPreferenceNamespaces.PREFIX,
) : EnvironmentSwitchCleaner {
    private val appContext = context.applicationContext

    init {
        registeredNames.forEach { name ->
            require(name.startsWith(namespacePrefix)) { "登记的 SharedPreferences 名 $name 不以 $namespacePrefix 开头" }
        }
    }

    // 必须用 commit()：apply() 的异步写回可能在删除之后重新建出文件。
    @SuppressLint("ApplySharedPref")
    override fun clean(
        from: AppEnvironment,
        to: AppEnvironment,
    ) {
        val failures = mutableListOf<String>()
        namesToClean().forEach { name ->
            val cleared =
                appContext
                    .getSharedPreferences(name, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
            if (!cleared) failures += "$name: commit() 返回 false"
            appContext.deleteSharedPreferences(name)
            val file = preferenceFile(name)
            val backup = backupFile(name)
            backup.delete()
            if (file.exists() || backup.exists()) failures += "$name: 文件仍在（${file.exists()} / .bak ${backup.exists()}）"
        }
        check(failures.isEmpty()) { "清理 SharedPreferences 失败：" + failures.joinToString("；") }
    }

    /** 本次要清理的名字：登记名单 ∪ 目录里存在的命名空间文件（含只剩 .bak 的）。 */
    fun namesToClean(): List<String> = (registeredNames + namespacedPreferenceNamesOnDisk()).distinct().sorted()

    /** 目录里存在的本 App 命名空间 SharedPreferences 名（`.xml` 或 `.xml.bak`）。 */
    fun namespacedPreferenceNamesOnDisk(): List<String> =
        preferencesDirectory()
            .listFiles()
            .orEmpty()
            .filter { it.isFile }
            .mapNotNull { file ->
                when {
                    file.name.endsWith(BACKUP_SUFFIX) -> file.name.removeSuffix(BACKUP_SUFFIX)
                    file.name.endsWith(XML_SUFFIX) -> file.name.removeSuffix(XML_SUFFIX)
                    else -> null
                }
            }.filter { it.startsWith(namespacePrefix) }
            .distinct()
            .sorted()

    private fun preferencesDirectory(): File = File(appContext.applicationInfo.dataDir, "shared_prefs")

    private fun preferenceFile(name: String): File = File(preferencesDirectory(), name + XML_SUFFIX)

    private fun backupFile(name: String): File = File(preferencesDirectory(), name + BACKUP_SUFFIX)

    companion object {
        const val DEBUG_SELECTION_PREFERENCES = "couli_debug_environment"
        private const val XML_SUFFIX = ".xml"
        private const val BACKUP_SUFFIX = ".xml.bak"
    }
}
