package com.couli.app

import android.content.Context
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentSwitchCleanerRegistry
import com.couli.core.config.SharedPreferencesNamespaceCleaner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** App 级依赖。 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /**
     * 切换环境时的清理入口注册表（03 §3.6）。先登记本 App 的 SharedPreferences 命名空间；
     * 会话、LKG、Keystore 等模块实现后各自注册。
     */
    @Provides
    @Singleton
    fun environmentSwitchCleaners(
        @ApplicationContext context: Context,
    ): EnvironmentSwitchCleanerRegistry =
        EnvironmentSwitchCleanerRegistry().apply {
            register(SharedPreferencesNamespaceCleaner(context))
        }

    /**
     * 环境闸门：对账动作按构建类型不同（debug / staging 按显式选择或构建默认，release 只有 prod；
     * 见各源码集的 reconcileEnvironmentAtStartup）。Application.onCreate 调 settleAtStartup()。
     */
    @Provides
    @Singleton
    fun environmentGate(
        @ApplicationContext context: Context,
        cleaners: EnvironmentSwitchCleanerRegistry,
    ): EnvironmentGate = EnvironmentGate { reconcileEnvironmentAtStartup(context, cleaners) }
}
