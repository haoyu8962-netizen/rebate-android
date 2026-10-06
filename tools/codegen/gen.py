#!/usr/bin/env python3
"""契约快照 → Kotlin 生成物（core:generated）。

只读 contracts/snapshot/，输出：
  core/generated/src/main/kotlin/com/couli/generated/*.kt
  core/generated/src/debugFeatures/kotlin/com/couli/generated/DebugRoutes.kt   （只被 debug、staging 加入）
  core/generated/src/debugFeatures/kotlin/com/couli/generated/AppVersionCheckExample.kt （同上；APP-02c 调试假数据）
  core/generated/src/main/resources/texts.default.json                         （快照原样复制）

用法：
  python3 tools/codegen/gen.py           生成（覆盖上述文件，删除输出目录里多余的旧文件）
  python3 tools/codegen/gen.py --check   在临时目录生成后逐字比较；不一致退出 1 并列出文件

本任务生成：枚举、错误码、路由、桥、文案、令牌；不生成 OpenAPI 客户端、事件、apps.json 片段
（分别归 APP-03、APP-03、APP-04/06）。API 形状见 APP-01-android 任务书 §5。
APP-02c 加：商店对照表 specs/app-stores.yaml → AppStores（main）；openapi GET /v1/app-versions/check 的 200 响应示例
→ AppVersionCheckExample（debugFeatures，调试面板的版本检查假数据，Release 里没有）。
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
OWNED_DIRS = [MAIN_KOTLIN, DEBUG_KOTLIN, RESOURCES]

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
    "DefaultTexts",
    "DesignTokens",
    "AppStoreSpec",
    "AppStores",
    "AppVersionCheckExample",
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


def kotlin_file(body: str, imports=(), suppress=()) -> str:
    lines = [HEADER.rstrip("\n")]
    if suppress:
        lines.append("@file:Suppress(" + ", ".join(kstr(s) for s in suppress) + ")")
        lines.append("")
    lines.append(f"package {PACKAGE}")
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
PARAM_PROPERTY_KEYS = {"type", "enum", "minLength", "maxLength", "pattern", "description", "additionalProperties"}
PARAM_TYPES = {"string": "STRING", "object": "OBJECT"}


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
        "enum class RouteParamType { STRING, OBJECT }",
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
    return [write(out_root, f"{MAIN_KOTLIN}/Bridge.kt", kotlin_file("\n".join(lines) + "\n"))]


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


def token_leaves(node: dict, prefix: str = ""):
    result = []
    for name, child in node.items():
        path = f"{prefix}.{name}" if prefix else name
        if not isinstance(child, dict):
            raise GenError(f"令牌 {path} 不是对象")
        if "value" in child:
            result.append((path, child["value"]))
        else:
            result.extend(token_leaves(child, path))
    return result


def int_leaves(node: dict, where: str):
    out = []
    for path, value in token_leaves(node):
        if not isinstance(value, int) or isinstance(value, bool):
            raise GenError(f"令牌 {where}.{path} 不是整数：{value!r}")
        out.append((path, value))
    return out


def kmap_lines(name: str, kdoc: str, value_type: str, pairs, render) -> list:
    lines = [f"    /** {kdoc} */", f"    val {name}: kotlin.collections.Map<kotlin.String, {value_type}> =", "        linkedMapOf("]
    for key, value in pairs:
        lines.append(f"            {kstr(key)} to {render(value)},")
    lines.append("        )")
    return lines


def gen_tokens(out_root: str):
    data = load("design/design-tokens.json")
    version = (data.get("metadata") or {}).get("version")
    if not isinstance(version, str):
        raise GenError("design-tokens.json 缺 metadata.version")
    tokens = data.get("tokens") or {}
    colors = []
    for path, value in token_leaves(tokens.get("color") or {}):
        if not isinstance(value, str) or not re.fullmatch(r"#[0-9A-Fa-f]{6}", value):
            raise GenError(f"颜色令牌 color.{path} 不是 #RRGGBB：{value!r}")
        colors.append((path, value))
    font = tokens.get("font") or {}
    spacing = int_leaves(tokens.get("space") or {}, "space")
    radius = int_leaves(tokens.get("radius") or {}, "radius")
    font_size = int_leaves(font.get("size") or {}, "font.size")
    font_weight = int_leaves(font.get("weight") or {}, "font.weight")
    line_height = []
    for path, value in token_leaves(font.get("line-height") or {}):
        if not isinstance(value, (int, float)) or isinstance(value, bool):
            raise GenError(f"令牌 font.line-height.{path} 不是数值")
        line_height.append((path, float(value)))
    target = int_leaves((tokens.get("interaction") or {}).get("target") or {}, "interaction.target")
    focus = int_leaves(tokens.get("focus") or {}, "focus")
    lines = [
        "/** 设计令牌原始常量（design/tokens/design-tokens.json）。语义映射在 core:designsystem。 */",
        "object DesignTokens {",
        "    /** metadata.version */",
        f"    const val version: kotlin.String = {kstr(version)}",
        "",
    ]
    lines += kmap_lines("colors", "color.* 点分路径（去掉 color. 前缀）→ #RRGGBB", "kotlin.String", colors, kstr)
    lines.append("")
    lines += kmap_lines("spacing", "space.* → 逻辑像素（dp）", "kotlin.Int", spacing, str)
    lines.append("")
    lines += kmap_lines("radius", "radius.* → 逻辑像素（dp）", "kotlin.Int", radius, str)
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
