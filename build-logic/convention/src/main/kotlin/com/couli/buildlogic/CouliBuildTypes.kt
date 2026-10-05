package com.couli.buildlogic

/** 三套构建类型的名字（03 §3.6）：debug、staging、release，三套 applicationId 相同。 */
object CouliBuildTypes {
    const val DEBUG = "debug"
    const val STAGING = "staging"
    const val RELEASE = "release"

    /** 调试能力只在这两个构建类型里编译（共享源码目录 src/debugFeatures）。 */
    val withDebugFeatures: Set<String> = setOf(DEBUG, STAGING)

    const val DEBUG_FEATURES_KOTLIN_DIR = "src/debugFeatures/kotlin"
    const val DEBUG_FEATURES_RES_DIR = "src/debugFeatures/res"
}
