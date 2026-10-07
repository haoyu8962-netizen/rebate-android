#!/bin/bash
# feature:web 真实 WebView 仪器测试（APP-04；F-AND-13、Android 第 2 轮评审 R2-05）：两类容器的仪器测试，以及加载锁定的 H5 一致性页、
# 逐项断言 window.__RESULT__ 的 ConformancePageTest。
#
# 用法：
#   tools/ci/ui-tests.sh                   全部 feature:web 仪器测试
#   tools/ci/ui-tests.sh --class <类全名>   只跑一个测试类（例：com.couli.feature.web.ConformancePageTest）
#   tools/ci/ui-tests.sh --probe            只检查有没有在线的设备 / 模拟器（有 0，没有 3）
#
# 不用 connectedDebugAndroidTest（装包失败时它仍报 BUILD SUCCESSFUL，F-AND-13）：installDebugAndroidTest 之后用 am instrument 直接跑，
# 以输出里的「OK (N tests)」为唯一成功判据，其余一律失败（退出 1）。没有在线设备时退出 3。跑之前先唤醒、解锁屏幕。
# 兼容 macOS 自带 bash 3.2；路径加引号（仓库路径含中文）。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT" || exit 1

if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
RUNNER="com.couli.feature.web.test/androidx.test.runner.AndroidJUnitRunner"
LOG_DIR="$ROOT/build/verify"
mkdir -p "$LOG_DIR"

CLASS=""
PROBE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --probe) PROBE=1 ;;
    --class)
      shift
      CLASS="${1:-}"
      ;;
    *)
      echo "ui-tests.sh: 未知参数 $1" >&2
      exit 2
      ;;
  esac
  shift
done

online() {
  [ -x "$ADB" ] && [ "$("$ADB" get-state 2>/dev/null)" = "device" ]
}

if ! online; then
  echo "ui-tests.sh: 没有在线的设备或模拟器" >&2
  exit 3
fi
if [ "$PROBE" -eq 1 ]; then
  exit 0
fi

# 唤醒并解锁（模拟器息屏或锁屏时触摸与页面渲染都不可靠）
"$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
"$ADB" shell wm dismiss-keyguard >/dev/null 2>&1
"$ADB" shell input keyevent 82 >/dev/null 2>&1

if ! ./gradlew --console=plain :feature:web:installDebugAndroidTest 2>&1 | tee "$LOG_DIR/ui_tests_install.log"; then
  echo "ui-tests.sh: 安装测试包失败" >&2
  exit 1
fi

LOG="$LOG_DIR/ui_tests_instrument.log"
if [ -n "$CLASS" ]; then
  "$ADB" shell am instrument -w -e class "$CLASS" "$RUNNER" 2>&1 | tee "$LOG"
else
  "$ADB" shell am instrument -w "$RUNNER" 2>&1 | tee "$LOG"
fi

if grep -Eq '^OK \([0-9]+ tests?\)' "$LOG"; then
  echo "ui-tests.sh: $(grep -E '^OK \(' "$LOG" | tr -d '\r')"
  exit 0
fi
echo "ui-tests.sh: 仪器测试没有全部通过（见 ${LOG}）" >&2
exit 1
