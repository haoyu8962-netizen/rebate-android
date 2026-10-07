#!/bin/bash
# 本机 CI 入口（APP-01；03 §11）。任一步失败即退出非零，最后打印各步结果汇总。
#
# 用法：
#   tools/ci/verify.sh          全部步骤
#   tools/ci/verify.sh --fast   只跑到生成物检查与一次 assembleDebug
#
# 步骤：
#   1. 快照与 contract.lock 的哈希一致
#   2. python3 tools/codegen/gen.py --check（生成物无漂移）
#   3. ./gradlew ktlintCheck detekt lint（含自定义规则 couliRules；0 问题）
#   4. 源码规则扫描器单测（./gradlew -p build-logic :convention:test：字符串模板嵌套、原始字符串、字符字面量、注释）；
#      lint 违反样例必须报错（tools/ci/lint-fixtures/ 每条规则至少一个；含插值里的中文、插值里的 SDK 初始化）
#   5. ./gradlew assembleDebug assembleStaging assembleRelease assembleDebugAndroidTest
#   6. ./gradlew testDebugUnitTest（含 Robolectric、ContractConformanceTest 与 DS-01 格式向量 FormatVectorTest）；
#      ./gradlew :core:designsystem:verifyRoborazziDebug（DS-01 组件快照与入库基准图 src/test/snapshots 比对）
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

check_lock() {
  python3 - "$ROOT" <<'PY'
import hashlib, json, os, sys
root = sys.argv[1]
lock = json.load(open(os.path.join(root, "contract.lock"), encoding="utf-8"))
snapshot = os.path.join(root, "contracts", "snapshot")
actual = {}
for directory, _, names in os.walk(snapshot):
    for name in names:
        full = os.path.join(directory, name)
        rel = os.path.relpath(full, snapshot).replace(os.sep, "/")
        actual[rel] = hashlib.sha256(open(full, "rb").read()).hexdigest()
expected = lock.get("files", {})
bad = sorted(k for k in set(actual) | set(expected) if actual.get(k) != expected.get(k))
for commit_key in ("rebate_platform", "design_tokens"):
    if len(lock[commit_key]["commit"]) != 40:
        print(f"contract.lock 的 {commit_key}.commit 不是 40 位", file=sys.stderr)
        sys.exit(1)
if bad:
    print("快照与 contract.lock 不一致：" + ", ".join(bad), file=sys.stderr)
    sys.exit(1)
print(f"快照 {len(actual)} 个文件与 contract.lock 一致")
PY
}

step "快照与 contract.lock 一致" check_lock
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
expect_failure "违反样例：PrivacyGate 之外初始化 SDK" "sdk-init-only-in-privacy-gate" \
  $GRADLE couliRules "-Pcouli.rules.root=$FIXTURES/sdk-init-only-in-privacy-gate"
expect_failure "违反样例：字符串插值里初始化 SDK" "sdk-init-only-in-privacy-gate" \
  $GRADLE couliRules "-Pcouli.rules.root=$FIXTURES/sdk-init-only-in-privacy-gate-interpolation"
expect_failure "违反样例：feature 依赖另一个 feature" "feature-to-feature-dependency" \
  $GRADLE help "-Pcouli.fixture.featureDependency=$FIXTURES/feature-to-feature-dependency/feature-home-depends-on-search.gradle.kts"

step "构建 debug / staging / release 与 androidTest" \
  $GRADLE assembleDebug assembleStaging assembleRelease assembleDebugAndroidTest
step "单元测试（testDebugUnitTest，含 Robolectric 与格式向量）" $GRADLE testDebugUnitTest
step "组件快照测试（Roborazzi verifyRoborazziDebug）" $GRADLE :core:designsystem:verifyRoborazziDebug
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
