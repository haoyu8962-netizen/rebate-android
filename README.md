# rebate-android

凑狸 Android 原生壳。规则见 `AGENTS.md`。

## 本机环境

- JDK 21：`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`
- Android SDK：`ANDROID_HOME=~/Library/Android/sdk`（compileSdk / targetSdk 36，minSdk 26）
- Gradle 一律用 `./gradlew`（wrapper 9.8.0）；依赖版本全在 `gradle/libs.versions.toml`

## 常用命令

| 用途 | 命令 |
| --- | --- |
| 本机 CI（全部） | `tools/ci/verify.sh` |
| 本机 CI（快速：生成物检查 + assembleDebug） | `tools/ci/verify.sh --fast` |
| 重建契约快照（换锁定版本时） | `REBATE_PLATFORM_DIR=… COULI_DIR=… tools/contracts/sync.sh` |
| 生成代码 / 查漂移 | `python3 tools/codegen/gen.py` / `python3 tools/codegen/gen.py --check` |
| 静态检查 | `./gradlew ktlintCheck detekt lint`（`detekt` 带自定义规则 `couliRules`） |
| 自动格式化 | `./gradlew ktlintFormat` |
| 单元测试 | `./gradlew testDebugUnitTest` |
| UI 冒烟（需模拟器） | `./gradlew connectedDebugAndroidTest` |
| Release 制品扫描 | `tools/ci/release-scan.sh app/build/outputs/apk/release/app-release-unsigned.apk`；`--self-test` 证明扫描有效 |

## 构建类型（03 §3.6）

| 构建类型 | 默认环境 | 调试能力 | 应用名 | 说明 |
| --- | --- | --- | --- | --- |
| `debug` | test（可切换） | 有（`src/debugFeatures`） | 凑狸 | 开发机 |
| `staging` | staging（可切换） | 有（`src/debugFeatures`） | 凑狸测试 | 不可调试；versionName 带 `-staging` |
| `release` | prod（不能切换） | 无 | 凑狸 | R8；未签名（签名见 needs.md N-04） |

三套同一个 applicationId `com.couli.app`，覆盖安装。非 prod 地址、调试面板、`debug_only` 路由只在
`src/debugFeatures`（debug 与 staging 共用），release 制品里不存在，由 `tools/ci/release-scan.sh` 检查。

## 模块

`app → feature/* → core/* → core:generated`；feature 之间不得互相依赖（构建期检查）。
convention 插件在 `build-logic/convention`：`couli.android.application`、`couli.android.library`、
`couli.android.compose`、`couli.kotlin.library`、`couli.lint`。
