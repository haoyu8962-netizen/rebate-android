#!/bin/bash
# Release 制品扫描（03 §3.6、§11；05 QA-09 的前身，完整版在 QA-09）。
#
# 用法：
#   tools/ci/release-scan.sh <release.apk>
#       解包后用 strings 扫 dex 与资源，命中任一即失败：
#         - 非 prod 环境的 .invalid 地址（local / test / staging）
#         - 调试面板标记 COULI_DEBUG_PANEL
#         - DebugRoutes 的唯一长标记（DebugRoutes.MARKER，取自生成物 DebugRoutes.kt）
#         - 快照 routes.json 里 debug_only 路由的名字
#         - 组件目录页的唯一长标记（COMPONENT_CATALOG_MARKER，取自 app/src/debugFeatures 的 ComponentCatalog.kt；DS-01）
#       另用 aapt2 检查不可调试（badging 没有 application-debuggable，清单没有 android:debuggable=true）。
#   tools/ci/release-scan.sh --self-test [debug.apk] [release.apk] [staging.apk]
#       证明扫描有牙齿：debug、staging 制品必须命中（含 DebugRoutes 标记；debug 还必须命中 debuggable），
#       release 制品必须不命中。不给路径时用 app/build/outputs/apk 下的默认产物。
#   tools/ci/release-scan.sh --self-test-tools [release.apk]
#       证明工具失败会阻断：对非 ZIP 文件、PATH 里注入返回 127 的假 unzip / strings / aapt2 / python3 / grep
#       时，本脚本必须以 2 退出；不注入时对同一 release 制品必须以 0 退出。
#
# 退出码：0 扫描通过；1 检出违规；2 工具失败或用法错误（解包、strings、aapt2、读路由 / 标记、grep 出错）。
# 只有 grep 的「无匹配」（退出码 1）算正常；其余任何失败都不会被当成「通过」或「命中」。
# 兼容 macOS 自带 bash 3.2。
set -euo pipefail
# 按字节处理二进制内容（dex、arsc 里的非 UTF-8 字节不能让 sed / grep 出错或漏扫）
export LC_ALL=C

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SELF="$ROOT/tools/ci/release-scan.sh"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ROUTES="$ROOT/contracts/snapshot/contracts/routes.json"
DEBUG_ROUTES_KT="$ROOT/core/generated/src/debugFeatures/kotlin/com/couli/generated/DebugRoutes.kt"
CATALOG_KT="$ROOT/app/src/debugFeatures/kotlin/com/couli/app/catalog/ComponentCatalog.kt"

DEFAULT_DEBUG_APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
DEFAULT_RELEASE_APK="$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
DEFAULT_STAGING_APK="$ROOT/app/build/outputs/apk/staging/app-staging.apk"

# 工具失败：退出 2（在扫描子 shell 里调用时只退出子 shell，由 run_scan 判为工具失败）
fail() {
  echo "release-scan: 工具失败：$*" >&2
  exit 2
}

# aapt2：AAPT2 环境变量 > PATH 里的 aapt2 > SDK build-tools/36.0.0
resolve_aapt2() {
  if [ -n "${AAPT2:-}" ]; then
    printf '%s\n' "$AAPT2"
  elif command -v aapt2 > /dev/null 2>&1; then
    command -v aapt2
  else
    printf '%s\n' "$ANDROID_HOME/build-tools/36.0.0/aapt2"
  fi
}

# grep_dump <扩展正则> <文件> <命中输出>：0 = 命中，1 = 无匹配；grep 出错即工具失败
grep_file() {
  local status=0
  grep -E -- "$1" "$2" > "$3" || status=$?
  case "$status" in
    0) return 0 ;;
    1) return 1 ;;
    *) fail "grep 失败（退出码 ${status}，模式 $1）" ;;
  esac
}

debug_route_names() {
  python3 - "$ROUTES" << 'PY'
import json, sys
routes = json.load(open(sys.argv[1], encoding="utf-8"))["routes"]
for name, spec in routes.items():
    if spec.get("debug_only") is True:
        print(name)
PY
}

# scan_apk <apk> <结果目录>：只在子 shell 里调用。工具都成功时写出 结果目录/hits（每行一个命中标签，可为空）
# 与 结果目录/done，退出 0；任何工具失败退出非零且不写 done。
scan_apk() {
  set -euo pipefail
  local apk="$1"
  local out="$2"
  [ -f "$apk" ] || fail "找不到 APK：$apk"
  [ -r "$apk" ] || fail "APK 不可读：$apk"
  [ -f "$ROUTES" ] || fail "找不到快照 $ROUTES"
  [ -f "$DEBUG_ROUTES_KT" ] || fail "找不到生成物 $DEBUG_ROUTES_KT"
  [ -f "$CATALOG_KT" ] || fail "找不到组件目录页源码 $CATALOG_KT"

  local aapt2
  aapt2="$(resolve_aapt2)"
  [ -x "$aapt2" ] || fail "找不到 aapt2：${aapt2}（可用 AAPT2 环境变量指定）"

  # 读 debug_only 路由名与 DebugRoutes 标记（读不到即失败，不能当成「没有要查的东西」）
  local routes
  routes="$(debug_route_names)" || fail "读取 debug_only 路由失败（$ROUTES）"
  [ -n "$routes" ] || fail "routes.json 里没有 debug_only 路由，扫描规则失效"
  local marker
  marker="$(sed -n 's/^ *const val MARKER: kotlin\.String = "\([A-Za-z0-9_]*\)"$/\1/p' "$DEBUG_ROUTES_KT")" ||
    fail "读取 DebugRoutes 标记失败（$DEBUG_ROUTES_KT）"
  [ "${#marker}" -ge 24 ] || fail "DebugRoutes.kt 里没有足够长的 MARKER 常量（读到 '${marker}'）"
  local catalog_marker
  catalog_marker="$(sed -n 's/^const val COMPONENT_CATALOG_MARKER = "\([A-Za-z0-9_]*\)"$/\1/p' "$CATALOG_KT")" ||
    fail "读取组件目录页标记失败（$CATALOG_KT）"
  [ "${#catalog_marker}" -ge 24 ] || fail "ComponentCatalog.kt 里没有足够长的 COMPONENT_CATALOG_MARKER 常量（读到 '${catalog_marker}'）"

  local tmp
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/couli-scan.XXXXXX")" || fail "mktemp 失败"
  # shellcheck disable=SC2064
  trap "rm -rf '$tmp'" EXIT

  local status=0
  unzip -q -o "$apk" -d "$tmp/apk" || status=$?
  [ "$status" -eq 0 ] || fail "unzip 失败（退出码 ${status}）：无法解包 $apk（不是有效的 APK / ZIP，或 unzip 本身出错）"

  find "$tmp/apk" -type f -print0 > "$tmp/files.list" || fail "列出解包文件失败"
  local dump="$tmp/strings.txt"
  : > "$dump" || fail "无法写 $dump"
  local count=0 dex=0 file rel esc
  # dex 与全部资源（含 resources.arsc、二进制 XML、Java 资源）；从文件读列表（不用管道），strings 的失败能被看到
  while IFS= read -r -d '' file; do
    rel="${file#"$tmp"/apk/}"
    status=0
    strings -a < "$file" > "$tmp/one.txt" || status=$?
    [ "$status" -eq 0 ] || fail "strings 失败（退出码 ${status}，文件 ${rel}）"
    esc="$(printf '%s' "$rel" | sed 's/[\\|&]/\\&/g')" || fail "sed 失败"
    sed "s|^|${esc}: |" "$tmp/one.txt" >> "$dump" || fail "sed 失败（文件 ${rel}）"
    count=$((count + 1))
    case "$rel" in classes*.dex) dex=$((dex + 1)) ;; esac
  done < "$tmp/files.list"
  [ "$count" -gt 0 ] || fail "解包后没有任何文件"
  [ "$dex" -gt 0 ] || fail "解包后没有 classes*.dex"
  [ -s "$dump" ] || fail "strings 没有输出任何内容"

  : > "$out/hits"
  local pattern label
  for pattern in 'local\.couli\.invalid' 'test\.couli\.invalid' 'staging\.couli\.invalid' 'COULI_DEBUG_PANEL' "$marker" "$catalog_marker"; do
    case "$pattern" in
      "$marker") label="debug_routes_marker" ;;
      "$catalog_marker") label="component_catalog" ;;
      COULI_DEBUG_PANEL) label="debug_panel" ;;
      *) label="nonprod_url:${pattern%%\\*}" ;;
    esac
    if grep_file "$pattern" "$dump" "$tmp/hit.txt"; then
      echo "  命中 ${label} /${pattern}/：$(head -1 "$tmp/hit.txt")"
      echo "$label" >> "$out/hits"
    fi
  done
  local route
  for route in $routes; do
    if grep_file "(^|[^A-Za-z0-9_])${route}([^A-Za-z0-9_]|\$)" "$dump" "$tmp/hit.txt"; then
      echo "  命中 debug_only 路由名 ${route}：$(head -1 "$tmp/hit.txt")"
      echo "route:${route}" >> "$out/hits"
    fi
  done

  # 先取完整输出再 grep（管道接 grep -q 会让 aapt2 收到 SIGPIPE，在 pipefail 下误判）
  status=0
  "$aapt2" dump badging "$apk" > "$tmp/badging.txt" 2> "$tmp/aapt2.err" || status=$?
  [ "$status" -eq 0 ] || fail "aapt2 dump badging 失败（退出码 ${status}）：$(head -3 "$tmp/aapt2.err")"
  grep_file "^package: name='" "$tmp/badging.txt" "$tmp/hit.txt" || fail "aapt2 dump badging 输出里没有 package 行"
  status=0
  "$aapt2" dump xmltree --file AndroidManifest.xml "$apk" > "$tmp/manifest.txt" 2> "$tmp/aapt2.err" || status=$?
  [ "$status" -eq 0 ] || fail "aapt2 dump xmltree 失败（退出码 ${status}）：$(head -3 "$tmp/aapt2.err")"
  grep_file "E: application" "$tmp/manifest.txt" "$tmp/hit.txt" || fail "aapt2 dump xmltree 输出里没有 application 元素"
  if grep_file '^application-debuggable' "$tmp/badging.txt" "$tmp/hit.txt" ||
    grep_file 'android:debuggable\([^)]*\)=true' "$tmp/manifest.txt" "$tmp/hit.txt"; then
    echo "  命中 debuggable：清单 android:debuggable=true"
    echo "debuggable" >> "$out/hits"
  fi
  : > "$out/done"
}

# run_scan <apk>：设置 SCAN_HITS（命中数）与 SCAN_LABELS（命中标签，换行分隔）；工具失败直接退出 2
run_scan() {
  local apk="$1"
  local out status=0
  out="$(mktemp -d "${TMPDIR:-/tmp}/couli-scan-result.XXXXXX")" || fail "mktemp 失败"
  # 不放在 || 左边：那样子 shell 里的 errexit 会被 bash 忽略；这里关掉外层 errexit，子 shell 里 scan_apk 自己再打开
  set +e
  (scan_apk "$apk" "$out")
  status=$?
  set -e
  if [ "$status" -ne 0 ] || [ ! -f "$out/done" ]; then
    rm -rf "$out"
    echo "release-scan: 扫描 $apk 时工具失败（退出码 ${status}），不能判定通过或命中" >&2
    exit 2
  fi
  SCAN_LABELS="$(cat "$out/hits")" || fail "读取命中结果失败"
  rm -rf "$out"
  if [ -z "$SCAN_LABELS" ]; then
    SCAN_HITS=0
  else
    SCAN_HITS="$(printf '%s\n' "$SCAN_LABELS" | wc -l | tr -d ' ')"
  fi
}

# require_label <apk 说明> <标签>：自检里要求某个命中标签出现
require_label() {
  case $'\n'"$SCAN_LABELS"$'\n' in
    *$'\n'"$2"$'\n'*) echo "  ${1} 命中 ${2}（预期）" ;;
    *)
      echo "release-scan --self-test：失败，${1} 没有命中 ${2}，扫描没有牙齿" >&2
      exit 1
      ;;
  esac
}

forbid_label() {
  case $'\n'"$SCAN_LABELS"$'\n' in
    *$'\n'"$2"$'\n'*)
      echo "release-scan --self-test：失败，${1} 不应命中 ${2}" >&2
      exit 1
      ;;
    *) echo "  ${1} 未命中 ${2}（预期）" ;;
  esac
}

if [ "${1:-}" = "--self-test" ]; then
  debug_apk="${2:-$DEFAULT_DEBUG_APK}"
  release_apk="${3:-$DEFAULT_RELEASE_APK}"
  staging_apk="${4:-$DEFAULT_STAGING_APK}"

  echo "release-scan --self-test：debug 制品必须命中（${debug_apk}）"
  run_scan "$debug_apk"
  require_label "debug" "debug_routes_marker"
  require_label "debug" "debug_panel"
  require_label "debug" "component_catalog"
  require_label "debug" "debuggable"
  echo "  debug 共命中 $SCAN_HITS 项"

  echo "release-scan --self-test：staging 制品必须命中（${staging_apk}）"
  run_scan "$staging_apk"
  require_label "staging" "debug_routes_marker"
  require_label "staging" "debug_panel"
  require_label "staging" "component_catalog"
  forbid_label "staging" "debuggable"
  echo "  staging 共命中 $SCAN_HITS 项"

  echo "release-scan --self-test：release 制品必须不命中（${release_apk}）"
  run_scan "$release_apk"
  if [ "$SCAN_HITS" -ne 0 ]; then
    echo "release-scan --self-test：失败，release 制品命中 $SCAN_HITS 项：$(printf '%s' "$SCAN_LABELS" | tr '\n' ' ')" >&2
    exit 1
  fi
  echo "release-scan --self-test：通过"
  exit 0
fi

if [ "${1:-}" = "--self-test-tools" ]; then
  release_apk="${2:-$DEFAULT_RELEASE_APK}"
  [ -f "$release_apk" ] || fail "找不到 APK：$release_apk"
  work="$(mktemp -d "${TMPDIR:-/tmp}/couli-scan-tools.XXXXXX")" || fail "mktemp 失败"
  trap 'rm -rf "$work"' EXIT
  printf 'this is not a zip archive\n' > "$work/not-a-zip.apk"

  # expect_exit <期望退出码> <说明> <命令...>
  expect_exit() {
    local want="$1" what="$2" got=0
    shift 2
    "$@" > "$work/out.log" 2>&1 || got=$?
    if [ "$got" -ne "$want" ]; then
      echo "release-scan --self-test-tools：失败，${what} 时退出码 ${got}，应为 ${want}" >&2
      tail -5 "$work/out.log" >&2
      exit 1
    fi
    echo "  ${what}：退出 ${got}（预期）$(grep -m1 '工具失败' "$work/out.log" || true)"
  }

  echo "release-scan --self-test-tools：不注入故障时 release 制品应通过（${release_apk}）"
  expect_exit 0 "无故障" env -u AAPT2 "$SELF" "$release_apk"

  echo "release-scan --self-test-tools：工具失败必须以 2 退出"
  expect_exit 2 "输入不是 ZIP" env -u AAPT2 "$SELF" "$work/not-a-zip.apk"
  expect_exit 2 "--self-test 遇到非 ZIP 的 debug 制品" env -u AAPT2 "$SELF" --self-test "$work/not-a-zip.apk" "$release_apk" "$release_apk"
  for tool in unzip strings aapt2 python3 grep; do
    mkdir -p "$work/fake-$tool"
    printf '#!/bin/sh\necho "fake %s: injected failure" >&2\nexit 127\n' "$tool" > "$work/fake-$tool/$tool"
    chmod +x "$work/fake-$tool/$tool"
    expect_exit 2 "PATH 注入返回 127 的假 ${tool}" env -u AAPT2 PATH="$work/fake-$tool:$PATH" "$SELF" "$release_apk"
  done
  # 自检本身也不能把工具失败当成预期命中
  expect_exit 2 "--self-test 遇到返回 127 的假 strings" \
    env -u AAPT2 PATH="$work/fake-strings:$PATH" "$SELF" --self-test "$DEFAULT_DEBUG_APK" "$release_apk" "$DEFAULT_STAGING_APK"
  echo "release-scan --self-test-tools：通过"
  exit 0
fi

[ $# -eq 1 ] || {
  echo "用法：release-scan.sh <release.apk> | --self-test [debug.apk] [release.apk] [staging.apk] | --self-test-tools [release.apk]" >&2
  exit 2
}
echo "release-scan：扫描 $1"
run_scan "$1"
if [ "$SCAN_HITS" -ne 0 ]; then
  echo "release-scan：失败，命中 $SCAN_HITS 项（Release 制品里不得有测试环境地址、调试面板、debug_only 路由，且不可调试）" >&2
  exit 1
fi
echo "release-scan：通过"
