pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "rebate-android"

// 依赖方向：app → feature → core → core:generated（03 §3.1）。
// feature 之间不得互相依赖：couli.android.library 插件对 :feature:* 做检查，违反即构建失败。
include(":app")

include(":core:generated")
include(":core:designsystem")
listOf(
    "network",
    "session",
    "config",
    "router",
    "tradejump",
    "bridge",
    "privacy",
    "tracker",
    "clipboard",
    "orders",
    "push",
    "storage",
).forEach { include(":core:$it") }

listOf(
    "home",
    "search",
    "product",
    "agent",
    "orders",
    "wallet",
    "me",
    "auth",
    "login",
    "web",
    "trade",
).forEach { include(":feature:$it") }
