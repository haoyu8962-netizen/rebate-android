package com.couli.app

import android.content.Context
import com.couli.core.config.DebugCapability
import com.couli.core.config.EnvironmentSwitchCleanerRegistry
import com.couli.core.config.EnvironmentSwitcher
import java.io.File

/**
 * 直接读写假服务开关文件（不经环境闸门）。只给调试面板的开关按钮用（显示与切换开关本身）；任何替身判断「能不能交假数据」必须用
 * [DebugMockGate.of]（F-X-02）。用到的地方必须显式 `@OptIn(RawFakeServerSwitch::class)`，否则编译失败。
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "直接读假服务开关会绕过环境闸门（F-X-02）：替身一律用 DebugMockGate.of(context)",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
internal annotation class RawFakeServerSwitch

/**
 * Debug / Staging 替身的统一闸门（F-X-02；APP-11 共性第 10、16 条；FU-AND-0714）：三项同时成立才放行——
 * 1. 假服务开关文件在（files/<[FAKE_LOGIN_SERVER_MARKER]>，文件名只在本文件可见）；
 * 2. 当前环境允许 MOCK_DATA（[DebugCapability.MOCK_DATA]：prod 一律不允许）；
 * 3. 环境闸门已就绪（[environmentReady]：启动对账成功、不在运行中切换；AppModule 在建环境闸门时接上）。
 *
 * **每次调用重新判断**，不在构造时判一次：替身在实际传输 / 交付入口调用它，异步等待之后交付之前再调用一次（已经拿到手的旧实例、
 * 等待期间切到 prod 都不再交假数据）。任何一项读取出错按关闭。
 *
 * 开关文件只在本文件可见（private）：别处拿不到开关、不能绕过闸门（编译期保护，代替源码扫描规则，见 FU-AND-0714 audit.md）。
 */
internal object DebugMockGate {
    /** 环境闸门已就绪（启动对账成功且不在切换中）。没有接线（单测直接构造替身）时按就绪。 */
    @Volatile
    var environmentReady: () -> Boolean = { true }

    /** 进程内的闸门：每次调用现读开关文件、当前环境与环境闸门。 */
    fun of(context: Context): () -> Boolean {
        val app = context.applicationContext
        val switch = switchFile(app)
        return { open(switchOn = switch::exists, mockDataAllowed = { mockDataAllowed(app) }) }
    }

    /** 闸门判定（三项都成立；读取出错按关闭）。 */
    fun open(
        switchOn: () -> Boolean,
        mockDataAllowed: () -> Boolean,
    ): Boolean = runCatching { switchOn() && mockDataAllowed() && environmentReady() }.getOrDefault(false)

    /** 当前环境允许 MOCK_DATA 且环境闸门已就绪（不看开关文件：版本检查假结果、首页 / Agent 替身的第二道核对用）。 */
    fun mockDataAllowed(context: Context): Boolean =
        runCatching {
            EnvironmentSwitcher(context.applicationContext, buildDefaultEnvironment(), EnvironmentSwitchCleanerRegistry())
                .capabilities
                .isAvailable(DebugCapability.MOCK_DATA) && environmentReady()
        }.getOrDefault(false)

    /** 假服务状态文件所在目录（与开关文件同目录：files/）。 */
    fun stateDir(context: Context): File = context.applicationContext.filesDir

    /** 开关文件此刻在不在（只给调试面板显示开关状态）。 */
    @RawFakeServerSwitch
    fun switchOn(context: Context): Boolean = switchFile(context.applicationContext).exists()

    /** 打开 / 关闭开关文件（调试面板的按钮），返回之后的状态。 */
    @RawFakeServerSwitch
    fun setSwitch(
        context: Context,
        on: Boolean,
    ): Boolean {
        val file = switchFile(context.applicationContext)
        if (on) runCatching { file.createNewFile() } else file.delete()
        return file.exists()
    }

    /** 假服务开关文件（只在本文件可见）。 */
    private fun switchFile(context: Context): File = File(context.applicationContext.filesDir, FAKE_LOGIN_SERVER_MARKER)
}

/** 假服务的开关文件名，也是 release-scan 查的唯一标记（Release 制品里不得出现）。只在本文件可见：别处拿不到开关、不能绕过闸门（F-X-02）。 */
private const val FAKE_LOGIN_SERVER_MARKER = "COULI_FAKE_LOGIN_SERVER_APP05A_7f3a91c2"
