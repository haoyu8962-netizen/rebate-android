#!/bin/bash
# 按 contract.lock 锁定的两个提交重建 contracts/snapshot/，并刷新 lock 里的 files 哈希。
#
# 用法：tools/contracts/sync.sh
#       tools/contracts/sync.sh --check   只核对（CT-LOCK-01）：按 lock 的提交在临时目录重建快照，与 contracts/snapshot/
#                                        逐字节比较，并核对 lock 的 files 哈希与快照一致；不写任何文件。
#                                        一致退出 0，不一致退出 1（列出差异），取不到仓库 / 提交退出 1。
#       tools/contracts/sync.sh --check-hashes-only   只核对 lock 的 files 哈希与快照一致（不需要上游仓库，
#                                        不核对锁定提交号；改了提交号而没重建快照查不出来）。不是完整一致性检查，
#                                        verify.sh 不用它（与鸿蒙 CT-LOCK-01 R1-02 同口径）。
#   仓库路径取 lock 里的相对路径（相对本仓库根）；在 worktree 等路径不成立的地方，
#   用环境变量 REBATE_PLATFORM_DIR、COULI_DIR 覆盖。
#
# 约定（与 iOS 端 APP-01 一致）：
#   - 只用 `git -C <repo> show <commit>:<path>` 读取，不读工作区。
#   - YAML 用 /usr/bin/ruby（YAML.safe_load）转 JSON；转换后出现非字符串映射键即报错退出
#     （Ruby 按 YAML 1.1 会把未加引号的 on/off/yes/no 当布尔，契约按 1.2 写）。
#   - 输出 UTF-8、2 空格缩进、末尾换行；保持源文件里的键顺序（枚举取值顺序即契约顺序）。
# 兼容 macOS 自带 bash 3.2。
set -euo pipefail

CHECK=0
HASHES_ONLY=0
if [ "${1:-}" = "--check" ]; then
  CHECK=1
elif [ "${1:-}" = "--check-hashes-only" ]; then
  HASHES_ONLY=1
elif [ -n "${1:-}" ]; then
  echo "用法：sync.sh [--check | --check-hashes-only]" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LOCK="$ROOT/contract.lock"
SNAPSHOT="$ROOT/contracts/snapshot"
RUBY="/usr/bin/ruby"

if [ ! -f "$LOCK" ]; then
  echo "sync.sh: 找不到 $LOCK" >&2
  exit 1
fi

if [ "$HASHES_ONLY" -eq 1 ]; then
  python3 - "$LOCK" "$SNAPSHOT" <<'PY'
import hashlib, json, os, sys
lock = json.load(open(sys.argv[1], encoding="utf-8"))
for key in ("rebate_platform", "design_tokens"):
    if len(lock[key]["commit"]) != 40:
        sys.stderr.write("sync.sh --check-hashes-only：contract.lock 的 %s.commit 不是 40 位\n" % key)
        sys.exit(1)
locked = lock.get("files", {})
have = {}
for dirpath, _, names in os.walk(sys.argv[2]):
    for name in names:
        full = os.path.join(dirpath, name)
        with open(full, "rb") as fh:
            have[os.path.relpath(full, sys.argv[2]).replace(os.sep, "/")] = hashlib.sha256(fh.read()).hexdigest()
bad = sorted(k for k in set(locked) | set(have) if locked.get(k) != have.get(k))
if bad:
    sys.stderr.write("sync.sh --check-hashes-only：contract.lock files 哈希与快照不一致：\n")
    for k in bad:
        sys.stderr.write("  " + k + "\n")
    sys.exit(1)
print("sync.sh --check-hashes-only：%d 个快照文件与 lock files 哈希一致（未核对锁定提交，不是完整一致性检查）" % len(have))
PY
  exit $?
fi

lock_get() {
  python3 - "$LOCK" "$1" <<'PY'
import json, sys
value = json.load(open(sys.argv[1], encoding="utf-8"))
for part in sys.argv[2].split("."):
    value = value[part]
print(value)
PY
}

RP_REPO_REL="$(lock_get rebate_platform.repo)"
RP_COMMIT="$(lock_get rebate_platform.commit)"
DT_REPO_REL="$(lock_get design_tokens.repo)"
DT_COMMIT="$(lock_get design_tokens.commit)"
DT_PATH="$(lock_get design_tokens.path)"

RP_DIR="${REBATE_PLATFORM_DIR:-$ROOT/$RP_REPO_REL}"
DT_DIR="${COULI_DIR:-$ROOT/$DT_REPO_REL}"

for pair in "rebate_platform|$RP_DIR|$RP_COMMIT" "design_tokens|$DT_DIR|$DT_COMMIT"; do
  name="${pair%%|*}"
  rest="${pair#*|}"
  dir="${rest%|*}"
  commit="${rest#*|}"
  if [ "${#commit}" -ne 40 ]; then
    echo "sync.sh: $name 的提交号必须是 40 位：$commit" >&2
    exit 1
  fi
  if ! git -C "$dir" cat-file -e "${commit}^{commit}" 2>/dev/null; then
    echo "sync.sh: 在 $dir 找不到 $name 提交 ${commit}（可用 REBATE_PLATFORM_DIR / COULI_DIR 覆盖仓库路径）" >&2
    exit 1
  fi
done

TMP="$(mktemp -d "${TMPDIR:-/tmp}/couli-sync.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
OUT="$TMP/snapshot"
mkdir -p "$OUT"

# 要取的契约与 specs 文件（03 §2；APP-01 任务书 §1）。APP-02c 加入 openapi.yaml（GET /v1/app-versions/check 的
# 响应示例给调试假数据；OpenAPI 客户端仍归 APP-03）与 specs/app-stores.yaml（商店对照表，生成 AppStores 常量）。
# APP-03a 加入 specs/request-sign.vectors.json（BR-ID-09 服务端与三端共用的签名测试向量）。
# APP-03b 加入 specs/device-hash.vectors.json（BR-ID-09 device_hash 服务端与三端共用的向量）；events、system-permissions、
# client-security-baseline 已在列表里。
FILES="$(git -C "$RP_DIR" ls-tree --name-only "$RP_COMMIT" contracts/enums/ | grep '\.yaml$' | sort)"
FILES="$FILES
contracts/error-codes.yaml
contracts/routes.json
contracts/bridge.schema.json
contracts/agent-stream.schema.json
contracts/texts.default.json
contracts/apps.json
specs/events.yaml
specs/client-public-ids.yaml
specs/client-security-baseline.yaml
specs/system-permissions.yaml
contracts/openapi.yaml
specs/app-stores.yaml
specs/request-sign.vectors.json
specs/device-hash.vectors.json"

# APP-10 加入 contracts/fixtures/agent-streams/*.ndjson（三端 StreamReducer 单测与快照测试用）：
# 先单独取列表并检查退出码，空列表即失败；逐字节原样拷贝（ndjson 是逐行 JSON，整文件不是合法 JSON；
# 原样拷贝让快照哈希与 rebate-platform 锁定提交里的文件哈希直接可比）。
AGENT_FIXTURES="$(git -C "$RP_DIR" ls-tree --name-only "$RP_COMMIT" contracts/fixtures/agent-streams/)" || {
  echo "sync.sh: 取不到 contracts/fixtures/agent-streams/ 列表" >&2
  exit 1
}
AGENT_FIXTURES="$(printf '%s\n' "$AGENT_FIXTURES" | grep -E '\.ndjson$' | sort || true)"
if [ -z "$AGENT_FIXTURES" ]; then
  echo "sync.sh: contracts/fixtures/agent-streams/ 下没有 ndjson" >&2
  exit 1
fi
FILES="$FILES
$AGENT_FIXTURES"

normalize_json() {
  # $1 输入 JSON 文件，$2 输出文件
  python3 - "$1" "$2" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    data = json.load(f)
with open(sys.argv[2], "w", encoding="utf-8", newline="\n") as f:
    f.write(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
PY
}

yaml_to_json() {
  # $1 输入 YAML，$2 输出 JSON（紧凑），非字符串映射键即失败
  "$RUBY" -ryaml -rjson -e '
    def check(node, path)
      case node
      when Hash
        node.each do |key, value|
          unless key.is_a?(String)
            warn "sync.sh: #{ARGV[0]} 在 #{path} 出现非字符串映射键 #{key.inspect}（#{key.class}）；契约按 YAML 1.2 写，请给键加引号"
            exit 2
          end
          check(value, "#{path}.#{key}")
        end
      when Array
        node.each_with_index { |value, index| check(value, "#{path}[#{index}]") }
      end
    end
    data = YAML.safe_load(File.read(ARGV[0], encoding: "UTF-8"), [], [], false, ARGV[0])
    check(data, "$")
    File.write(ARGV[1], JSON.generate(data))
  ' "$1" "$2"
}

echo "$FILES" | while IFS= read -r path; do
  [ -n "$path" ] || continue
  raw="$TMP/raw"
  git -C "$RP_DIR" show "${RP_COMMIT}:${path}" > "$raw"
  case "$path" in
    *.yaml)
      dest="$OUT/${path%.yaml}.json"
      mkdir -p "$(dirname "$dest")"
      yaml_to_json "$raw" "$TMP/converted.json"
      normalize_json "$TMP/converted.json" "$dest"
      ;;
    *.json)
      dest="$OUT/$path"
      mkdir -p "$(dirname "$dest")"
      normalize_json "$raw" "$dest"
      ;;
    contracts/fixtures/agent-streams/*.ndjson)
      dest="$OUT/$path"
      mkdir -p "$(dirname "$dest")"
      cp "$raw" "$dest"
      ;;
    *)
      echo "sync.sh: 不支持的文件类型 $path" >&2
      exit 1
      ;;
  esac
  echo "  $path -> contracts/snapshot/${dest#$OUT/}"
done

mkdir -p "$OUT/design"
git -C "$DT_DIR" show "${DT_COMMIT}:${DT_PATH}" > "$TMP/tokens.raw"
normalize_json "$TMP/tokens.raw" "$OUT/design/design-tokens.json"
echo "  $DT_PATH -> contracts/snapshot/design/design-tokens.json"

if [ "$CHECK" -eq 1 ]; then
  python3 - "$LOCK" "$OUT" "$SNAPSHOT" <<'PY'
import hashlib, json, os, sys
lock_path, rebuilt, snap = sys.argv[1], sys.argv[2], sys.argv[3]
def digests(base):
    out = {}
    if not os.path.isdir(base):
        return out
    for dirpath, _, names in os.walk(base):
        for name in names:
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, base).replace(os.sep, "/")
            with open(full, "rb") as fh:
                out[rel] = hashlib.sha256(fh.read()).hexdigest()
    return out
with open(lock_path, encoding="utf-8") as f:
    locked = json.load(f).get("files", {})
want, have = digests(rebuilt), digests(snap)
problems = []
for rel in sorted(set(want) | set(have)):
    if rel not in have:
        problems.append("快照缺文件（锁定提交里有）：%s" % rel)
    elif rel not in want:
        problems.append("快照多出文件（锁定提交里没有）：%s" % rel)
    elif want[rel] != have[rel]:
        problems.append("快照与锁定提交重建结果不同：%s" % rel)
for rel in sorted(set(locked) | set(have)):
    if locked.get(rel) != have.get(rel):
        problems.append("contract.lock files 哈希与快照不一致：%s" % rel)
if problems:
    sys.stderr.write("sync.sh --check：契约快照与 contract.lock 不一致（运行 tools/contracts/sync.sh 重建）：\n")
    for p in problems:
        sys.stderr.write("  " + p + "\n")
    sys.exit(1)
print("sync.sh --check：%d 个快照文件与锁定提交、lock 哈希一致" % len(have))
PY
  exit $?
fi

rm -rf "$SNAPSHOT"
mkdir -p "$(dirname "$SNAPSHOT")"
mv "$OUT" "$SNAPSHOT"

python3 - "$LOCK" "$SNAPSHOT" <<'PY'
import hashlib, json, os, sys
lock_path, snapshot = sys.argv[1], sys.argv[2]
with open(lock_path, encoding="utf-8") as f:
    lock = json.load(f)
files = {}
for directory, _, names in os.walk(snapshot):
    for name in names:
        full = os.path.join(directory, name)
        rel = os.path.relpath(full, snapshot).replace(os.sep, "/")
        with open(full, "rb") as f:
            files[rel] = hashlib.sha256(f.read()).hexdigest()
lock["files"] = {key: files[key] for key in sorted(files)}
with open(lock_path, "w", encoding="utf-8", newline="\n") as f:
    f.write(json.dumps(lock, ensure_ascii=False, indent=2) + "\n")
PY

echo "sync.sh: 快照已按 contract.lock 重建（$(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))["files"]))' "$LOCK") 个文件）"
