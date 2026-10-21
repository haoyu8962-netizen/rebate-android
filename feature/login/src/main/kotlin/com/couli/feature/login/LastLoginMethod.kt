package com.couli.feature.login

import com.couli.core.privacy.CredentialCleanerRegistry

/*
 * 上次登录方式（APP-17a；BR-ID-04「上次登录」标签，鸿蒙 HM-13a C06–C09；G05、G22、G24、G29；Q5）。只记方式、不记号码与账号：
 * 会话真实保存成功、尝试仍有效时写（受限登录也写）；撤回同意时清（CredentialCleanerRegistry）；登出、注销申请、切换环境都不清。
 */

/** 方式的取值（Android：不含 apple、鸿蒙的 huawei_one_tap；Q5）。 */
object LastLoginMethod {
    const val SMS = "sms"
    const val WECHAT = "wechat"
    const val HUAWEI = "huawei"
    const val CARRIER_ONE_TAP = "carrier_one_tap"

    val ALL: Set<String> = setOf(SMS, WECHAT, HUAWEI, CARRIER_ONE_TAP)

    /** 撤回同意清理项的名字（CredentialCleanerRegistry）。 */
    const val CLEANER = "last_login_method"
}

/** 持久化介质（App 给 SharedPreferences 实现；单测给内存实现）。 */
interface LastLoginMethodBackend {
    fun read(): String?

    /** [method] 为 null 是删除；返回 false 表示没能落盘。 */
    fun write(method: String?): Boolean
}

/**
 * 上次登录方式的存储。修订号 [revision]（G29、坑 24）：成功写入、清除时加 1；登录尝试开始时捕获，成功后带着捕获值 [record]——期间被撤回
 * 清掉或写进了更新的方式，修订号已变，旧尝试写不回来。清除先推进修订号再删：删除失败也已作废旧写者，并向清理入口抛错（不吞）。
 */
class LastLoginMethodStore(
    private val backend: LastLoginMethodBackend,
) {
    private val lock = Any()

    @Volatile
    var revision: Long = 0L
        private set

    /** 最近一次清除没落盘：读一律按没有（不把撤回前的方式当标签显示），下次清除或新写入成功后恢复。 */
    @Volatile
    private var clearFailed = false

    /** 已登记进撤回同意的清理入口（App 启动接线核对用）。 */
    @Volatile
    var cleanerInstalled: Boolean = false
        private set

    /** 不在取值集合里的旧存储（含号码等不该存的内容）读为 null。 */
    fun read(): String? =
        synchronized(lock) {
            if (clearFailed) null else backend.read()?.takeIf { it in LastLoginMethod.ALL }
        }

    /** 写入 [method]：不在取值集合、[expectedRevision] 已过期、落盘失败都不写并返回 false（不推进修订号）。 */
    fun record(
        method: String,
        expectedRevision: Long = revision,
    ): Boolean =
        synchronized(lock) {
            if (method !in LastLoginMethod.ALL || expectedRevision != revision) return@synchronized false
            if (!backend.write(method)) return@synchronized false
            revision += 1
            clearFailed = false
            true
        }

    /** 清除（撤回同意）：先推进修订号作废在途的旧写者，再删；删不掉抛错，交给 CredentialCleanerRegistry / PrivacyGate 保留待清理并重试。 */
    fun clear() {
        synchronized(lock) {
            revision += 1
            clearFailed = true
            check(backend.write(null)) { "last login method not cleared" }
            clearFailed = false
        }
    }

    /** 登记进撤回同意的清理入口（只进撤回清理；切换环境、登出不清，G22、G24）。 */
    fun installCleaner(registry: CredentialCleanerRegistry) {
        registry.register(LastLoginMethod.CLEANER, ::clear)
        cleanerInstalled = true
    }
}

/**
 * 登录页的登录方式条目（C08）：[providers] 实际显示的第三方按钮；[lastMethod] 只在那个方式的按钮确实显示时才给（其余为 null，不挂标签）。
 * 当前 Android 短信页没有一键登录首屏，sms / carrier_one_tap 没有可挂的按钮。
 */
data class LoginPageEntries(
    val providers: List<String>,
    val lastMethod: String?,
) {
    companion object {
        fun of(
            providers: List<String>,
            lastMethod: String?,
        ): LoginPageEntries = LoginPageEntries(providers, lastMethod?.takeIf { it in providers })
    }
}
