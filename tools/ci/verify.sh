#!/bin/bash
# 本机 CI 入口（APP-01；03 §11）。任一步失败即退出非零，最后打印各步结果汇总。
#
# 用法：
#   tools/ci/verify.sh          全部步骤
#   tools/ci/verify.sh --fast   只跑到生成物检查与一次 assembleDebug
#
# 步骤：
#   1. 快照与 contract.lock 一致：按锁定提交重建逐字节比较 + lock files 哈希（tools/contracts/sync.sh --check；
#      取不到 rebate-platform / couli 即失败，CT-LOCK-01）
#   2. python3 tools/codegen/gen.py --check（生成物无漂移）
#   3. ./gradlew ktlintCheck detekt lint（含自定义规则 couliRules；0 问题）
#   4. 源码规则扫描器单测（./gradlew -p build-logic :convention:test：字符串模板嵌套、原始字符串、字符字面量、注释）；
#      lint 违反样例必须报错（tools/ci/lint-fixtures/ 每条规则至少一个；含插值里的中文、插值里的 SDK 初始化、多美元符号插值、
#      扫不完整的文件与空扫描根——F-X-07 fail-closed；Java 块注释不嵌套——FU-AND-0715 R1-04；Java 单独回车结束行注释——R2-02，
#      运行前先核对样例里确实有单独回车字节；FU-AND-0717 F-X-07 补充 / 补充二：转义写的中文、块注释边界与反引号标识符里的 SDK 初始化、
#      Unicode 行终止符按扫不完整——运行前同样核对样例里确实有 U+2028 字节）
#   5. ./gradlew assembleDebug assembleStaging assembleRelease assembleDebugAndroidTest
#   6. ./gradlew testDebugUnitTest（含 Robolectric、ContractConformanceTest 与 DS-01 格式向量 FormatVectorTest）；
#      ./gradlew :core:designsystem:verifyRoborazziDebug :feature:home:verifyRoborazziDebug :feature:agent:verifyRoborazziDebug（DS-01 组件快照、APP-11 首页 golden 页、
#      APP-10 Agent 页 15 个流 fixture 与构造样例，与入库基准图 src/test/snapshots 比对）
#   6b. tools/ci/ui-tests.sh：feature:web 真实 WebView 仪器测试（installDebugAndroidTest + am instrument，以「OK (N tests)」为准），
#      含一致性页 ConformancePageTest（逐项断言 window.__RESULT__）；需要一台在线的设备或模拟器，没有即失败
#   7. python3 tools/codegen/test_gen.py（生成器命名规则；含用 Gradle 缓存里的 Kotlin 编译器真编译探针生成物，
#      所以放在构建之后，COULI_GEN_REQUIRE_KOTLINC=1：找不到编译器即失败，不跳过）
#   8. tools/ci/release-scan.sh <release apk>、--self-test（debug / staging 命中、release 不命中；含组件目录页标记）
#      与 --self-test-tools（非 ZIP、注入返回 127 的假 unzip / strings / aapt2 / python3 / grep 时必须以 2 退出）
# 兼容 macOS 自带 bash 3.2；路径加引号（仓库路径含中文）。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT" || exit 1

FAST=0
for arg in "$@"; do
  case "$arg" in
    --fast) FAST=1 ;;
    *)
      echo "verify.sh: 未知参数 ${arg}" >&2
      exit 2
      ;;
  esac
done

# 本机默认工具位置（已设置则沿用）
if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
# 不在 tools/codegen/ 里留下 __pycache__
export PYTHONDONTWRITEBYTECODE=1

GRADLE="./gradlew --console=plain"
LOG_DIR="$ROOT/build/verify"
mkdir -p "$LOG_DIR"
SUMMARY=""
FAILED=0

record() {
  SUMMARY="${SUMMARY}$1"$'\n'
}

finish() {
  echo
  echo "================ verify.sh 汇总 ================"
  printf '%s' "$SUMMARY"
  if [ "$FAILED" -ne 0 ]; then
    echo "结果：失败（日志在 build/verify/）"
    exit 1
  fi
  echo "结果：全部通过"
  exit 0
}

# step <名字> <命令...>：命令必须成功
step() {
  local name="$1"
  shift
  local log
  log="$LOG_DIR/$(echo "$name" | tr -c 'A-Za-z0-9' '_').log"
  echo
  echo "==> ${name}"
  if "$@" 2>&1 | tee "$log"; then
    record "  通过  ${name}"
  else
    record "  失败  ${name}"
    FAILED=1
    finish
  fi
}

# expect_failure <名字> <必须出现的规则标识> <命令...>：命令必须失败，且输出里有规则标识
expect_failure() {
  local name="$1"
  local marker="$2"
  shift 2
  local log
  log="$LOG_DIR/$(echo "$name" | tr -c 'A-Za-z0-9' '_').log"
  echo
  echo "==> ${name}（预期失败）"
  if "$@" > "$log" 2>&1; then
    echo "违反样例没有被拦：$*" >&2
    record "  失败  ${name}（违反样例没有被拦）"
    FAILED=1
    finish
  elif grep -q -- "$marker" "$log"; then
    echo "已被拦：$(grep -m1 -- "$marker" "$log")"
    record "  通过  ${name}（被拦，${marker}）"
  else
    tail -30 "$log" >&2
    record "  失败  ${name}（失败了，但不是因为 ${marker}）"
    FAILED=1
    finish
  fi
}

# CT-LOCK-01：contract.lock 与快照一致——必须按锁定提交重建、逐字节比较，并核对 lock files 哈希（tools/contracts/sync.sh --check）。
# 取不到上游仓库或其中没有锁定提交就失败，不退化成只比哈希（只比哈希用 tools/contracts/sync.sh --check-hashes-only 单独跑，
# 不算本步通过；与鸿蒙 CT-LOCK-01 R1-02 同口径）。上游仓库按顺序推导：
#   1. 环境变量 REBATE_PLATFORM_DIR / COULI_DIR（设了就只用它，不对则失败）；
#   2. lock 里的相对路径，相对本仓库根；
#   3. 同一相对路径，相对主检出（git worktree 的公共 .git 所在目录；worktree 下 2 不成立时用它）；
#   4. 本机固定位置 /Users/zhixing/我的项目/rebate-platform、/Users/zhixing/我的项目/couli。
lock_field() {
  python3 -c 'import json,sys
n=json.load(open(sys.argv[1],encoding="utf-8"))
for p in sys.argv[2].split("."): n=n[p]
print(n)' "$ROOT/contract.lock" "$1"
}

is_git_repo() {
  [ -n "$1" ] && [ -d "$1" ] && git -C "$1" rev-parse --git-dir >/dev/null 2>&1
}

# $1 环境变量值 $2 lock 相对路径 $3 本机固定位置；输出仓库绝对路径，取不到输出空串
resolve_upstream() {
  local override="$1" rel="$2" fixed="$3" main_root="" cand=""
  if [ -n "$override" ]; then
    if is_git_repo "$override"; then (cd "$override" && pwd); fi
    return 0
  fi
  main_root="$(git -C "$ROOT" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)"
  main_root="${main_root%/.git}"
  for cand in "$ROOT/$rel" "${main_root:+$main_root/$rel}" "$fixed"; do
    if is_git_repo "$cand"; then
      (cd "$cand" && pwd)
      return 0
    fi
  done
  return 0
}

# 必须在父 shell 导出；step 的命令通过管道执行，在 check_lock 内 export 会丢失。
dt_dir="$(resolve_upstream "${COULI_DIR:-}" "$(lock_field design_tokens.repo)" "/Users/zhixing/我的项目/couli")"
export COULI_DIR="$dt_dir"

check_lock() {
  local rp_dir
  rp_dir="$(resolve_upstream "${REBATE_PLATFORM_DIR:-}" "$(lock_field rebate_platform.repo)" "/Users/zhixing/我的项目/rebate-platform")"
  if [ -z "$rp_dir" ] || [ -z "$dt_dir" ]; then
    echo "取不到上游仓库（rebate-platform：${rp_dir:-未找到}；couli：${dt_dir:-未找到}），无法按锁定提交核对快照。" >&2
    echo "请设置 REBATE_PLATFORM_DIR=/path/to/rebate-platform COULI_DIR=/path/to/couli 后重跑；" >&2
    echo "只想核对 lock files 哈希可单独运行 tools/contracts/sync.sh --check-hashes-only（不算完整一致性检查）。" >&2
    return 1
  fi
  echo "rebate-platform：$rp_dir"
  echo "couli：$dt_dir"
  REBATE_PLATFORM_DIR="$rp_dir" COULI_DIR="$dt_dir" "$ROOT/tools/contracts/sync.sh" --check | grep -v '^  ' || return 1
  echo "锁定提交 $(lock_field rebate_platform.commit | cut -c1-12) 重建逐字节相同、files 哈希相同"
}

step "快照与 contract.lock 一致（按锁定提交重建核对）" check_lock
step "生成物无漂移（gen.py --check）" python3 tools/codegen/gen.py --check

if [ "$FAST" -eq 1 ]; then
  step "assembleDebug" $GRADLE assembleDebug
  finish
fi

step "ktlint + detekt + Android Lint + 自定义规则" $GRADLE ktlintCheck detekt lint couliRules
step "源码规则扫描器单测（build-logic KotlinSourceScannerTest）" $GRADLE -p build-logic :convention:test

FIXTURES="tools/ci/lint-fixtures"
expect_failure "违反样例：feature 字符串含中文" "no-cjk-string-in-feature" \
  $GRADLE couliRules "-Pcouli.rules.root=$FIXTURES/no-cjk-string-in-feature"
expect_failure "违反样例：feature 字符串插值里的中文" "no-cjk-string-in-feature" \
  $GRADLE couliRules "-Pcouli.rules.root=$FIXTURES/no-cjk-string-in-feature-interpolation"
expect_failure "违反样例：多美元符号插值字符串里的中文（F-AND-02）" "no-cjk-string-in-feature" \
  $GRADLE couliRules "-Pcouli.rules.root=$FIXTURES/no-cjk-string-in-feature-multi-dollar"
expect_failure "违反样例：扫不完整的文件（未闭合原始字符串，F-X-07 fail-closed）" "scan-incomplete" \
  $GRADLE couliRules "-Pcouli.rules.root=$FIXTURES/scan-incomplete"
expect_failure "违反样例：扫描根下没有源码（F-X-07 fail-closed）" "scan-incomplete" \
  $GRADLE couliRules "-Pcouli.rules.root=$FIXTURES/feature-to-feature-dependency"
expect_failure "违反样例：PrivacyGate 之外初始化 SDK" "sdk-init-only-in-privacy-gate" \
  $GRADLE couliRules "-Pcouli.rules.root=$FIXTURES/sdk-init-only-in-privacy-gate"
expect_failure "违反样例：字符串插值里初始化 SDK" "sdk-init-only-in-privacy-gate" \
  $GRADLE couliRules "-Pcouli.rules.root=$FIXTURES/sdk-init-only-in-privacy-gate-interpolation"
expect_failure "违反样例：Java 块注释不嵌套，注释结束后的 SDK 初始化（FU-AND-0715 R1-04）" "sdk-init-only-in-privacy-gate" \
  $GRADLE couliRules "-Pcouli.rules.root=$FIXTURES/sdk-init-only-in-privacy-gate-java-comment"
# R2-02：样例文件必须真的含单独回车（CR 后面不是 LF）；被换行转换改掉的话这条样例就测不到东西，按失败处理
lone_cr_fixture() {
  python3 - "$FIXTURES/sdk-init-only-in-privacy-gate-java-lone-cr/app/src/main/java/com/couli/app/LoneCrInit.java" <<'PY'
import re, sys
data = open(sys.argv[1], "rb").read()
count = len(re.findall(rb"\r(?!\n)", data))
print(f"单独回车 {count} 处")
sys.exit(0 if count > 0 else 1)
PY
}
step "违反样例含单独回车字节（R2-02）" lone_cr_fixture
expect_failure "违反样例：Java 行注释后单独回车，回车之后的 SDK 初始化（FU-AND-0715 R2-02）" "LoneCrInit.java:12: .sdk-init-only-in-privacy-gate" \
  $GRADLE couliRules "-Pcouli.rules.root=$FIXTURES/sdk-init-only-in-privacy-gate-java-lone-cr"
expect_failure "违反样例：Unicode 转义写的中文与字符字面量（FU-AND-0717 F-X-07 补充二）" "EscapedCjk.kt:7: .no-cjk-string-in-feature" \
  $GRADLE couliRules "-Pcouli.rules.root=$FIXTURES/no-cjk-string-in-feature-escaped"
expect_failure "违反样例：块注释边界与反引号标识符里的 SDK 初始化（FU-AND-0717 F-X-07 补充）" "CommentBoundaryInit.kt:11: .sdk-init-only-in-privacy-gate" \
  $GRADLE couliRules "-Pcouli.rules.root=$FIXTURES/sdk-init-only-in-privacy-gate-comment-boundary"
# FU-AND-0717：样例文件必须真的含 U+2028 字节（被编辑器改掉的话这条样例就测不到东西，按失败处理）
line_separator_fixture() {
  python3 - "$FIXTURES/scan-incomplete-unicode-line-terminator/feature/home/src/main/kotlin/com/couli/feature/home/LineSeparator.kt" <<'PY2'
import sys
data = open(sys.argv[1], "rb").read()
count = data.count("\u2028".encode("utf-8"))
print(f"U+2028 {count} 处")
sys.exit(0 if count > 0 else 1)
PY2
}
step "违反样例含 U+2028 字节（FU-AND-0717）" line_separator_fixture
expect_failure "违反样例：Unicode 行终止符（U+2028，FU-AND-0717 F-X-07 补充二）" "LineSeparator.kt:6: .scan-incomplete" \
  $GRADLE couliRules "-Pcouli.rules.root=$FIXTURES/scan-incomplete-unicode-line-terminator"
expect_failure "违反样例：feature 依赖另一个 feature" "feature-to-feature-dependency" \
  $GRADLE help "-Pcouli.fixture.featureDependency=$FIXTURES/feature-to-feature-dependency/feature-home-depends-on-search.gradle.kts"

step "DS-02 官方素材状态（未到位可见挂起）" python3 tools/ci/ds02/check_oauth_assets.py
step "DS-02 守卫违反样例（13 类系统控件只警告）" python3 tools/ci/ds02/check_guard.py

step "构建 debug / staging / release 与 androidTest" \
  $GRADLE assembleDebug assembleStaging assembleRelease assembleDebugAndroidTest
step "单元测试（testDebugUnitTest，含 Robolectric 与格式向量）" $GRADLE testDebugUnitTest
step "组件快照测试（Roborazzi verifyRoborazziDebug；含首页 golden 页 APP-11 G29、Agent 页流 fixture 快照 APP-10 G32）" $GRADLE :core:designsystem:verifyRoborazziDebug :feature:home:verifyRoborazziDebug :feature:agent:verifyRoborazziDebug
step "DS-02 目录组件浅 / 深快照（仅 golden 用例）" $GRADLE :app:verifyRoborazziDebug --tests "*Ds02CatalogSnapshotTest"
step "首页视觉整页浅 / 深快照（APP-11v：Main-v3 / HomeGuest-v3 / MainDark-v3 / 200% / 840）" $GRADLE :app:verifyRoborazziDebug --tests "*HomeVisualSnapshotTest"
step "商品详情视觉整页浅 / 深快照（APP-06v-a：Product-v2 / ProductJd / ProductDark / ProductOffShelf-v3 / 无稿状态 / 200% / 840）" $GRADLE :app:verifyRoborazziDebug --tests "*ProductVisualSnapshotTest"
# APP-04（Android 第 2 轮评审 R2-05、共性第 44、55 条）：真实系统 WebView 仪器测试，含加载锁定 H5 一致性页、逐项断言
# window.__RESULT__ 的 ConformancePageTest。以 am instrument 输出的「OK (N tests)」为唯一成功判据；任何失败、没有在线的设备
# 或模拟器都让本步失败（不跳过）
step "真实 WebView 仪器测试与一致性页（tools/ci/ui-tests.sh）" tools/ci/ui-tests.sh

step "生成器命名规则测试（test_gen.py，含探针编译）" env COULI_GEN_REQUIRE_KOTLINC=1 python3 tools/codegen/test_gen.py -v

RELEASE_APK="app/build/outputs/apk/release/app-release-unsigned.apk"
DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
STAGING_APK="app/build/outputs/apk/staging/app-staging.apk"
step "Release 制品扫描" tools/ci/release-scan.sh "$RELEASE_APK"
step "Release 制品扫描自检（debug / staging 命中、release 不命中）" \
  tools/ci/release-scan.sh --self-test "$DEBUG_APK" "$RELEASE_APK" "$STAGING_APK"
step "Release 制品扫描工具失败自检（工具失败必须退出 2）" tools/ci/release-scan.sh --self-test-tools "$RELEASE_APK"

finish
