#!/usr/bin/env python3
"""契约快照 → Kotlin 生成物（core:generated）。

只读 contracts/snapshot/，输出：
  core/generated/src/main/kotlin/com/couli/generated/*.kt
  core/generated/src/debugFeatures/kotlin/com/couli/generated/DebugRoutes.kt   （只被 debug、staging 加入）
  core/generated/src/debugFeatures/kotlin/com/couli/generated/AppVersionCheckExample.kt （同上；APP-02c 调试假数据）
  core/generated/src/main/resources/texts.default.json                         （快照原样复制）
  core/generated/src/main/resources/bridge.schema.json                         （快照原样复制；APP-04 BridgeProtocol 按它校验参数）

用法：
  python3 tools/codegen/gen.py           生成（覆盖上述文件，删除输出目录里多余的旧文件）
  python3 tools/codegen/gen.py --check   在临时目录生成后逐字比较；不一致退出 1 并列出文件

APP-03a 加：openapi 每个 operation 的登记表 → ApiOperations（包 com.couli.core.generated；不生成请求 / 响应模型）。
本任务生成：枚举、错误码、路由、桥、文案、令牌；不生成 OpenAPI 客户端、事件、apps.json 片段
（分别归 APP-03、APP-03、APP-04/06）。API 形状见 APP-01-android 任务书 §5。
APP-02c 加：商店对照表 specs/app-stores.yaml → AppStores（main）；openapi GET /v1/app-versions/check 的 200 响应示例
→ AppVersionCheckExample（debugFeatures，调试面板的版本检查假数据，Release 里没有）。
APP-03b 加（包 com.couli.core.clientcore，三端测试接线约定）：
  - specs/events.yaml → ClientEvents（事件构造函数，业务代码只能调用它们）与各枚举字段的类型；字段名命中
    FORBIDDEN_EVENT_FIELD 即失败（03 §4.7：不为完整推广 URL、口令、推广位、relation_id、令牌等设字段）；
  - specs/device-hash.vectors.json 的 invalid_hash_seeds → DeviceHashSeeds（BR-ID-09 无效设备哈希清单的种子）；
  - openapi GET /v1/config 的 200 响应示例 data → 资源 default_config.json 与读取它的 BundledConfig（包内默认配置；
    去掉示例里的非生产地址，TODO(N-21)：发布构建改为取生产配置当时的值生成）。
APP-11 加：specs/home-sdui-vectors.json 的 goldenPages.default_home → 资源 default_home.json 与读取它的 BundledHome（包内默认首页，
三端逐字一致；--check 保证与向量一致）。
APP-04 加：contracts/bridge.schema.json 原样复制为资源 bridge.schema.json（BridgeProtocol 按方法的 params schema 校验、
按 levels / signed_paths / events / not_exposed 判定）；contracts/apps.json 的 trade_only 目标 → BridgeAppTargets（ext.openApp 的 90403）。
只用 Python 3 标准库；输出不含时间戳，可重复。

命名规则（防止契约里的名字遮蔽 Kotlin 标准类型或生成器自用名；tools/codegen/test_gen.py 用探针输入覆盖）：
  1. 枚举类型名 = 蛇形键的 UpperCamel（client_platform → ClientPlatform；数字开头加 V）。
  2. 类型名若落在 RESERVED_TYPE_NAMES（Kotlin 默认导入的标准类型 String、Int、Long、Boolean、Any、Unit、
     List、Map、Set、Pair、Result、Enum 等，生成器模板与导入用到的名字 KSerializer、Serializable、Json、
     Companion 等，以及生成器自己产出的类型名 Routes、RouteSpec、ErrorCodes 等）里，一律加前缀 Contract
     （string → ContractString，routes → ContractRoutes）。加前缀后仍与其他类型名、序列化器名
     （<类型名>Serializer）或保留名冲突即明确失败（GenError），不静默改第二次。
  3. entry 名 = 取值的 UPPER_SNAKE（stable_24h → STABLE_24H；数字开头加 V_）。与生成器自用的 entry / 成员名
     （RESERVED_ENTRY_NAMES：UNKNOWN 等）冲突时加后缀 _VALUE（unknown → UNKNOWN_VALUE）；改名后仍与同枚举
     其他 entry 冲突即失败。raw、fromRaw、known、byRaw、Companion 是小写或混合大小写，UPPER_SNAKE 不会与之相同，
     仍列入保留名做防御性检查。
  4. 模板里引用 Kotlin 标准类型一律写全限定名（kotlin.String、kotlin.Int、kotlin.collections.List、
     kotlin.collections.Map 等），即使同包出现同名声明也不会被遮蔽。
  5. 输出文件名（按大小写不敏感比较，macOS 默认文件系统不区分大小写）冲突即失败，不覆盖。
  6. 同包生成的全部类型名（枚举类型、<类型名>Serializer、生成器固定类型与保留名）按 casefold 比较判重，
     冲突即失败：foo 与 f_o_o 分别得到 Foo 与 FOO，虽在同一个 .kt 文件里，但编译出的 Foo.class 与 FOO.class
     在不区分大小写的文件系统上是同一个文件。同一枚举内的 entry 名也按 casefold 判重。
"""

from __future__ import annotations

import argparse
import filecmp
import json
import os
import re
import shutil
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SNAPSHOT = os.path.join(ROOT, "contracts", "snapshot")

PACKAGE = "com.couli.generated"
PACKAGE_PATH = PACKAGE.replace(".", "/")
MAIN_KOTLIN = "core/generated/src/main/kotlin/" + PACKAGE_PATH
DEBUG_KOTLIN = "core/generated/src/debugFeatures/kotlin/" + PACKAGE_PATH
RESOURCES = "core/generated/src/main/resources"
# APP-03a：openapi 操作登记表（ApiOperations）。包名按三端测试接线约定（NetworkVectorTest：com.couli.core.generated）。
API_PACKAGE = "com.couli.core.generated"
API_KOTLIN = "core/generated/src/main/kotlin/" + API_PACKAGE.replace(".", "/")
# APP-03b：Tracker 事件构造函数、设备哈希种子、包内默认配置（三端测试接线约定的包 com.couli.core.clientcore）。
CLIENT_CORE_PACKAGE = "com.couli.core.clientcore"
CLIENT_CORE_KOTLIN = "core/generated/src/main/kotlin/" + CLIENT_CORE_PACKAGE.replace(".", "/")
OWNED_DIRS = [MAIN_KOTLIN, DEBUG_KOTLIN, RESOURCES, API_KOTLIN, CLIENT_CORE_KOTLIN]

HEADER = "// 生成物，禁止手改；来源 contract.lock（tools/codegen/gen.py 由 contracts/snapshot/ 生成）\n"

TYPE_PREFIX = "Contract"
# 生成器自己产出的顶层类型名。
GENERATOR_TYPE_NAMES = {
    "ContractEnums",
    "RetryKind",
    "ErrorCodeInfo",
    "ErrorCodes",
    "RouteKind",
    "RouteSpec",
    "RouteParamSpec",
    "RouteParamType",
    "Routes",
    "DebugRoutes",
    "BridgeMethodSpec",
    "BridgeMethods",
    "BridgeErrors",
    "BridgeAppTargets",
    "DefaultTexts",
    "DesignTokens",
    "AppStoreSpec",
    "AppStores",
    "AppVersionCheckExample",
    "ApiOperation",
    "ApiOperations",
    "ApiMinVersionGate",
    "ClientEvent",
    "ClientEvents",
    "DeviceHashSeeds",
    "BundledConfig",
    "BundledHome",
}
# Kotlin 默认导入（kotlin.*、kotlin.collections.* 等）里常见的类型名；同包声明会遮蔽它们。
KOTLIN_STANDARD_TYPE_NAMES = {
    "Any", "Nothing", "Unit", "String", "CharSequence", "Char", "Boolean", "Byte", "Short", "Int", "Long",
    "Float", "Double", "Number", "Comparable", "Enum", "Array", "Throwable", "Exception", "Error",
    "Result", "Pair", "Triple", "Lazy", "Function", "Annotation", "Deprecated", "Suppress",
    "Iterable", "Collection", "List", "MutableList", "Map", "MutableMap", "Set", "MutableSet",
    "ArrayList", "HashMap", "LinkedHashMap", "HashSet", "LinkedHashSet", "Sequence",
    "IntArray", "LongArray", "BooleanArray", "ByteArray", "CharArray", "DoubleArray", "FloatArray", "ShortArray",
}
# 模板、导入与 Kotlin 语言本身用到的其他名字。
GENERATOR_SYMBOL_NAMES = {
    "KSerializer", "Serializable", "Serializer", "SerialDescriptor", "PrimitiveSerialDescriptor",
    "PrimitiveKind", "Decoder", "Encoder", "Json", "JsonObject", "JsonElement", "Companion",
    "Loaded", "Raw", "FromRaw", "Known", "Unknown",
}
RESERVED_TYPE_NAMES = GENERATOR_TYPE_NAMES | KOTLIN_STANDARD_TYPE_NAMES | GENERATOR_SYMBOL_NAMES
# 规则 6 用：casefold → 原名。
RESERVED_TYPE_NAMES_FOLDED = {n.casefold(): n for n in sorted(RESERVED_TYPE_NAMES)}
# 枚举类体内生成器自用的 entry / 成员名。
RESERVED_ENTRY_NAMES = {"UNKNOWN", "raw", "fromRaw", "known", "byRaw", "Companion", "name", "ordinal", "entries"}
ENTRY_RENAME_SUFFIX = "_VALUE"
KOTLIN_HARD_KEYWORDS = {
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
    "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
    "true", "try", "typealias", "typeof", "val", "var", "when", "while",
}
RETRY_KINDS = ["never", "after_action", "later", "immediate", "not_applicable"]
UNKNOWN_RAW = "__unknown__"
# DebugRoutes 的唯一标记（release-scan.sh 读同一个常量名：DEBUG_ROUTES_MARKER）。
DEBUG_ROUTES_MARKER = "COULI_DEBUG_ROUTES_MARKER_8d1f4c2a9e7b"
# 版本检查假数据的唯一标记（release-scan.sh 读 AppVersionCheckExample.kt 里的 MARKER）。
UPDATE_EXAMPLE_MARKER = "COULI_UPDATE_CHECK_EXAMPLE_MARKER_5b7e3d91c2a4"
APP_VERSION_CHECK_PATH = "/v1/app-versions/check"


class GenError(Exception):
    pass


def load(rel: str):
    path = os.path.join(SNAPSHOT, rel)
    if not os.path.isfile(path):
        raise GenError(f"快照文件不存在：contracts/snapshot/{rel}（先运行 tools/contracts/sync.sh）")
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def kstr(value: str) -> str:
    """Kotlin 字符串字面量。"""
    out = ['"']
    for ch in value:
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif ch == "$":
            out.append("\\$")
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif ord(ch) < 0x20:
            out.append("\\u%04x" % ord(ch))
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


def knullable_str(value) -> str:
    return "null" if value is None else kstr(str(value))


def kbool(value) -> str:
    return "true" if value else "false"


def upper_camel(snake: str) -> str:
    parts = [p for p in re.split(r"[^A-Za-z0-9]+", snake) if p]
    name = "".join(p[:1].upper() + p[1:] for p in parts)
    if not name or name[0].isdigit():
        name = "V" + name
    return name


def lower_camel(path: str) -> str:
    parts = [p for p in re.split(r"[^A-Za-z0-9]+", path) if p]
    if not parts:
        raise GenError(f"无法转换为 lowerCamel：{path!r}")
    name = parts[0][:1].lower() + parts[0][1:] + "".join(p[:1].upper() + p[1:] for p in parts[1:])
    if name[0].isdigit():
        name = "v" + name
    return name


def entry_name(raw: str) -> str:
    """取值 → UPPER_SNAKE entry 名。"""
    # camelCase 边界拆开（契约里目前没有，防御性处理）
    spaced = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", raw)
    name = re.sub(r"[^A-Za-z0-9]+", "_", spaced).strip("_").upper()
    if not name:
        raise GenError(f"枚举取值无法转换为 entry 名：{raw!r}")
    if name[0].isdigit():
        name = "V_" + name
    if name in RESERVED_ENTRY_NAMES:
        # 与生成器自用 entry 冲突（如兜底 UNKNOWN("__unknown__")）：加后缀，unknown → UNKNOWN_VALUE。
        name = name + ENTRY_RENAME_SUFFIX
    return name


def enum_type_name(key: str) -> str:
    """枚举键 → Kotlin 类型名；与保留名冲突时加前缀 Contract（规则 2）。"""
    name = upper_camel(key)
    if name in RESERVED_TYPE_NAMES:
        name = TYPE_PREFIX + name
    return name


def comment_text(text: str) -> str:
    return " ".join(str(text).split()).replace("*/", "* /")


# 本次 generate() 已写出的文件：小写路径 → 原路径（规则 5）。
_WRITTEN: dict = {}


def claim_output(rel: str) -> None:
    """登记一个输出文件；与本次已写出的文件同名（大小写不敏感）即失败，不覆盖。"""
    folded = rel.casefold()
    if folded in _WRITTEN:
        raise GenError(f"输出文件名冲突：{rel} 与 {_WRITTEN[folded]}（大小写不敏感比较）")
    _WRITTEN[folded] = rel


def write(out_root: str, rel: str, content: str) -> str:
    claim_output(rel)
    path = os.path.join(out_root, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(content)
    return rel


def kotlin_file(body: str, imports=(), suppress=(), package: str = PACKAGE) -> str:
    lines = [HEADER.rstrip("\n")]
    if suppress:
        lines.append("@file:Suppress(" + ", ".join(kstr(s) for s in suppress) + ")")
        lines.append("")
    lines.append(f"package {package}")
    lines.append("")
    if imports:
        for imp in sorted(imports):
            lines.append(f"import {imp}")
        lines.append("")
    text = "\n".join(lines) + "\n" + body.rstrip("\n") + "\n"
    return text


# ---------------------------------------------------------------- 枚举


def collect_enums():
    enum_dir = os.path.join(SNAPSHOT, "contracts", "enums")
    if not os.path.isdir(enum_dir):
        raise GenError("快照里没有 contracts/enums/")
    groups = []
    seen_keys = {}
    seen_types = {}
    for file_name in sorted(os.listdir(enum_dir)):
        if not file_name.endswith(".json"):
            continue
        data = load(f"contracts/enums/{file_name}")
        enums = data.get("enums")
        if not isinstance(enums, dict) or not enums:
            raise GenError(f"contracts/enums/{file_name} 没有 enums")
        items = []
        for key, spec in enums.items():
            if key in seen_keys:
                raise GenError(f"枚举键 {key} 在 {seen_keys[key]} 与 {file_name} 重复")
            seen_keys[key] = file_name
            values = spec.get("values")
            if not isinstance(values, dict) or not values:
                raise GenError(f"枚举 {key} 没有 values")
            type_name = enum_type_name(key)
            serializer_name = type_name + "Serializer"
            # 规则 6：类型名按大小写不敏感判重（同包 .class 在不区分大小写的文件系统上会互相覆盖）。
            for declared in (type_name, serializer_name):
                folded = declared.casefold()
                if folded in RESERVED_TYPE_NAMES_FOLDED:
                    raise GenError(
                        f"枚举 {key} 生成的类型名 {declared} 与保留名 {RESERVED_TYPE_NAMES_FOLDED[folded]} 冲突"
                        "（大小写不敏感比较；加前缀后仍冲突也在此失败）"
                    )
                if folded in seen_types:
                    raise GenError(
                        f"枚举 {key} 生成的类型名 {declared} 与 {seen_types[folded]} 冲突（大小写不敏感比较）"
                    )
            seen_types[type_name.casefold()] = f"枚举 {key} 的类型 {type_name}"
            seen_types[serializer_name.casefold()] = f"枚举 {key} 的序列化器 {serializer_name}"
            entries = []
            # entry 名同一枚举内按大小写不敏感判重；只与全大写的保留 entry（UNKNOWN）比较，
            # raw、fromRaw、name 等成员名大小写不同即是不同标识符（RAW 与 raw 可以并存），不参与折叠比较。
            names = {n.casefold(): "（生成器保留）" for n in RESERVED_ENTRY_NAMES if n.isupper()}
            for raw in values.keys():
                if raw == UNKNOWN_RAW:
                    raise GenError(f"枚举 {key} 的取值与兜底值 {UNKNOWN_RAW} 冲突")
                name = entry_name(raw)
                if name in KOTLIN_HARD_KEYWORDS:
                    raise GenError(f"枚举 {key} 的 entry 名 {name} 是 Kotlin 关键字")
                if name in RESERVED_ENTRY_NAMES:
                    raise GenError(f"枚举 {key} 的取值 {raw!r} 转换后 entry 名 {name} 仍与生成器保留名冲突")
                if name.casefold() in names:
                    raise GenError(
                        f"枚举 {key} 的取值 {raw!r} 与 {names[name.casefold()]!r} 转换后 entry 名冲突：{name}"
                        "（大小写不敏感比较）"
                    )
                names[name.casefold()] = raw
                entries.append((name, raw))
            items.append({
                "key": key,
                "type": type_name,
                "description": spec.get("description", ""),
                "entries": entries,
            })
        groups.append((file_name[:-5], items))
    return groups


def render_enum(item, source_file: str) -> str:
    t = item["type"]
    lines = []
    lines.append("/**")
    lines.append(f" * 契约枚举 `{item['key']}`（contracts/enums/{source_file}.yaml）。")
    if item["description"]:
        lines.append(f" * {comment_text(item['description'])}")
    lines.append(" *")
    lines.append(f" * 未知取值解码为 [{t}.UNKNOWN]，不抛错。")
    lines.append(" */")
    lines.append(f"@Serializable(with = {t}Serializer::class)")
    lines.append(f"enum class {t}(")
    lines.append("    val raw: kotlin.String,")
    lines.append(") {")
    for name, raw in item["entries"]:
        lines.append(f"    {name}({kstr(raw)}),")
    lines.append(f"    UNKNOWN({kstr(UNKNOWN_RAW)}),")
    lines.append("    ;")
    lines.append("")
    lines.append("    companion object {")
    lines.append("        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */")
    lines.append(f"        val known: kotlin.collections.List<{t}> =")
    lines.append("            listOf(")
    for name, _ in item["entries"]:
        lines.append(f"                {name},")
    lines.append("            )")
    lines.append("")
    lines.append(f"        private val byRaw: kotlin.collections.Map<kotlin.String, {t}> = known.associateBy {{ it.raw }}")
    lines.append("")
    lines.append("        /** 未知取值返回 [UNKNOWN]。 */")
    lines.append(f"        fun fromRaw(raw: kotlin.String): {t} = byRaw[raw] ?: UNKNOWN")
    lines.append("    }")
    lines.append("}")
    lines.append("")
    lines.append(f"/** [{t}] 的序列化器：未知字符串得 [{t}.UNKNOWN]；序列化写 raw。 */")
    lines.append(f"object {t}Serializer : KSerializer<{t}> {{")
    lines.append("    override val descriptor: SerialDescriptor =")
    lines.append(f"        PrimitiveSerialDescriptor({kstr(PACKAGE + '.' + t)}, PrimitiveKind.STRING)")
    lines.append("")
    lines.append(f"    override fun deserialize(decoder: Decoder): {t} = {t}.fromRaw(decoder.decodeString())")
    lines.append("")
    lines.append("    override fun serialize(")
    lines.append("        encoder: Encoder,")
    lines.append(f"        value: {t},")
    lines.append("    ) {")
    lines.append("        encoder.encodeString(value.raw)")
    lines.append("    }")
    lines.append("}")
    return "\n".join(lines) + "\n"


ENUM_IMPORTS = [
    "kotlinx.serialization.KSerializer",
    "kotlinx.serialization.Serializable",
    "kotlinx.serialization.descriptors.PrimitiveKind",
    "kotlinx.serialization.descriptors.PrimitiveSerialDescriptor",
    "kotlinx.serialization.descriptors.SerialDescriptor",
    "kotlinx.serialization.encoding.Decoder",
    "kotlinx.serialization.encoding.Encoder",
]


def gen_enums(out_root: str):
    groups = collect_enums()
    written = []
    registry = []
    for group, items in groups:
        body = "\n".join(render_enum(item, group) for item in items)
        written.append(write(out_root, f"{MAIN_KOTLIN}/Enums{upper_camel(group)}.kt",
                             kotlin_file(body, ENUM_IMPORTS)))
        registry.extend(items)
    lines = [
        "/** 全部契约枚举：键为契约里的蛇形原名，值为契约顺序的 raw（不含 unknown）。 */",
        "object ContractEnums {",
        "    val all: kotlin.collections.Map<kotlin.String, kotlin.collections.List<kotlin.String>> =",
        "        linkedMapOf(",
    ]
    for item in registry:
        lines.append(f"            {kstr(item['key'])} to {item['type']}.known.map {{ it.raw }},")
    lines += ["        )", "}"]
    written.append(write(out_root, f"{MAIN_KOTLIN}/ContractEnums.kt", kotlin_file("\n".join(lines) + "\n")))
    return written, {item["key"]: item for item in registry}


# ---------------------------------------------------------------- 错误码


def retry_entry(kind: str, where: str) -> str:
    if kind not in RETRY_KINDS:
        raise GenError(f"{where} 的 retry_kind {kind!r} 不在 {RETRY_KINDS}")
    return "RetryKind." + kind.upper()


def gen_error_codes(out_root: str):
    data = load("contracts/error-codes.json")
    codes = data.get("codes")
    if not isinstance(codes, list) or not codes:
        raise GenError("error-codes.json 没有 codes")
    lines = [
        "/** 错误码的重试类别（contracts/error-codes.yaml 的 retry_kind）。 */",
        "enum class RetryKind(",
        "    val raw: kotlin.String,",
        ") {",
    ]
    lines += [f"    {k.upper()}({kstr(k)})," for k in RETRY_KINDS]
    lines += [
        "}",
        "",
        "/** 一条错误码。文案不在这里，取字典 error.<code>（BR-TEXT-14）。 */",
        "data class ErrorCodeInfo(",
        "    val code: kotlin.Int,",
        "    val http: kotlin.Int,",
        "    val retryKind: RetryKind,",
        "    /** 按 data.reason 区分的重试类别，优先于 [retryKind]。 */",
        "    val retryKindByReason: kotlin.collections.Map<kotlin.String, RetryKind>,",
        "    val isDeprecated: kotlin.Boolean,",
        "    /** phase 为 P1：MVP 不返回。 */",
        "    val isP1: kotlin.Boolean,",
        ")",
        "",
        "/** 错误码表（含废弃与 P1）。 */",
        "object ErrorCodes {",
        "    val all: kotlin.collections.Map<kotlin.Int, ErrorCodeInfo> =",
        "        linkedMapOf(",
    ]
    seen = set()
    for index, c in enumerate(codes):
        where = f"error-codes codes[{index}]"
        code = c.get("code")
        if not isinstance(code, int) or isinstance(code, bool):
            raise GenError(f"{where} 的 code 不是整数")
        if code in seen:
            raise GenError(f"错误码 {code} 重复")
        seen.add(code)
        http = c.get("http")
        if not isinstance(http, int):
            raise GenError(f"{where} 的 http 不是整数")
        by_reason = c.get("retry_kind_by_reason") or {}
        if by_reason:
            reason_text = ", ".join(
                f"{kstr(r)} to {retry_entry(k, f'{where}.{r}')}" for r, k in by_reason.items()
            )
            reason_expr = f"mapOf({reason_text})"
        else:
            reason_expr = "emptyMap()"
        lines += [
            f"            {code} to",
            "                ErrorCodeInfo(",
            f"                    code = {code},",
            f"                    http = {http},",
            f"                    retryKind = {retry_entry(c.get('retry_kind'), where)},",
            f"                    retryKindByReason = {reason_expr},",
            f"                    isDeprecated = {kbool(c.get('deprecated') is True)},",
            f"                    isP1 = {kbool(c.get('phase') == 'P1')},",
            "                ),",
        ]
    lines += [
        "        )",
        "",
        "    fun info(code: kotlin.Int): ErrorCodeInfo? = all[code]",
        "}",
    ]
    return [write(out_root, f"{MAIN_KOTLIN}/ErrorCodes.kt", kotlin_file("\n".join(lines) + "\n"))]


# ---------------------------------------------------------------- 路由


ROUTE_KEYS = {"page", "kind", "h5_path", "params", "auth", "entry", "since", "debug_only", "phase",
              "agent_guide", "agent_guide_account"}
# params schema 里生成器认识的关键字；出现其他关键字即失败（不静默忽略约束）。
PARAM_SCHEMA_KEYS = {"type", "additionalProperties", "properties", "required"}
PARAM_PROPERTY_KEYS = {"type", "enum", "minLength", "maxLength", "pattern", "description", "additionalProperties",
                       "minimum"}
# CT-LOCK-02：a7c9f1fc 的 Agreement.version 是 {"type": "integer", "minimum": 1}，加 integer 与 minimum（三端同口径）。
PARAM_TYPES = {"string": "STRING", "object": "OBJECT", "integer": "INTEGER"}
INTEGER_PARAM_KEYS = {"type", "minimum", "description"}


def kint_or_null(value, where: str) -> str:
    if value is None:
        return "null"
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise GenError(f"{where} 必须是非负整数：{value!r}")
    return str(value)


def route_params_exprs(name: str, r: dict, indent: str) -> list:
    schema = r.get("params")
    where = f"路由 {name} 的 params"
    if not isinstance(schema, dict) or schema.get("type") != "object":
        raise GenError(f"{where} 必须是 type=object 的 JSON Schema")
    unknown = set(schema) - PARAM_SCHEMA_KEYS
    if unknown:
        raise GenError(f"{where} 出现生成器不认识的关键字：{sorted(unknown)}")
    if schema.get("additionalProperties", True) is not False:
        raise GenError(f"{where} 必须 additionalProperties=false")
    props = schema.get("properties") or {}
    required = schema.get("required") or []
    for key in required:
        if key not in props:
            raise GenError(f"{where} 的 required {key!r} 不在 properties 里")
    lines = []
    for key, prop in props.items():
        pw = f"{where}.{key}"
        if not isinstance(prop, dict):
            raise GenError(f"{pw} 必须是对象")
        unknown = set(prop) - PARAM_PROPERTY_KEYS
        if unknown:
            raise GenError(f"{pw} 出现生成器不认识的关键字：{sorted(unknown)}")
        ptype = prop.get("type")
        if ptype not in PARAM_TYPES:
            raise GenError(f"{pw} 的 type {ptype!r} 不支持")
        if ptype == "object" and set(prop) - {"type", "additionalProperties", "description"}:
            raise GenError(f"{pw} 是 object，只支持 additionalProperties")
        if ptype == "string" and "additionalProperties" in prop:
            raise GenError(f"{pw} 是 string，不应有 additionalProperties")
        if ptype == "integer" and set(prop) - INTEGER_PARAM_KEYS:
            raise GenError(f"{pw} 是 integer，只支持 minimum")
        minimum = prop.get("minimum")
        if minimum is not None and ptype != "integer":
            raise GenError(f"{pw} 的 minimum 只用于 integer")
        if minimum is not None and (not isinstance(minimum, int) or isinstance(minimum, bool)):
            raise GenError(f"{pw} 的 minimum 必须是整数：{minimum!r}")
        enum = prop.get("enum")
        if enum is not None and (not isinstance(enum, list) or not all(isinstance(v, str) for v in enum)):
            raise GenError(f"{pw} 的 enum 必须是字符串数组")
        enum_expr = "null" if enum is None else "listOf(" + ", ".join(kstr(v) for v in enum) + ")"
        pattern = prop.get("pattern")
        if pattern is not None and not isinstance(pattern, str):
            raise GenError(f"{pw} 的 pattern 必须是字符串")
        lines += [
            f"{indent}RouteParamSpec(",
            f"{indent}    name = {kstr(key)},",
            f"{indent}    type = RouteParamType.{PARAM_TYPES[ptype]},",
            f"{indent}    required = {kbool(key in required)},",
            f"{indent}    enumValues = {enum_expr},",
            f"{indent}    minLength = {kint_or_null(prop.get('minLength'), pw + '.minLength')},",
            f"{indent}    maxLength = {kint_or_null(prop.get('maxLength'), pw + '.maxLength')},",
            f"{indent}    pattern = {knullable_str(pattern)},",
        ] + ([f"{indent}    minimum = {minimum}L,"] if minimum is not None else []) + [
            f"{indent}),",
        ]
    return lines


def route_spec_expr(name: str, r: dict, route_entry_item, indent: str) -> list:
    unknown = set(r) - ROUTE_KEYS
    if unknown:
        raise GenError(f"路由 {name} 出现生成器不认识的字段：{sorted(unknown)}")
    kind = r.get("kind")
    if kind not in ("native", "h5"):
        raise GenError(f"路由 {name} 的 kind {kind!r} 不支持")
    since = r.get("since") or {}
    entries = r.get("entry") or []
    entry_map = {raw: n for n, raw in route_entry_item["entries"]}
    entry_exprs = []
    for raw in entries:
        if raw not in entry_map:
            raise GenError(f"路由 {name} 的 entry {raw!r} 不在 route_entry 枚举里")
        entry_exprs.append(route_entry_item["type"] + "." + entry_map[raw])
    auth = r.get("auth")
    if not isinstance(auth, str):
        raise GenError(f"路由 {name} 缺 auth")
    agent_guide = r.get("agent_guide", False)
    if not isinstance(agent_guide, bool):
        raise GenError(f"路由 {name} 的 agent_guide 必须是布尔")
    agent_guide_account = r.get("agent_guide_account")
    if agent_guide_account is not None and not isinstance(agent_guide_account, str):
        raise GenError(f"路由 {name} 的 agent_guide_account 必须是字符串")
    if agent_guide and agent_guide_account is not None:
        raise GenError(f"路由 {name} 同时有 agent_guide=true 与 agent_guide_account（契约说明二者互斥）")
    params = route_params_exprs(name, r, indent + "                ")
    params_expr = (
        [f"{indent}        params =", f"{indent}            listOf("] + params + [f"{indent}            ),"]
        if params
        else [f"{indent}        params = emptyList(),"]
    )
    return [
        f"{indent}{kstr(name)} to",
        f"{indent}    RouteSpec(",
        f"{indent}        name = {kstr(name)},",
        f"{indent}        kind = RouteKind.{kind.upper()},",
        f"{indent}        auth = {kstr(auth)},",
        f"{indent}        sinceAndroid = {knullable_str(since.get('android'))},",
        f"{indent}        entries = listOf({', '.join(entry_exprs)}),",
        f"{indent}        h5Path = {knullable_str(r.get('h5_path'))},",
        f"{indent}        debugOnly = {kbool(r.get('debug_only') is True)},",
        f"{indent}        agentGuide = {kbool(agent_guide)},",
        f"{indent}        agentGuideAccount = {knullable_str(agent_guide_account)},",
    ] + params_expr + [
        f"{indent}    ),",
    ]


def gen_routes(out_root: str, enums):
    data = load("contracts/routes.json")
    routes = data.get("routes")
    if not isinstance(routes, dict) or not routes:
        raise GenError("routes.json 没有 routes")
    route_entry_item = enums.get("route_entry")
    if route_entry_item is None:
        raise GenError("缺少枚举 route_entry")
    route_entry_type = route_entry_item["type"]
    normal = [(n, r) for n, r in routes.items() if r.get("debug_only") is not True]
    debug = [(n, r) for n, r in routes.items() if r.get("debug_only") is True]
    lines = [
        "/** 路由类型。 */",
        "enum class RouteKind { NATIVE, H5 }",
        "",
        "/** 路由参数的取值类型（params JSON Schema 的 type）。 */",
        "enum class RouteParamType { STRING, OBJECT, INTEGER }",
        "",
        "/** 路由的一个参数（params JSON Schema 的一项 property；schema 一律 additionalProperties=false）。 */",
        "data class RouteParamSpec(",
        "    val name: kotlin.String,",
        "    val type: RouteParamType,",
        "    val required: kotlin.Boolean,",
        "    val enumValues: kotlin.collections.List<kotlin.String>?,",
        "    val minLength: kotlin.Int?,",
        "    val maxLength: kotlin.Int?,",
        "    /** ECMA 正则（契约当前只有 ^https://）。 */",
        "    val pattern: kotlin.String?,",
        "    /** 整数参数的下限（含）；null 表示没有（CT-LOCK-02：Agreement.version minimum 1）。 */",
        "    val minimum: kotlin.Long? = null,",
        ")",
        "",
        "/** 一条路由（contracts/routes.json）。 */",
        "data class RouteSpec(",
        "    val name: kotlin.String,",
        "    val kind: RouteKind,",
        "    val auth: kotlin.String,",
        "    /** since.android；null 表示本端尚未上线。 */",
        "    val sinceAndroid: kotlin.String?,",
        f"    val entries: kotlin.collections.List<{route_entry_type}>,",
        "    val h5Path: kotlin.String?,",
        "    val debugOnly: kotlin.Boolean,",
        "    /** Agent 页面引导卡能否打开（agent_guide，缺省 false；03 §4.4、BR-ID-10）。 */",
        "    val agentGuide: kotlin.Boolean,",
        "    /** agent_guide_account（phone / delete / fund）：Agent 从不引导、改答文案的账号安全页；null 表示没有。 */",
        "    val agentGuideAccount: kotlin.String?,",
        "    /** params schema 的 properties（additionalProperties 一律 false）。 */",
        "    val params: kotlin.collections.List<RouteParamSpec>,",
        ")",
        "",
        "/**",
        " * 路由表，只含非 debug_only 路由。",
        " * debug_only 路由在 DebugRoutes（src/debugFeatures，只被 debug、staging 变体加入；03 §3.6）。",
        " */",
        "object Routes {",
        "    val all: kotlin.collections.Map<kotlin.String, RouteSpec> =",
        "        linkedMapOf(",
    ]
    for n, r in normal:
        lines += route_spec_expr(n, r, route_entry_item, "            ")
    lines += [
        "        )",
        "",
        "    fun spec(name: kotlin.String): RouteSpec? = all[name]",
        "}",
    ]
    written = [write(out_root, f"{MAIN_KOTLIN}/Routes.kt", kotlin_file("\n".join(lines) + "\n"))]
    dlines = [
        "/** debug_only 路由；本文件只在 debug、staging 变体里编译，release 制品里没有（03 §3.6）。 */",
        "object DebugRoutes {",
        "    /**",
        "     * 足够长的唯一标记：tools/ci/release-scan.sh 在 release 制品里查它，命中即失败；",
        "     * 路由名可能很短、容易与其他字符串误撞，这个标记不会。由 app 的调试面板引用，不会被裁掉。",
        "     */",
        f"    const val MARKER: kotlin.String = {kstr(DEBUG_ROUTES_MARKER)}",
        "",
        "    val all: kotlin.collections.Map<kotlin.String, RouteSpec> =",
        "        linkedMapOf(",
    ]
    for n, r in debug:
        dlines += route_spec_expr(n, r, route_entry_item, "            ")
    dlines += ["        )", "}"]
    written.append(write(out_root, f"{DEBUG_KOTLIN}/DebugRoutes.kt", kotlin_file("\n".join(dlines) + "\n")))
    return written


# ---------------------------------------------------------------- 桥


def gen_bridge(out_root: str):
    data = load("contracts/bridge.schema.json")
    methods = data.get("methods")
    errors = data.get("errors")
    if not isinstance(methods, dict) or not methods:
        raise GenError("bridge.schema.json 没有 methods")
    if not isinstance(errors, dict) or not errors:
        raise GenError("bridge.schema.json 没有 errors")
    lines = [
        "/** 一个桥方法（contracts/bridge.schema.json methods）。缺省：timeoutMs、sinceAndroid 为 null，gestureRequired 为 false。 */",
        "data class BridgeMethodSpec(",
        "    val name: kotlin.String,",
        "    val level: kotlin.String,",
        "    val timeoutMs: kotlin.Int?,",
        "    val sinceAndroid: kotlin.String?,",
        "    val gestureRequired: kotlin.Boolean,",
        ")",
        "",
        "object BridgeMethods {",
        "    val all: kotlin.collections.Map<kotlin.String, BridgeMethodSpec> =",
        "        linkedMapOf(",
    ]
    for name, m in methods.items():
        level = m.get("level")
        if not isinstance(level, str):
            raise GenError(f"桥方法 {name} 缺 level")
        timeout = m.get("timeout_ms")
        if timeout is not None and (not isinstance(timeout, int) or isinstance(timeout, bool)):
            raise GenError(f"桥方法 {name} 的 timeout_ms 不是整数")
        since = m.get("since") or {}
        lines += [
            f"            {kstr(name)} to",
            "                BridgeMethodSpec(",
            f"                    name = {kstr(name)},",
            f"                    level = {kstr(level)},",
            f"                    timeoutMs = {'null' if timeout is None else timeout},",
            f"                    sinceAndroid = {knullable_str(since.get('android'))},",
            f"                    gestureRequired = {kbool(m.get('gesture_required') is True)},",
            "                ),",
        ]
    lines += [
        "        )",
        "",
        "    fun spec(name: kotlin.String): BridgeMethodSpec? = all[name]",
        "}",
        "",
        "/** 桥错误码 → 含义原文（contracts/bridge.schema.json errors）。 */",
        "object BridgeErrors {",
        "    val all: kotlin.collections.Map<kotlin.Int, kotlin.String> =",
        "        linkedMapOf(",
    ]
    for code, meaning in errors.items():
        if not re.fullmatch(r"\d+", code):
            raise GenError(f"桥错误码 {code!r} 不是十进制整数")
        lines.append(f"            {int(code)} to {kstr(str(meaning))},")
    lines += ["        )", "}"]
    # APP-04：ext.openApp 的 trade_only 目标（contracts/apps.json apps.*.trade_only，必填布尔）
    apps = load("contracts/apps.json").get("apps")
    if not isinstance(apps, dict) or not apps:
        raise GenError("apps.json 没有 apps")
    trade_only = []
    for key, app in apps.items():
        flag = app.get("trade_only") if isinstance(app, dict) else None
        if not isinstance(flag, bool):
            raise GenError(f"apps.json 的 {key} 缺布尔 trade_only")
        if flag:
            trade_only.append(key)
    lines += [
        "",
        "/** ext.openApp 的目标（contracts/apps.json apps）：[all] 全部目标，[tradeOnly] 标 trade_only 的电商平台（90403，BR-ATTR-29）。 */",
        "object BridgeAppTargets {",
        "    val all: kotlin.collections.Set<kotlin.String> = linkedSetOf(" + ", ".join(kstr(k) for k in apps) + ")",
        "",
        "    val tradeOnly: kotlin.collections.Set<kotlin.String> = linkedSetOf(" + ", ".join(kstr(k) for k in trade_only) + ")",
        "}",
    ]
    # 桥契约原样复制为 Java 资源（BridgeProtocol 读它；JVM 单测与 Robolectric 都能经 classloader 读到）
    src = os.path.join(SNAPSHOT, "contracts", "bridge.schema.json")
    claim_output(f"{RESOURCES}/bridge.schema.json")
    dest = os.path.join(out_root, RESOURCES, "bridge.schema.json")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    shutil.copyfile(src, dest)
    return [write(out_root, f"{MAIN_KOTLIN}/Bridge.kt", kotlin_file("\n".join(lines) + "\n")), f"{RESOURCES}/bridge.schema.json"]


# ---------------------------------------------------------------- 文案


def gen_texts(out_root: str):
    src = os.path.join(SNAPSHOT, "contracts", "texts.default.json")
    data = load("contracts/texts.default.json")
    if not isinstance(data.get("texts"), dict) or not isinstance(data.get("fallbacks"), dict):
        raise GenError("texts.default.json 缺 texts 或 fallbacks")
    claim_output(f"{RESOURCES}/texts.default.json")
    dest = os.path.join(out_root, RESOURCES, "texts.default.json")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    shutil.copyfile(src, dest)
    body = '''/**
 * 包内默认文案（BR-TEXT-12 唯一来源）。读取 Java 资源 texts.default.json（快照原样复制），
 * 由 classloader 加载，JVM 单测与 Robolectric 都能读到。
 */
object DefaultTexts {
    private const val RESOURCE = "texts.default.json"

    private class Loaded(
        val texts: kotlin.collections.Map<kotlin.String, kotlin.String>,
        val fallbacks: kotlin.collections.Map<kotlin.String, kotlin.String>,
    )

    private val loaded: Loaded by lazy {
        val stream =
            DefaultTexts::class.java.classLoader?.getResourceAsStream(RESOURCE)
                ?: error("missing resource $RESOURCE")
        val root = stream.use { Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
        Loaded(
            texts = root.getValue("texts").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content },
            fallbacks = root.getValue("fallbacks").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content },
        )
    }

    /** texts 里的值。 */
    fun text(key: kotlin.String): kotlin.String? = loaded.texts[key]

    /** fallbacks 里的值。 */
    fun fallback(key: kotlin.String): kotlin.String? = loaded.fallbacks[key]

    /** texts 的全部键（契约顺序）。 */
    val allKeys: kotlin.collections.List<kotlin.String> get() = loaded.texts.keys.toList()
}
'''
    imports = [
        "kotlinx.serialization.json.Json",
        "kotlinx.serialization.json.jsonObject",
        "kotlinx.serialization.json.jsonPrimitive",
    ]
    return [
        write(out_root, f"{MAIN_KOTLIN}/DefaultTexts.kt", kotlin_file(body, imports)),
        f"{RESOURCES}/texts.default.json",
    ]


# ---------------------------------------------------------------- 令牌


def token_nodes(node: dict, prefix: str = ""):
    """令牌叶子（含 value 的对象）：(点分路径, 叶子对象)。"""
    result = []
    for name, child in node.items():
        path = f"{prefix}.{name}" if prefix else name
        if not isinstance(child, dict):
            raise GenError(f"令牌 {path} 不是对象")
        if "value" in child:
            result.append((path, child))
        else:
            result.extend(token_nodes(child, path))
    return result


def token_node_parts(node: dict, prefix: tuple = ()):
    """令牌叶子：(路径段元组, 叶子对象)。消费核对用元组，避免字面点号键与嵌套路径撞成同一字符串；
    含点号的键在任何层级都拒绝，报错带完整路径（父路径 + 键原文）。"""
    result = []
    for name, child in node.items():
        parts = prefix + (name,)
        path = ".".join(parts)
        if "." in name:
            raise GenError(f"令牌 {path} 的键 {name!r} 含点号（与嵌套路径无法区分，不允许）")
        if not isinstance(child, dict):
            raise GenError(f"令牌 {path} 不是对象")
        if "value" in child:
            result.append((parts, child))
        else:
            result.extend(token_node_parts(child, parts))
    return result


def token_leaves(node: dict, prefix: str = ""):
    return [(path, leaf["value"]) for path, leaf in token_nodes(node, prefix)]


def is_int(value) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def is_number(value) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def int_leaves(node: dict, where: str):
    out = []
    for path, value in token_leaves(node):
        if not is_int(value):
            raise GenError(f"令牌 {where}.{path} 不是整数：{value!r}")
        out.append((path, value))
    return out


# 颜色只认 #RRGGBB 与 #RRGGBBAA（末两位为透明度，与令牌源 overlay.scrim 的写法一致）；不收 #RGB / #AARRGGBB 等别的写法。
_COLOR_RE = re.compile(r"#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?")


def check_color(where: str, value) -> str:
    if not isinstance(value, str) or not _COLOR_RE.fullmatch(value):
        raise GenError(f"颜色令牌 {where} 不是 #RRGGBB / #RRGGBBAA：{value!r}")
    return value


def color_leaves(node: dict):
    """color.* → (浅色, 深色)。每个颜色都必须有 dark，缺了报错，不静默回落浅色。"""
    light, dark = [], []
    for path, leaf in token_nodes(node):
        light.append((path, check_color(f"color.{path}", leaf["value"])))
        if "dark" not in leaf:
            raise GenError(f"颜色令牌 color.{path} 缺 dark")
        dark.append((path, check_color(f"color.{path}.dark", leaf["dark"])))
    return light, dark


_SHADOW_FIELDS = ("x", "y", "blur", "spread", "color", "opacity")


def shadow_layers(where: str, layers) -> list:
    if not isinstance(layers, list):
        raise GenError(f"阴影令牌 {where} 不是数组")
    out = []
    for index, layer in enumerate(layers):
        at = f"{where}[{index}]"
        if not isinstance(layer, dict) or set(layer) != set(_SHADOW_FIELDS):
            raise GenError(f"阴影令牌 {at} 须且只能有 {'/'.join(_SHADOW_FIELDS)}")
        for field in ("x", "y", "blur", "spread"):
            if not is_int(layer[field]):
                raise GenError(f"阴影令牌 {at}.{field} 不是整数：{layer[field]!r}")
        if not is_number(layer["opacity"]):
            raise GenError(f"阴影令牌 {at}.opacity 不是数值：{layer['opacity']!r}")
        check_color(f"{at}.color", layer["color"])
        out.append(layer)
    return out


def shadow_leaves(node: dict):
    """shadow.* → (浅色层列表, 深色层列表)。非空阴影必须有 dark；空阴影（shadow.none）无 dark 时深色同为空。"""
    light, dark = [], []
    for path, leaf in token_nodes(node):
        layers = shadow_layers(f"shadow.{path}", leaf["value"])
        light.append((path, layers))
        if "dark" in leaf:
            dark.append((path, shadow_layers(f"shadow.{path}.dark", leaf["dark"])))
        elif layers:
            raise GenError(f"阴影令牌 shadow.{path} 缺 dark")
        else:
            dark.append((path, []))
    return light, dark


def component_leaves(node: dict):
    """component.* 按源的 type 分段：dimension → 整数尺寸；number → 数值（1.5 不截断，整数 3 也走数值段）。"""
    dimensions, numbers = [], []
    for path, leaf in token_nodes(node):
        kind, value = leaf.get("type"), leaf["value"]
        if kind == "dimension":
            if not is_int(value):
                raise GenError(f"令牌 component.{path} 是 dimension 但不是整数：{value!r}")
            dimensions.append((path, value))
        elif kind == "number":
            if not is_number(value):
                raise GenError(f"令牌 component.{path} 是 number 但不是数值：{value!r}")
            numbers.append((path, float(value)))
        else:
            raise GenError(f"令牌 component.{path} 的 type 不是 dimension / number：{kind!r}")
    return dimensions, numbers


def font_family_leaves(node: dict):
    out = []
    for path, value in token_leaves(node):
        if not isinstance(value, list) or not value or not all(isinstance(item, str) for item in value):
            raise GenError(f"令牌 font.family.{path} 不是非空字符串数组")
        out.append((path, value))
    return out


def kmap_lines(name: str, kdoc: str, value_type: str, pairs, render) -> list:
    lines = [f"    /** {kdoc} */", f"    val {name}: kotlin.collections.Map<kotlin.String, {value_type}> =", "        linkedMapOf("]
    for key, value in pairs:
        lines.append(f"            {kstr(key)} to {render(value)},")
    lines.append("        )")
    return lines


def render_shadow(layers: list) -> str:
    if not layers:
        return "kotlin.collections.emptyList()"
    items = ", ".join(
        f"ShadowLayer(x = {layer['x']}, y = {layer['y']}, blur = {layer['blur']}, spread = {layer['spread']}, "
        f"color = {kstr(layer['color'])}, opacity = {float(layer['opacity'])!r})"
        for layer in layers
    )
    return f"kotlin.collections.listOf({items})"


def render_string_list(values: list) -> str:
    return "kotlin.collections.listOf(" + ", ".join(kstr(value) for value in values) + ")"


# 生成器认识的令牌段 → 该段允许的叶子 type。源里每个叶子都必须落在某一段内且 type 相符，
# 否则带完整点分路径报 GenError（不静默丢弃：源里有、生成物里没有的令牌不能悄悄放过）。
TOKEN_SECTION_TYPES = {
    "color": {"color"},
    "shadow": {"shadow"},
    "font.family": {"fontFamily"},
    "font.size": {"fontSize"},
    "font.weight": {"fontWeight"},
    "font.line-height": {"lineHeight"},
    "space": {"dimension"},
    "radius": {"dimension"},
    "focus": {"dimension"},
    "interaction.target": {"dimension"},
    "component": {"dimension", "number"},
}


class TokenConsumer:
    """按段取令牌子树，逐叶核 type 并记下消费过的路径；最后与源的全部叶子做集合核对。"""

    def __init__(self, tokens: dict):
        self.tokens = tokens
        self.consumed = set()
        # 先整树走一遍：含点号的键在任何层级一律拒绝（与嵌套路径无法区分）。
        self.leaves = token_node_parts(tokens)

    def __call__(self, section: str) -> dict:
        allowed = TOKEN_SECTION_TYPES[section]
        node = self.tokens
        for part in section.split("."):
            node = node.get(part) or {}
            if not isinstance(node, dict):
                raise GenError(f"令牌 {section} 不是对象")
        if "value" in node:
            raise GenError(f"令牌 {section} 应是分组，却直接是叶子")
        for parts, leaf in token_node_parts(node, tuple(section.split("."))):
            path = ".".join(parts)
            kind = leaf.get("type")
            if not isinstance(kind, str):
                raise GenError(f"令牌 {path} 的 type {kind!r} 不是字符串（{section} 段只收 {' / '.join(sorted(allowed))}）")
            if kind not in allowed:
                raise GenError(f"令牌 {path} 的 type {kind!r} 不属于 {section} 段（只收 {' / '.join(sorted(allowed))}）")
            self.consumed.add(parts)
        return node

    def check_all_consumed(self) -> None:
        missed = sorted(".".join(parts) for parts, _ in self.leaves if parts not in self.consumed)
        if missed:
            raise GenError("令牌源有生成器不认识的路径（未生成，请先在 gen.py 支持该段）：" + "、".join(missed))


def gen_tokens(out_root: str):
    data = load("design/design-tokens.json")
    version = (data.get("metadata") or {}).get("version")
    if not isinstance(version, str):
        raise GenError("design-tokens.json 缺 metadata.version")
    tokens = data.get("tokens") or {}
    if not isinstance(tokens, dict):
        raise GenError("design-tokens.json 的 tokens 不是对象")
    take = TokenConsumer(tokens)
    colors, colors_dark = color_leaves(take("color"))
    font_family = font_family_leaves(take("font.family"))
    spacing = int_leaves(take("space"), "space")
    radius = int_leaves(take("radius"), "radius")
    font_size = int_leaves(take("font.size"), "font.size")
    font_weight = int_leaves(take("font.weight"), "font.weight")
    line_height = []
    for path, value in token_leaves(take("font.line-height")):
        if not is_number(value):
            raise GenError(f"令牌 font.line-height.{path} 不是数值")
        line_height.append((path, float(value)))
    target = int_leaves(take("interaction.target"), "interaction.target")
    focus = int_leaves(take("focus"), "focus")
    shadows, shadows_dark = shadow_leaves(take("shadow"))
    component, component_number = component_leaves(take("component"))
    take.check_all_consumed()
    lines = [
        "/** 设计令牌原始常量（design/tokens/design-tokens.json）。语义映射在 core:designsystem。 */",
        "object DesignTokens {",
        "    /** metadata.version */",
        f"    const val version: kotlin.String = {kstr(version)}",
        "",
        "    /** 阴影的一层（shadow.* 的数组元素）：偏移 / 模糊 / 扩展为逻辑像素，color 为 #RRGGBB(AA)，opacity 0–1。 */",
        "    data class ShadowLayer(",
        "        val x: kotlin.Int,",
        "        val y: kotlin.Int,",
        "        val blur: kotlin.Int,",
        "        val spread: kotlin.Int,",
        "        val color: kotlin.String,",
        "        val opacity: kotlin.Double,",
        "    )",
        "",
    ]
    lines += kmap_lines("colors", "color.* 点分路径（去掉 color. 前缀）→ 浅色 #RRGGBB / #RRGGBBAA（末两位透明度）", "kotlin.String", colors, kstr)
    lines.append("")
    lines += kmap_lines("colorsDark", "color.*.dark → 深色 #RRGGBB / #RRGGBBAA（键与 colors 相同）", "kotlin.String", colors_dark, kstr)
    lines.append("")
    lines += kmap_lines("spacing", "space.* → 逻辑像素（dp）", "kotlin.Int", spacing, str)
    lines.append("")
    lines += kmap_lines("radius", "radius.* → 逻辑像素（dp）", "kotlin.Int", radius, str)
    lines.append("")
    lines += kmap_lines(
        "fontFamily",
        "font.family.* → 字体回退列表（只导出数据；原生仍用平台系统字体）",
        "kotlin.collections.List<kotlin.String>",
        font_family,
        render_string_list,
    )
    lines.append("")
    lines += kmap_lines("fontSize", "font.size.* → 基础字号（sp，随系统字号缩放）", "kotlin.Int", font_size, str)
    lines.append("")
    lines += kmap_lines("fontWeight", "font.weight.*", "kotlin.Int", font_weight, str)
    lines.append("")
    lines += kmap_lines("lineHeight", "font.line-height.* → 行高倍数", "kotlin.Double", line_height, repr)
    lines.append("")
    lines += kmap_lines("interactionTarget", "interaction.target.* → 最小命中区（逻辑像素）", "kotlin.Int", target, str)
    lines.append("")
    lines += kmap_lines("focus", "focus.* → 焦点环线宽与外距（逻辑像素）", "kotlin.Int", focus, str)
    lines.append("")
    lines += kmap_lines("shadows", "shadow.* → 浅色阴影层（有序；空列表 = 无阴影）", "kotlin.collections.List<ShadowLayer>", shadows, render_shadow)
    lines.append("")
    lines += kmap_lines(
        "shadowsDark", "shadow.*.dark → 深色阴影层（键与 shadows 相同）", "kotlin.collections.List<ShadowLayer>", shadows_dark, render_shadow
    )
    lines.append("")
    lines += kmap_lines("component", "component.*（type=dimension）→ 逻辑像素（dp）", "kotlin.Int", component, str)
    lines.append("")
    lines += kmap_lines("componentNumber", "component.*（type=number）→ 无单位数值（如勾选框放大上限倍数）", "kotlin.Double", component_number, repr)
    lines.append("}")
    return [write(out_root, f"{MAIN_KOTLIN}/DesignTokens.kt", kotlin_file("\n".join(lines) + "\n"))]


# ---------------------------------------------------------------- 商店对照表与版本检查示例（APP-02c）


def gen_app_stores(out_root: str):
    data = load("specs/app-stores.json")
    version = data.get("version")
    stores = data.get("stores")
    if not isinstance(version, str) or not isinstance(stores, list):
        raise GenError("app-stores.json 缺 version 或 stores")
    lines = [
        "/**",
        " * 商店对照表的一行（specs/app-stores.yaml）。值为 null 的商店一律视为「本机打不开」（APP-02c；needs N-17）。",
        " * installSourcePackages：Android 安装来源包名（本机读到的安装来源与它相等即认作这家商店）。",
        " */",
        "data class AppStoreSpec(",
        "    val key: kotlin.String?,",
        "    val name: kotlin.String,",
        "    val platform: kotlin.String,",
        "    val channels: kotlin.collections.List<kotlin.String>,",
        "    val installSourcePackages: kotlin.collections.List<kotlin.String>,",
        "    val openMethod: kotlin.String?,",
        "    val target: kotlin.String?,",
        ")",
        "",
        "/** 商店对照表（specs/app-stores.yaml；规划/03 §4.1、BR-ID-01）。代码里不写死商店键与打开方式。 */",
        "object AppStores {",
        "    /** version */",
        f"    const val version: kotlin.String = {kstr(version)}",
        "",
        "    val all: kotlin.collections.List<AppStoreSpec> =",
        "        listOf(",
    ]
    for index, item in enumerate(stores):
        where = f"app-stores.json stores[{index}]"
        if not isinstance(item, dict):
            raise GenError(f"{where} 不是对象")
        name, platform = item.get("name"), item.get("platform")
        if not isinstance(name, str) or not isinstance(platform, str):
            raise GenError(f"{where} 缺 name 或 platform")
        key = item.get("key")
        if key is not None and (not isinstance(key, str) or not re.fullmatch(r"[a-z0-9_]+", key)):
            raise GenError(f"{where} 的 key 不是小写字母、数字、下划线：{key!r}")
        channels = item.get("channels") or []
        packages = item.get("install_source_packages") or []
        if not all(isinstance(v, str) for v in channels) or not all(isinstance(v, str) for v in packages):
            raise GenError(f"{where} 的 channels / install_source_packages 不是字符串列表")
        detail = item.get("detail_page") or {}
        open_method, target = detail.get("open_method"), detail.get("target")
        for label, value in (("open_method", open_method), ("target", target)):
            if value is not None and not isinstance(value, str):
                raise GenError(f"{where} 的 detail_page.{label} 不是字符串或 null")
        lines += [
            "            AppStoreSpec(",
            f"                key = {knullable_str(key)},",
            f"                name = {kstr(name)},",
            f"                platform = {kstr(platform)},",
            "                channels = listOf(" + ", ".join(kstr(v) for v in channels) + "),",
            "                installSourcePackages = listOf(" + ", ".join(kstr(v) for v in packages) + "),",
            f"                openMethod = {knullable_str(open_method)},",
            f"                target = {knullable_str(target)},",
            "            ),",
        ]
    lines += [
        "        )",
        "",
        "    /** 某一端（client_platform：ios / android / harmony）的商店。 */",
        "    fun forPlatform(platform: kotlin.String): kotlin.collections.List<AppStoreSpec> = all.filter { it.platform == platform }",
        "}",
    ]
    return [write(out_root, f"{MAIN_KOTLIN}/AppStores.kt", kotlin_file("\n".join(lines) + "\n"))]


def gen_app_version_check_example(out_root: str):
    data = load("contracts/openapi.json")
    try:
        response = data["paths"][APP_VERSION_CHECK_PATH]["get"]["responses"]["200"]
        example = response["content"]["application/json"]["example"]
    except (KeyError, TypeError) as error:
        raise GenError(f"openapi.json 里没有 GET {APP_VERSION_CHECK_PATH} 的 200 响应示例（{error}）") from error
    body = example.get("data") if isinstance(example, dict) else None
    if not isinstance(body, dict):
        raise GenError(f"GET {APP_VERSION_CHECK_PATH} 的响应示例没有 data 对象")
    text = json.dumps(body, ensure_ascii=False, separators=(",", ":"))
    lines = [
        "/**",
        f" * openapi GET {APP_VERSION_CHECK_PATH} 的 200 响应示例里的 data（AppVersionCheck），原样转写。",
        " * 只在 debug、staging 编译（调试面板的版本检查假数据，APP-02c）；Release 里没有，release-scan 查 [MARKER]。",
        " */",
        "object AppVersionCheckExample {",
        f"    const val MARKER: kotlin.String = {kstr(UPDATE_EXAMPLE_MARKER)}",
        "",
        f"    const val DATA_JSON: kotlin.String = {kstr(text)}",
        "}",
    ]
    return [write(out_root, f"{DEBUG_KOTLIN}/AppVersionCheckExample.kt", kotlin_file("\n".join(lines) + "\n"))]


# ---------------------------------------------------------------- openapi 操作登记表（APP-03a）

HTTP_METHODS = ["get", "post", "put", "patch", "delete"]
# x-min-version-gate：true（受闸门约束）/ false（例外）/ "conditional"（按请求体条件）/ 未标注。
MIN_VERSION_GATES = {True: "GATED", False: "EXEMPT", "conditional": "CONDITIONAL", None: "UNSPECIFIED"}
OPERATION_EXTENSIONS = {
    "x-auth", "x-signed", "x-idempotent", "x-step-up", "x-error-codes", "x-session-scopes",
    "x-min-version-gate", "x-implementation",
}
# CT-LOCK-01：后台接口（/admin/v1/*，x-auth admin / super）不给 App 生成，登记表只收客户端路径（与鸿蒙同口径）。
EXCLUDED_PATH_PREFIXES = ("/admin/",)


def gen_api_operations(out_root: str):
    data = load("contracts/openapi.json")
    paths = data.get("paths")
    if not isinstance(paths, dict) or not paths:
        raise GenError("openapi.json 没有 paths")
    entries = []
    seen = set()
    for path, item in paths.items():
        if not isinstance(item, dict):
            raise GenError(f"openapi.json paths[{path!r}] 不是对象")
        if path.startswith(EXCLUDED_PATH_PREFIXES):
            continue
        for method in HTTP_METHODS:
            op = item.get(method)
            if op is None:
                continue
            where = f"openapi.json {method.upper()} {path}"
            op_id = op.get("operationId")
            if not isinstance(op_id, str) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9]*", op_id):
                raise GenError(f"{where} 的 operationId 不合法：{op_id!r}")
            if op_id in seen:
                raise GenError(f"operationId 重复：{op_id}")
            seen.add(op_id)
            unknown = sorted(k for k in op if k.startswith("x-") and k not in OPERATION_EXTENSIONS)
            if unknown:
                raise GenError(f"{where} 有不认识的扩展字段：{unknown}")
            auth = op.get("x-auth")
            # CT-LOCK-02：a7c9f1fc 起客户端接口出现 x-auth phone（已登录且绑了手机号；鸿蒙早已认），CT-LOCK-01 G16 收口。
            if auth not in ("none", "optional", "login", "phone", "realname"):
                raise GenError(f"{where} 的 x-auth 不认识：{auth!r}")
            flags = {}
            for key in ("x-signed", "x-idempotent"):
                value = op.get(key, False)
                if not isinstance(value, bool):
                    raise GenError(f"{where} 的 {key} 不是布尔：{value!r}")
                flags[key] = value
            step_up = op.get("x-step-up")
            if step_up is not None and not isinstance(step_up, str):
                raise GenError(f"{where} 的 x-step-up 不是字符串：{step_up!r}")
            codes = op.get("x-error-codes", [])
            if not isinstance(codes, list) or not all(isinstance(c, int) and not isinstance(c, bool) for c in codes):
                raise GenError(f"{where} 的 x-error-codes 不是整数列表")
            # BR-ID-01 细则「受限会话」：未标注时取 [full]
            scopes = op.get("x-session-scopes", ["full"])
            if not isinstance(scopes, list) or not all(isinstance(v, str) for v in scopes) or not scopes:
                raise GenError(f"{where} 的 x-session-scopes 不是非空字符串列表")
            gate = op.get("x-min-version-gate")
            gate_key = gate if isinstance(gate, bool) or gate is None else str(gate)
            if gate_key not in MIN_VERSION_GATES:
                raise GenError(f"{where} 的 x-min-version-gate 不认识：{gate!r}")
            implementation = op.get("x-implementation")
            if implementation not in (None, "planned"):
                raise GenError(f"{where} 的 x-implementation 不认识：{implementation!r}")
            entries.append(
                [
                    f"            {kstr(op_id)} to",
                    "                ApiOperation(",
                    f"                    operationId = {kstr(op_id)},",
                    f"                    method = {kstr(method.upper())},",
                    f"                    pathTemplate = {kstr(path)},",
                    f"                    auth = {kstr(auth)},",
                    f"                    signed = {kbool(flags['x-signed'])},",
                    f"                    idempotent = {kbool(flags['x-idempotent'])},",
                    f"                    stepUp = {knullable_str(step_up)},",
                    "                    errorCodes = listOf(" + ", ".join(str(c) for c in codes) + "),",
                    "                    sessionScopes = listOf(" + ", ".join(kstr(v) for v in scopes) + "),",
                    f"                    minVersionGate = ApiMinVersionGate.{MIN_VERSION_GATES[gate_key]},",
                    f"                    planned = {kbool(implementation == 'planned')},",
                    "                ),",
                ]
            )
    lines = [
        "/** openapi 的 x-min-version-gate（BR-ID-01 细则「最低支持版本的接口层拦截」）。 */",
        "enum class ApiMinVersionGate {",
        "    /** true：受闸门约束。 */",
        "    GATED,",
        "",
        "    /** false：不受约束（例外）。 */",
        "    EXEMPT,",
        "",
        "    /** conditional：按请求体条件（operation 说明里写条件）。 */",
        "    CONDITIONAL,",
        "",
        "    /** 未标注（只读接口）。 */",
        "    UNSPECIFIED,",
        "}",
        "",
        "/**",
        " * openapi 的一个 operation（contracts/openapi.yaml）。只登记方法、路径与扩展字段，不含请求 / 响应模型。",
        " * [sessionScopes] 未标注时为 [full]（BR-ID-01 细则「受限会话」）；[planned] = x-implementation: planned。",
        " */",
        "data class ApiOperation(",
        "    val operationId: kotlin.String,",
        "    /** 大写 HTTP 方法。 */",
        "    val method: kotlin.String,",
        "    val pathTemplate: kotlin.String,",
        "    /** x-auth：none / optional / login / phone / realname。 */",
        "    val auth: kotlin.String,",
        "    val signed: kotlin.Boolean,",
        "    val idempotent: kotlin.Boolean,",
        "    /** x-step-up 的 action；不需要二次验证为 null。 */",
        "    val stepUp: kotlin.String?,",
        "    val errorCodes: kotlin.collections.List<kotlin.Int>,",
        "    val sessionScopes: kotlin.collections.List<kotlin.String>,",
        "    val minVersionGate: ApiMinVersionGate,",
        "    val planned: kotlin.Boolean,",
        ") {",
        "    /** 只读接口（GET）。 */",
        "    val isRead: kotlin.Boolean get() = method == \"GET\"",
        "}",
        "",
        "/** 全部 operation，按 openapi 出现顺序。 */",
        "object ApiOperations {",
        "    val all: kotlin.collections.Map<kotlin.String, ApiOperation> =",
        "        linkedMapOf(",
    ]
    for entry in entries:
        lines += entry
    lines += [
        "        )",
        "",
        "    fun byId(operationId: kotlin.String): ApiOperation? = all[operationId]",
        "}",
    ]
    content = kotlin_file("\n".join(lines) + "\n", package=API_PACKAGE)
    return [write(out_root, f"{API_KOTLIN}/ApiOperations.kt", content)]

# ---------------------------------------------------------------- Tracker 事件（APP-03b）

# 03 §4.7、02 §12.6：事件字段不带完整推广 URL、口令、推广位或 relation_id 等渠道标识，也不带令牌、密钥与设备原始标识。
# 字段名（按 _ 分段）命中任一即失败。
FORBIDDEN_EVENT_FIELD = re.compile(
    r"(^|_)(url|uri|href|tpwd|kouling|password|passwd|pwd|pid|adzone|adzone_id|position_id|relation_id|special_id|"
    r"token|access_token|refresh_token|h5_token|secret|install_secret|sign|phone|mobile|id_card|idfa|idfv|oaid|odid|"
    r"android_id|imei)($|_)"
)
EVENT_FIELD_TYPES = {"string": "kotlin.String", "integer": "kotlin.Long", "boolean": "kotlin.Boolean"}
# 生成函数的公共参数（events.yaml common_fields 里由业务传入的部分；其余由 Tracker 补齐）。
EVENT_CALLER_COMMON = ["page", "spm", "trace_id"]


def check_event_field_name(name: str, where: str) -> None:
    if not isinstance(name, str) or not re.fullmatch(r"[a-z][a-z0-9_]*", name):
        raise GenError(f"{where} 字段名不是小写蛇形：{name!r}")
    if FORBIDDEN_EVENT_FIELD.search(name):
        raise GenError(f"{where} 字段名 {name!r} 命中禁用规则（03 §4.7：不为推广 URL、口令、推广位、relation_id、令牌、密钥、设备原始标识设字段）")


def kparam(name: str) -> str:
    camel = lower_camel(name)
    return f"`{camel}`" if camel in KOTLIN_HARD_KEYWORDS else camel


def gen_client_events(out_root: str):
    data = load("specs/events.json")
    common = data.get("common_fields")
    events = data.get("events")
    if not isinstance(common, dict) or not isinstance(events, dict) or not events:
        raise GenError("events.json 缺 common_fields 或 events")
    for name in common:
        check_event_field_name(name, "events.json common_fields")
    for name in EVENT_CALLER_COMMON:
        if name not in common:
            raise GenError(f"events.json common_fields 缺 {name}")
    enum_types = {}
    enum_blocks = []
    functions = []
    names = []
    for event, spec in events.items():
        where = f"events.json events.{event}"
        if not isinstance(event, str) or not re.fullmatch(r"[a-z][a-z0-9_]*", event):
            raise GenError(f"{where} 事件名不是小写蛇形")
        if not isinstance(spec, dict) or not isinstance(spec.get("fields"), dict):
            raise GenError(f"{where} 缺 fields")
        names.append(event)
        required = []
        optional = []
        checks = []
        puts = []
        for field, fspec in spec["fields"].items():
            fwhere = f"{where}.fields.{field}"
            check_event_field_name(field, fwhere)
            if field in common:
                raise GenError(f"{fwhere} 与公共字段同名")
            if not isinstance(fspec, dict):
                raise GenError(f"{fwhere} 不是对象")
            ftype = fspec.get("type")
            param = kparam(field)
            if ftype == "enum":
                values = fspec.get("values")
                if not isinstance(values, list) or not values or not all(isinstance(v, str) for v in values):
                    raise GenError(f"{fwhere} 的 values 不是非空字符串列表")
                type_name = upper_camel(event) + upper_camel(field)
                folded = type_name.casefold()
                if folded in RESERVED_TYPE_NAMES_FOLDED or folded in enum_types:
                    raise GenError(f"{fwhere} 的类型名 {type_name} 与其他类型名冲突")
                enum_types[folded] = type_name
                entries = []
                seen_entries = set()
                for value in values:
                    entry = entry_name(value)
                    if entry.casefold() in seen_entries:
                        raise GenError(f"{fwhere} 的取值 {value!r} 转成 entry 名后重复")
                    seen_entries.add(entry.casefold())
                    entries.append(f"    {entry}({kstr(value)}),")
                enum_blocks.append(
                    "\n".join(
                        [
                            f"/** events.yaml {event}.{field}：{comment_text(fspec.get('note', ''))} */",
                            f"enum class {type_name}(",
                            "    val raw: kotlin.String,",
                            ") {",
                            *entries,
                            "}",
                        ]
                    )
                )
                ktype = type_name
                value_expr = f"{param}.raw"
            elif ftype in EVENT_FIELD_TYPES:
                ktype = EVENT_FIELD_TYPES[ftype]
                value_expr = param
            else:
                raise GenError(f"{fwhere} 的 type 不认识：{ftype!r}")
            is_required = fspec.get("required") is True
            required_when = fspec.get("required_when")
            if required_when is not None:
                if is_required or not isinstance(required_when, dict) or len(required_when) != 1:
                    raise GenError(f"{fwhere} 的 required_when 只能是单个条件")
                (cond_field, cond_value), = required_when.items()
                cond = spec["fields"].get(cond_field)
                if not isinstance(cond, dict) or cond.get("type") != "enum" or cond.get("required") is not True:
                    raise GenError(f"{fwhere} 的 required_when 只能引用必填的枚举字段")
                if cond_value not in cond.get("values", []):
                    raise GenError(f"{fwhere} 的 required_when 取值 {cond_value!r} 不在 {cond_field} 的取值里")
                cond_type = upper_camel(event) + upper_camel(cond_field)
                checks.append(
                    f"        require({kparam(cond_field)} != {cond_type}.{entry_name(cond_value)} || {param} != null) "
                    f"{{ {kstr(f'{event}.{field} 在 {cond_field}={cond_value} 时必填')} }}"
                )
            if is_required:
                required.append(f"        {param}: {ktype},")
                puts.append(f"                put({kstr(field)}, kotlinx.serialization.json.JsonPrimitive({value_expr}))")
            else:
                optional.append(f"        {param}: {ktype}? = null,")
                nullable_expr = value_expr.replace(f"{param}.raw", f"{param}?.raw")
                puts.append(
                    f"                {nullable_expr}?.let {{ put({kstr(field)}, kotlinx.serialization.json.JsonPrimitive(it)) }}"
                    if ftype == "enum"
                    else f"                {param}?.let {{ put({kstr(field)}, kotlinx.serialization.json.JsonPrimitive(it)) }}"
                )
        fn = [
            f"    /** {event}：{comment_text(spec.get('description', ''))}（{comment_text(spec.get('source', ''))}） */",
            f"    fun {lower_camel(event)}(",
            "        page: kotlin.String,",
            *required,
            *optional,
            "        spm: kotlin.String? = null,",
            "        traceId: kotlin.String? = null,",
            "    ): ClientEvent {",
            *checks,
            "        val fields =",
            "            kotlin.collections.buildMap<kotlin.String, kotlinx.serialization.json.JsonPrimitive> {",
            *puts,
            "            }",
            f"        return ClientEvent({kstr(event)}, page, spm, traceId, fields)",
            "    }",
        ]
        functions.append("\n".join(fn))
    lines = [
        "/**",
        " * 一个待采集的事件（specs/events.yaml）：事件名、业务传入的公共字段（page、spm、trace_id）与事件字段。",
        " * 其余公共字段（event_id、client_at、session_id、device_id、user_id、platform、app_version、channel）由 Tracker 补齐。",
        " * 构造函数不公开：业务代码只能调用 [ClientEvents] 的生成函数（03 §4.7、拍板第二批 TECH-21）。",
        " */",
        "class ClientEvent internal constructor(",
        "    val name: kotlin.String,",
        "    val page: kotlin.String,",
        "    val spm: kotlin.String?,",
        "    val traceId: kotlin.String?,",
        "    val fields: kotlin.collections.Map<kotlin.String, kotlinx.serialization.json.JsonPrimitive>,",
        ")",
        "",
        "/** specs/events.yaml 的事件构造函数（events.yaml version " + comment_text(str(data.get("version"))) + "）。事件族（families）随各自任务补。 */",
        "object ClientEvents {",
        "    /** 全部事件名（契约顺序）。 */",
        "    val names: kotlin.collections.List<kotlin.String> =",
        "        listOf(" + ", ".join(kstr(n) for n in names) + ")",
        "",
        "\n\n".join(functions),
        "}",
        "",
        "\n\n".join(enum_blocks),
    ]
    content = kotlin_file("\n".join(lines) + "\n", package=CLIENT_CORE_PACKAGE)
    return [write(out_root, f"{CLIENT_CORE_KOTLIN}/ClientEvents.kt", content)]


# ---------------------------------------------------------------- 设备哈希种子（APP-03b）


def gen_device_hash_seeds(out_root: str):
    data = load("specs/device-hash.vectors.json")
    seeds = data.get("invalid_hash_seeds")
    if not isinstance(seeds, list) or not seeds:
        raise GenError("device-hash.vectors.json 缺 invalid_hash_seeds")
    hashes = []
    for index, item in enumerate(seeds):
        value = item.get("device_hash") if isinstance(item, dict) else None
        if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value):
            raise GenError(f"device-hash.vectors.json invalid_hash_seeds[{index}] 的 device_hash 不是 64 位小写十六进制")
        hashes.append(value)
    lines = [
        "/**",
        " * 无效设备哈希清单的种子（specs/device-hash.vectors.json invalid_hash_seeds；BR-ID-09 细则「设备标识的无效值」，",
        " * 即配置 device.invalid_hashes 的初始值）。客户端在上报前排除命中的值（已知公共固定值等），不求哈希上报。",
        " */",
        "object DeviceHashSeeds {",
        "    val invalidHashes: kotlin.collections.Set<kotlin.String> =",
        "        setOf(",
        *[f"            {kstr(h)}," for h in hashes],
        "        )",
        "}",
    ]
    return [write(out_root, f"{CLIENT_CORE_KOTLIN}/DeviceHashSeeds.kt", kotlin_file("\n".join(lines) + "\n", package=CLIENT_CORE_PACKAGE))]


# ---------------------------------------------------------------- 包内默认配置（APP-03b）

CONFIG_PATH = "/v1/config"
# 示例里的地址类字段（示例域名不是生产地址，不进包）。
# CT-LOCK-01：platform_icons（CT-17f，替换图地址与摘要）同样不进包——包内默认配置没有替换图，客户端显示包内平台标识；
# 下发接入归 F-X-05。
BUNDLED_CONFIG_DROPPED_KEYS = ["h5_base_url", "share_domains", "kf", "external_hosts", "platform_icons"]
# CT-LOCK-02：a7c9f1fc 的示例多了 clipboard.consent_version（示例值，不是生产的功能同意版本）。本端剪贴板自动读取按
# 「配置缺 consent_version 一律不许读、不用包内值兜底」（G47、BR-ID-16）；包内带了示例值就会在没有 LKG 时让用户按一个
# 示例版本做功能同意，所以不进包（与 platform_icons 同理：升锁不改现有行为）。clipboard 节里其余透传字段照留。
BUNDLED_CONFIG_DROPPED_NESTED = [("clipboard", "consent_version")]
NON_PROD_ADDRESS = re.compile(r"(?i)https?://|\.test\b|\.invalid\b|\.example\b|\.local\b|example\.(com|net|org)\b")


def string_leaves(node, path="$"):
    if isinstance(node, dict):
        for key, value in node.items():
            yield from string_leaves(value, f"{path}.{key}")
    elif isinstance(node, list):
        for index, value in enumerate(node):
            yield from string_leaves(value, f"{path}[{index}]")
    elif isinstance(node, str):
        yield path, node


def gen_bundled_config(out_root: str):
    data = load("contracts/openapi.json")
    try:
        example = data["paths"][CONFIG_PATH]["get"]["responses"]["200"]["content"]["application/json"]["example"]
    except (KeyError, TypeError) as error:
        raise GenError(f"openapi.json 里没有 GET {CONFIG_PATH} 的 200 响应示例（{error}）") from error
    body = example.get("data") if isinstance(example, dict) else None
    if not isinstance(body, dict):
        raise GenError(f"GET {CONFIG_PATH} 的响应示例没有 data 对象")
    config = {key: value for key, value in body.items() if key not in BUNDLED_CONFIG_DROPPED_KEYS}
    for section, key in BUNDLED_CONFIG_DROPPED_NESTED:
        if isinstance(config.get(section), dict) and key in config[section]:
            config[section] = {k: v for k, v in config[section].items() if k != key}
    patterns = config.get("link_patterns")
    if not isinstance(patterns, dict) or not isinstance(patterns.get("version"), str):
        raise GenError(f"GET {CONFIG_PATH} 的响应示例缺 link_patterns.version")
    # 示例规则里的主机是示例域名：包内快照只留版本，规则为空（TODO(N-21)：发布构建取生产链接形态表）
    config["link_patterns"] = {"version": patterns["version"], "rules": []}
    for path, text in string_leaves(config):
        if NON_PROD_ADDRESS.search(text):
            raise GenError(f"包内默认配置 {path} 含非生产地址：{text!r}")
    claim_output(f"{RESOURCES}/default_config.json")
    dest = os.path.join(out_root, RESOURCES, "default_config.json")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(config, ensure_ascii=False, indent=2) + "\n")
    body_kt = """/**
 * 包内默认配置（03 §4.3）：没有当前配置与 LKG 时使用。读取 Java 资源 default_config.json（由 openapi GET /v1/config 的
 * 200 响应示例的 data 生成，去掉示例里的地址类字段，link_patterns 只留版本、规则为空）。
 * TODO(N-21): 发布构建时按生产配置当时的值生成（features.search_status、link_patterns 等，03 §4.3），不用契约示例。
 */
object BundledConfig {
    private const val RESOURCE = "default_config.json"

    /** data 对象的 JSON 文本。 */
    val dataJson: kotlin.String by lazy {
        val stream =
            BundledConfig::class.java.classLoader?.getResourceAsStream(RESOURCE)
                ?: error("missing resource $RESOURCE")
        stream.use { it.readBytes().decodeToString() }
    }
}
"""
    return [
        write(out_root, f"{CLIENT_CORE_KOTLIN}/BundledConfig.kt", kotlin_file(body_kt, package=CLIENT_CORE_PACKAGE)),
        f"{RESOURCES}/default_config.json",
    ]


HOME_VECTORS = "specs/home-sdui-vectors.json"


def gen_bundled_home(out_root: str):
    """包内 default_home.json（APP-11 G17）：逐项取向量 goldenPages.default_home（三端同一来源），按固定格式序列化
    （json.dumps(ensure_ascii=False, indent=2) + 换行，与鸿蒙 rawfile/default_home.json 逐字一致）；--check 保证与向量一致。"""
    path = os.path.join(ROOT, HOME_VECTORS)
    if not os.path.isfile(path):
        raise GenError("缺 " + HOME_VECTORS)
    with open(path, encoding="utf-8") as f:
        vectors = json.load(f)
    page = vectors.get("goldenPages", {}).get("default_home")
    if not isinstance(page, dict):
        raise GenError(HOME_VECTORS + " 缺 goldenPages.default_home")
    for where, text in string_leaves(page):
        if re.search(r"https?://", text):
            raise GenError(f"包内默认首页不得引用网络图片或链接：{where} {text!r}")
    claim_output(f"{RESOURCES}/default_home.json")
    dest = os.path.join(out_root, RESOURCES, "default_home.json")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(page, ensure_ascii=False, indent=2) + "\n")
    body_kt = """/**
 * 包内默认首页（APP-11 G17；03 §6.2 第 4 步三级兜底的最后一级）：读取 Java 资源 default_home.json（由 specs/home-sdui-vectors.json 的
 * goldenPages.default_home 逐项生成，三端同一来源）。只含不依赖数据源的金刚区与双入口卡；没有 banner、公告、商品卡、popup，不引用网络图片。
 */
object BundledHome {
    const val RESOURCE: kotlin.String = "default_home.json"

    /** 页面 JSON 文本；读不出为 null（首页按包内默认解码失败处理，显示 StateLoadFailed）。 */
    fun pageJson(): kotlin.String? =
        runCatching {
            BundledHome::class.java.classLoader?.getResourceAsStream(RESOURCE)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
}
"""
    return [
        write(out_root, f"{CLIENT_CORE_KOTLIN}/BundledHome.kt", kotlin_file(body_kt, package=CLIENT_CORE_PACKAGE)),
        f"{RESOURCES}/default_home.json",
    ]


# ---------------------------------------------------------------- 入口


def generate(out_root: str) -> list:
    _WRITTEN.clear()
    written, enums = gen_enums(out_root)
    written += gen_error_codes(out_root)
    written += gen_routes(out_root, enums)
    written += gen_bridge(out_root)
    written += gen_texts(out_root)
    written += gen_tokens(out_root)
    written += gen_app_stores(out_root)
    written += gen_app_version_check_example(out_root)
    written += gen_api_operations(out_root)
    written += gen_client_events(out_root)
    written += gen_device_hash_seeds(out_root)
    written += gen_bundled_config(out_root)
    written += gen_bundled_home(out_root)
    return sorted(written)


def owned_files(root: str) -> set:
    found = set()
    for rel_dir in OWNED_DIRS:
        base = os.path.join(root, rel_dir)
        if not os.path.isdir(base):
            continue
        for directory, _, names in os.walk(base):
            for name in names:
                full = os.path.join(directory, name)
                found.add(os.path.relpath(full, root).replace(os.sep, "/"))
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="只检查生成物是否与快照一致")
    args = parser.parse_args()
    try:
        if args.check:
            with tempfile.TemporaryDirectory(prefix="couli-gen-") as tmp:
                expected = set(generate(tmp))
                actual = owned_files(ROOT)
                drift = []
                for rel in sorted(expected | actual):
                    a = os.path.join(ROOT, rel)
                    b = os.path.join(tmp, rel)
                    if rel not in actual:
                        drift.append(f"缺少  {rel}")
                    elif rel not in expected:
                        drift.append(f"多余  {rel}")
                    elif not filecmp.cmp(a, b, shallow=False):
                        drift.append(f"不一致 {rel}")
                if drift:
                    print("gen.py --check：生成物与快照不一致（运行 python3 tools/codegen/gen.py 重新生成）：", file=sys.stderr)
                    for line in drift:
                        print("  " + line, file=sys.stderr)
                    return 1
                print(f"gen.py --check：{len(expected)} 个生成物与快照一致")
                return 0
        with tempfile.TemporaryDirectory(prefix="couli-gen-") as tmp:
            expected = generate(tmp)
            for rel in sorted(owned_files(ROOT) - set(expected)):
                os.remove(os.path.join(ROOT, rel))
                print(f"删除多余 {rel}")
            for rel in expected:
                dest = os.path.join(ROOT, rel)
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                shutil.copyfile(os.path.join(tmp, rel), dest)
        print(f"gen.py：已生成 {len(expected)} 个文件")
        return 0
    except GenError as error:
        print(f"gen.py：{error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
