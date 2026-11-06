#!/usr/bin/env python3
"""gen.py 的命名规则测试（只用标准库 unittest）。

用法：python3 tools/codegen/test_gen.py [-v]

用探针输入（真实快照 + 额外的枚举文件）覆盖 gen.py 顶部注释里的命名规则：
  - 与 Kotlin 标准类型、生成器自用名同名的枚举键加前缀 Contract；
  - 与兜底 entry 同名的取值改名 UNKNOWN_VALUE；改名后仍冲突即失败；
  - 模板里的标准类型全限定（生成物里没有裸的 String / Int / List / Map 等类型引用）；
  - 输出文件名冲突明确失败。
另有一项编译测试：用 Gradle 缓存里的 kotlin-compiler-embeddable 真编译探针生成物（含 enum class 键名 string 等）。
找不到编译器 jar 时跳过；设置环境变量 COULI_GEN_REQUIRE_KOTLINC=1 时找不到即失败（verify.sh 在构建之后这样跑）。
"""

from __future__ import annotations

import glob
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import gen  # noqa: E402

REAL_SNAPSHOT = gen.SNAPSHOT

# 探针：每个键都与 Kotlin 标准类型或生成器自用名同名（键 → 期望类型名）。
PROBE_KEYS = {
    "string": "ContractString",
    "int": "ContractInt",
    "long": "ContractLong",
    "boolean": "ContractBoolean",
    "any": "ContractAny",
    "unit": "ContractUnit",
    "list": "ContractList",
    "map": "ContractMap",
    "set": "ContractSet",
    "pair": "ContractPair",
    "result": "ContractResult",
    "enum": "ContractEnum",
    "k_serializer": "ContractKSerializer",
    "serializable": "ContractSerializable",
    "json": "ContractJson",
    "companion": "ContractCompanion",
    "raw": "ContractRaw",
    "from_raw": "ContractFromRaw",
    "known": "ContractKnown",
    "unknown": "ContractUnknown",
    "routes": "ContractRoutes",
    "route_spec": "ContractRouteSpec",
    "error_codes": "ContractErrorCodes",
}
# 探针取值：on（YAML 1.1 布尔陷阱的字符串形式）、unknown（与兜底 entry 同名）、raw / known（与成员同名的小写）。
PROBE_VALUES = {"on": "开", "unknown": "未知", "raw": "原始", "known": "已知"}

STANDARD_TYPES = ("String", "Int", "Long", "Boolean", "Double", "Any", "Unit", "List", "Map", "Set", "Pair", "Result")


def probe_enum_file(keys, values=None) -> dict:
    values = values or PROBE_VALUES
    return {"enums": {key: {"description": f"probe {key}", "values": dict(values)} for key in keys}}


class SnapshotCase(unittest.TestCase):
    """每个测试一份真实快照的副本，可往里加探针文件。"""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="couli-gen-test-")
        self.snapshot = os.path.join(self.tmp, "snapshot")
        shutil.copytree(REAL_SNAPSHOT, self.snapshot)
        self.out = os.path.join(self.tmp, "out")
        gen.SNAPSHOT = self.snapshot

    def tearDown(self):
        gen.SNAPSHOT = REAL_SNAPSHOT
        shutil.rmtree(self.tmp, ignore_errors=True)

    def add_enum_file(self, name: str, content: dict):
        path = os.path.join(self.snapshot, "contracts", "enums", name)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(content, f, ensure_ascii=False)

    def generate(self) -> list:
        return gen.generate(self.out)

    def read(self, rel: str) -> str:
        with open(os.path.join(self.out, rel), encoding="utf-8") as f:
            return f.read()

    def kotlin_sources(self) -> dict:
        found = {}
        for path in glob.glob(os.path.join(self.out, "**", "*.kt"), recursive=True):
            with open(path, encoding="utf-8") as f:
                found[os.path.relpath(path, self.out)] = f.read()
        return found


def code_without_comments_and_strings(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return re.sub(r'"(?:\\.|[^"\\])*"', '""', text)


class NamingRulesTest(SnapshotCase):
    def test_reserved_type_names_get_contract_prefix(self):
        self.add_enum_file("zprobe.json", probe_enum_file(PROBE_KEYS))
        self.generate()
        source = self.read(f"{gen.MAIN_KOTLIN}/EnumsZprobe.kt")
        registry = self.read(f"{gen.MAIN_KOTLIN}/ContractEnums.kt")
        for key, expected in PROBE_KEYS.items():
            with self.subTest(key=key):
                self.assertIn(f"enum class {expected}(", source)
                self.assertIn(f"object {expected}Serializer : KSerializer<{expected}>", source)
                self.assertIn(f'"{key}" to {expected}.known.map', registry)
        for bare in ("String", "List", "Map", "Int", "Routes", "KSerializer", "Json", "Companion"):
            self.assertNotIn(f"enum class {bare}(", source)

    def test_entry_names_follow_convention(self):
        self.add_enum_file("zprobe.json", probe_enum_file(["string"]))
        self.generate()
        source = self.read(f"{gen.MAIN_KOTLIN}/EnumsZprobe.kt")
        for line in ('    ON("on"),', '    UNKNOWN_VALUE("unknown"),', '    RAW("raw"),', '    KNOWN("known"),',
                     '    UNKNOWN("__unknown__"),'):
            self.assertIn(line, source)
        self.assertIn("    val raw: kotlin.String,", source)
        self.assertIn("fun fromRaw(raw: kotlin.String): ContractString = byRaw[raw] ?: UNKNOWN", source)

    def test_entry_rename_collision_fails(self):
        self.add_enum_file("zprobe.json", probe_enum_file(["probe"], {"unknown": "a", "unknown_value": "b"}))
        with self.assertRaisesRegex(gen.GenError, "UNKNOWN_VALUE"):
            self.generate()

    def test_prefixed_name_colliding_with_existing_type_fails(self):
        self.add_enum_file("zprobe.json", probe_enum_file(["string", "contract_string"]))
        with self.assertRaisesRegex(gen.GenError, "ContractString"):
            self.generate()

    def test_serializer_name_collision_fails(self):
        self.add_enum_file("zprobe.json", probe_enum_file(["probe", "probe_serializer"]))
        with self.assertRaisesRegex(gen.GenError, "ProbeSerializer"):
            self.generate()

    def test_type_names_differing_only_in_case_fail_in_same_file(self):
        # foo → Foo、f_o_o → FOO：同一个 .kt 文件，但 Foo.class 与 FOO.class 在不区分大小写的文件系统上相同
        self.add_enum_file("zprobe.json", probe_enum_file(["foo", "f_o_o"]))
        with self.assertRaisesRegex(gen.GenError, r"FOO.*Foo.*大小写不敏感"):
            self.generate()

    def test_type_names_differing_only_in_case_fail_across_files(self):
        self.add_enum_file("zprobe_a.json", probe_enum_file(["foo"]))
        self.add_enum_file("zprobe_b.json", probe_enum_file(["f_o_o"]))
        with self.assertRaisesRegex(gen.GenError, "大小写不敏感"):
            self.generate()

    def test_serializer_name_differing_only_in_case_fails(self):
        # probe_s_erializer → ProbeSErializer，与 probe 的序列化器 ProbeSerializer 只差大小写
        self.add_enum_file("zprobe.json", probe_enum_file(["probe", "probe_s_erializer"]))
        with self.assertRaisesRegex(gen.GenError, "ProbeSErializer.*ProbeSerializer"):
            self.generate()

    def test_type_name_differing_only_in_case_from_generator_type_fails(self):
        # r_outes → ROutes：不与 Routes 完全相同所以不加前缀，但与同包的 Routes 只差大小写
        self.add_enum_file("zprobe.json", probe_enum_file(["r_outes"]))
        with self.assertRaisesRegex(gen.GenError, "ROutes.*Routes"):
            self.generate()

    def test_entry_names_collide_case_insensitively(self):
        # fooBar 与 foo_bar 转换后都是 FOO_BAR
        self.add_enum_file("zprobe.json", probe_enum_file(["probe"], {"fooBar": "a", "foo_bar": "b"}))
        with self.assertRaisesRegex(gen.GenError, "FOO_BAR"):
            self.generate()

    def test_foo_and_f_o_o_do_not_collide_when_distinct_after_folding(self):
        # 对照：foo 与 foo_two 折叠后不同，正常生成
        self.add_enum_file("zprobe.json", probe_enum_file(["foo", "foo_two"]))
        self.generate()
        source = self.read(f"{gen.MAIN_KOTLIN}/EnumsZprobe.kt")
        self.assertIn("enum class Foo(", source)
        self.assertIn("enum class FooTwo(", source)

    def test_output_file_name_collision_fails(self):
        # zprobe_a.json 与 zprobe-a.json 都会写到 EnumsZprobeA.kt
        self.add_enum_file("zprobe_a.json", probe_enum_file(["probe_one"]))
        self.add_enum_file("zprobe-a.json", probe_enum_file(["probe_two"]))
        with self.assertRaisesRegex(gen.GenError, "输出文件名冲突"):
            self.generate()

    def test_output_file_name_collision_is_case_insensitive(self):
        gen._WRITTEN.clear()
        gen.claim_output("core/x/EnumsFoo.kt")
        with self.assertRaisesRegex(gen.GenError, "输出文件名冲突"):
            gen.claim_output("core/x/ENUMSFOO.kt")

    def test_standard_types_are_fully_qualified_in_templates(self):
        self.add_enum_file("zprobe.json", probe_enum_file(PROBE_KEYS))
        self.generate()
        pattern = re.compile(r"(?<![.\w])(" + "|".join(STANDARD_TYPES) + r")\b")
        for rel, text in self.kotlin_sources().items():
            with self.subTest(file=rel):
                hits = pattern.findall(code_without_comments_and_strings(text))
                self.assertEqual([], hits, f"{rel} 里有未全限定的标准类型引用")

    def test_real_snapshot_is_unchanged_by_rules(self):
        # 真实快照里没有触发改名的键：生成物与仓库里的一致（等价于 gen.py --check）
        written = self.generate()
        root = gen.ROOT
        for rel in written:
            with self.subTest(file=rel):
                with open(os.path.join(root, rel), "rb") as a, open(os.path.join(self.out, rel), "rb") as b:
                    self.assertEqual(a.read(), b.read())


class RouteSchemaTest(SnapshotCase):
    """APP-02a：routes.json 的 agent_guide / agent_guide_account / params 进 RouteSpec；不认识的约束明确失败。"""

    def edit_routes(self, mutate):
        path = os.path.join(self.snapshot, "contracts", "routes.json")
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        mutate(data["routes"])
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False)

    def test_agent_flags_and_params_are_generated(self):
        self.generate()
        source = self.read(f"{gen.MAIN_KOTLIN}/Routes.kt")
        self.assertIn("val agentGuide: kotlin.Boolean,", source)
        self.assertIn("val agentGuideAccount: kotlin.String?,", source)
        self.assertIn('agentGuideAccount = "fund",', source)
        self.assertIn('name = "product_key",', source)
        self.assertIn('pattern = "^https://",', source)

    def test_agent_guide_defaults_to_false(self):
        self.edit_routes(lambda routes: routes["Wallet"].pop("agent_guide"))
        self.generate()
        source = self.read(f"{gen.MAIN_KOTLIN}/Routes.kt")
        wallet = source[source.index('"Wallet" to'):source.index('"Withdraw" to')]
        self.assertIn("agentGuide = false,", wallet)

    def test_unknown_param_keyword_fails(self):
        self.edit_routes(lambda routes: routes["Search"]["params"]["properties"]["q"].__setitem__("format", "uri"))
        with self.assertRaisesRegex(gen.GenError, "Search.*format"):
            self.generate()

    def test_unknown_route_field_fails(self):
        self.edit_routes(lambda routes: routes["Home"].__setitem__("agent_only", True))
        with self.assertRaisesRegex(gen.GenError, "Home.*agent_only"):
            self.generate()

    def test_non_boolean_agent_guide_fails(self):
        self.edit_routes(lambda routes: routes["Wallet"].__setitem__("agent_guide", "yes"))
        with self.assertRaisesRegex(gen.GenError, "Wallet.*agent_guide"):
            self.generate()


class EventsSchemaTest(SnapshotCase):
    """APP-03b：events.yaml → ClientEvents；字段名禁用规则命中即失败（03 §4.7）。"""

    def edit_events(self, mutate):
        path = os.path.join(self.snapshot, "specs", "events.json")
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        mutate(data)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False)

    def test_app_launch_constructor_is_generated(self):
        self.generate()
        source = self.read(f"{gen.CLIENT_CORE_KOTLIN}/ClientEvents.kt")
        self.assertIn("fun appLaunch(", source)
        self.assertIn("class ClientEvent internal constructor(", source)

    def test_forbidden_field_names_fail(self):
        for name in ("click_url", "tpwd", "relation_id", "pid", "access_token", "install_secret", "android_id", "phone"):
            with self.subTest(field=name):
                def mutate(data, field=name):
                    data["events"]["click"]["fields"] = {field: {"type": "string", "required": True}}
                self.edit_events(mutate)
                with self.assertRaises(gen.GenError) as caught:
                    self.generate()
                self.assertIn("禁用规则", str(caught.exception))

    def test_allowed_field_names_pass(self):
        def mutate(data):
            data["events"]["click"]["fields"] = {
                "link_id": {"type": "string", "required": True},
                "registered_domain": {"type": "string", "required": False},
            }
        self.edit_events(mutate)
        self.generate()

    def test_field_shadowing_common_field_fails(self):
        def mutate(data):
            data["events"]["click"]["fields"] = {"page": {"type": "string", "required": True}}
        self.edit_events(mutate)
        with self.assertRaises(gen.GenError):
            self.generate()

    def test_bundled_config_rejects_non_prod_address(self):
        path = os.path.join(self.snapshot, "contracts", "openapi.json")
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        example = data["paths"]["/v1/config"]["get"]["responses"]["200"]["content"]["application/json"]["example"]
        example["data"]["help_links"] = {"x": {"route": "https://staging.couli.invalid/a"}}
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False)
        with self.assertRaises(gen.GenError) as caught:
            self.generate()
        self.assertIn("非生产地址", str(caught.exception))


def token_nodes(node, prefix=""):
    """测试自己的遍历器；不能复用被测 token_leaves，否则漏段会一起漏断言。"""
    for key, child in node.items():
        path = f"{prefix}.{key}" if prefix else key
        if "value" in child:
            yield path, child
        else:
            yield from token_nodes(child, path)


def without_kotlin_comments(source):
    # 保留字符串（颜色 / 点分路径也要扫描），保留换行便于报行号。
    pattern = r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|/\*[\s\S]*?\*/|//[^\n]*'
    return re.sub(pattern, lambda m: re.sub(r"[^\n]", " ", m[0]) if m[0].startswith("/") else m[0], source)


class KotlinTokenLiteral:
    """只读生成物常量，不执行 Kotlin。支持已有 map 模板与 list / 阴影记录。

    阴影允许 JSON 字符串或 List<记录>；记录名不锁死，位置参数由声明的字段顺序解码。
    未识别表达式必须报错，不能把漏解析当空表通过。
    """

    def __init__(self, text, records):
        self.text = text
        self.records = records
        self.position = 0

    def take(self, pattern):
        match = re.match(r"\s*(?:" + pattern + r")", self.text[self.position:])
        if not match:
            raise AssertionError(f"不能解析生成物常量：{self.text[self.position:self.position + 100]!r}")
        self.position += match.end()
        return match[0].strip()

    def peek(self, pattern):
        return re.match(r"\s*(?:" + pattern + r")", self.text[self.position:]) is not None

    def value(self):
        if self.peek('"'):
            return json.loads(self.take(r'"(?:\\.|[^"\\])*"'))
        if self.peek(r"-?\d"):
            return json.loads(self.take(r"-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?"))
        name = self.take(r"[\w.]+").split(".")[-1]
        if self.peek("<"):
            self.take(r"<[^>]+>")
        self.take(r"\(")
        args, fields = [], {}
        while not self.peek(r"\)"):
            if self.peek(r"\w+\s*="):
                field = self.take(r"\w+")
                self.take("=")
                if field in fields:
                    raise AssertionError(f"重复阴影字段：{field}")
                fields[field] = self.value()
            else:
                item = self.value()
                if self.peek(r"to\b"):
                    self.take("to")
                    item = (item, self.value())
                args.append(item)
            if not self.peek(","):
                break
            self.take(",")
        self.take(r"\)")
        if name in ("linkedMapOf", "mapOf", "emptyMap"):
            result = dict(args)
            if len(result) != len(args):
                raise AssertionError("生成物存在重复键")
            return result
        if name in ("listOf", "emptyList"):
            return args
        if name in self.records:
            if fields:
                if args:
                    raise AssertionError("阴影常量不能混用位置参数和命名参数")
                return fields
            names = self.records[name]
            if len(names) != len(args):
                raise AssertionError(f"阴影字段个数不符：{name}")
            return dict(zip(names, args))
        raise AssertionError(f"未知生成物表达式：{name}")


def generated_token_map(source, name):
    source = without_kotlin_comments(source)
    records = {
        record: re.findall(r"\bval\s+(\w+)\s*:", body)
        for record, body in re.findall(r"\bclass\s+(\w+)\s*\((.*?)\)", source, flags=re.S)
    }
    match = re.search(r"\bval\s+" + re.escape(name) + r"\s*:[^=]+?=", source)
    if match is None:
        raise AssertionError(f"生成物缺少 DesignTokens.{name}")
    values = KotlinTokenLiteral(source[match.end():], records).value()
    if not isinstance(values, dict):
        raise AssertionError(f"DesignTokens.{name} 不是 map")
    if name in ("shadows", "shadowsDark", "fontFamily"):
        values = {key: json.loads(value) if isinstance(value, str) else value for key, value in values.items()}
    return values


# 新段按 iOS DS-02-iOS-1 对齐；font.family 也纳入，保证源的每个 value 叶子有去处。
TOKEN_MAPS = {
    "color": "colors", "font.family": "fontFamily", "font.size": "fontSize",
    "font.weight": "fontWeight", "font.line-height": "lineHeight", "space": "spacing",
    "radius": "radius", "shadow": "shadows", "focus": "focus",
    "interaction.target": "interactionTarget", "component": "component",
}


class TokensPipeGeneratorTest(SnapshotCase):
    """TOKENS-PIPE：样例生成与升锁后真实快照逐值检查；不修改工作树快照 / 生成物。"""

    def generate_tokens(self, tokens):
        for path, leaf in token_nodes(tokens):
            if path.startswith("color."):
                leaf.setdefault("type", "color")
            elif path.startswith("shadow."):
                leaf.setdefault("type", "shadow")
        path = Path(self.snapshot, "design/design-tokens.json")
        path.write_text(json.dumps({"metadata": {"version": "0.5.1"}, "tokens": tokens}), encoding="utf-8")
        gen._WRITTEN.clear()
        gen.gen_tokens(self.out)
        return self.read(f"{gen.MAIN_KOTLIN}/DesignTokens.kt")

    def test_dark_paths_and_values_are_independent_of_light(self):
        source = self.generate_tokens({"color": {
            "text": {"primary": {"value": "#28251F", "dark": "#F2EEE6"}},
            "brand": {"primary": {"value": "#C44820", "dark": "#C44820"}},
            "background": {"canvas": {"value": "#F2EEE6", "dark": "#171513"}},
            "status": {"error": {"text": {"value": "#B42345", "dark": "#F28DA4"}}},
        }})
        self.assertEqual({"text.primary": "#28251F", "brand.primary": "#C44820",
                          "background.canvas": "#F2EEE6", "status.error.text": "#B42345"},
                         generated_token_map(source, "colors"))
        self.assertEqual({"text.primary": "#F2EEE6", "brand.primary": "#C44820",
                          "background.canvas": "#171513", "status.error.text": "#F28DA4"},
                         generated_token_map(source, "colorsDark"))

    def test_component_paths_keep_hyphens_and_fraction(self):
        source = self.generate_tokens({"component": {
            "checkbox": {"size": {"type": "dimension", "value": 18},
                         "max-scale": {"type": "number", "value": 1.5}},
            "code-cell": {"height": {"type": "dimension", "value": 56}},
            "button": {"padding-inline": {"type": "dimension", "value": 20}},
            "probe": {"whole-number": {"type": "number", "value": 3}},
        }})
        self.assertEqual({"checkbox.size": 18, "code-cell.height": 56, "button.padding-inline": 20},
                         generated_token_map(source, "component"))
        self.assertEqual({"checkbox.max-scale": 1.5, "probe.whole-number": 3},
                         generated_token_map(source, "componentNumber"))

    def test_eight_digit_colors_preserve_rgba_in_both_modes(self):
        source = self.generate_tokens({"color": {"overlay": {
            "scrim": {"value": "#28251F66", "dark": "#0000008C"},
            "on-image": {"value": "#0000008C", "dark": "#0000008C"},
            "probe": {"value": "#12345678", "dark": "#89ABCDEF"},
        }}})
        self.assertEqual({"overlay.scrim": "#28251F66", "overlay.on-image": "#0000008C",
                          "overlay.probe": "#12345678"}, generated_token_map(source, "colors"))
        self.assertEqual({"overlay.scrim": "#0000008C", "overlay.on-image": "#0000008C",
                          "overlay.probe": "#89ABCDEF"}, generated_token_map(source, "colorsDark"))

    def test_shadows_keep_all_layers_fields_and_empty_none(self):
        light = [{"x": 0, "y": 2, "blur": 8, "spread": 0, "color": "#28251F", "opacity": 0.06},
                 {"x": -1, "y": 3, "blur": 9, "spread": 1, "color": "#123456", "opacity": 0.12}]
        dark = [{"x": 0, "y": 2, "blur": 8, "spread": 0, "color": "#000000", "opacity": 0.3}]
        source = self.generate_tokens({"shadow": {
            "none": {"value": []}, "card": {"value": light, "dark": dark},
            "floating": {"value": light, "dark": list(reversed(light))},
        }})
        self.assertEqual({"none": [], "card": light, "floating": light}, generated_token_map(source, "shadows"))
        self.assertEqual({"none": [], "card": dark, "floating": list(reversed(light))},
                         generated_token_map(source, "shadowsDark"))

    def test_missing_dark_must_not_silently_fall_back_to_light(self):
        for tokens in (
            {"color": {"text": {"primary": {"value": "#28251F"}}}},
            {"shadow": {"card": {"value": [
                {"x": 0, "y": 2, "blur": 8, "spread": 0, "color": "#28251F", "opacity": 0.06},
            ]}}},
        ):
            with self.subTest(tokens=tokens), self.assertRaises(gen.GenError):
                self.generate_tokens(tokens)

    def test_v051_every_leaf_matches_checked_in_generated_constants(self):
        data = json.loads(Path(self.snapshot, "design/design-tokens.json").read_text(encoding="utf-8"))
        self.assertEqual("0.5.1", data["metadata"]["version"], "本用例须在 f088f04 升锁副本跑")
        leaves = dict(token_nodes(data["tokens"]))
        self.assertEqual(116, len(leaves), "v0.5.1 含 font.family、空阴影和 component，不能默默排除")
        source = Path(gen.ROOT, gen.MAIN_KOTLIN, "DesignTokens.kt").read_text(encoding="utf-8")
        covered = set()
        for prefix, map_name in TOKEN_MAPS.items():
            expected = {path[len(prefix) + 1:]: leaf["value"] for path, leaf in leaves.items()
                        if path.startswith(prefix + ".") and not (prefix == "component" and leaf["type"] == "number")}
            covered.update(path for path in leaves if path.startswith(prefix + "."))
            with self.subTest(section=prefix):
                self.assertEqual(expected, generated_token_map(source, map_name))
        self.assertEqual(set(leaves), covered)
        with self.subTest(section="componentNumber"):
            expected = {path[len("component."):]: leaf["value"] for path, leaf in leaves.items()
                        if path.startswith("component.") and leaf["type"] == "number"}
            self.assertEqual(expected, generated_token_map(source, "componentNumber"))
        for prefix, map_name in (("color", "colorsDark"), ("shadow", "shadowsDark")):
            with self.subTest(section=map_name):
                expected = {path[len(prefix) + 1:]: leaf.get("dark", leaf["value"])
                            for path, leaf in leaves.items() if path.startswith(prefix + ".")}
                self.assertEqual(expected, generated_token_map(source, map_name))

    def test_v051_source_has_the_decided_new_keys(self):
        data = json.loads(Path(self.snapshot, "design/design-tokens.json").read_text(encoding="utf-8"))
        leaves = dict(token_nodes(data["tokens"]))
        expected = {"color.platform.tag.taobao": "#D53D00", "color.platform.tag.tmall": "#EA0031",
                    "color.platform.tag.jd": "#E02018", "color.platform.tag.pdd": "#EB0007",
                    "color.overlay.on-image": "#0000008C", "radius.tag": 4, "radius.avatar": 10,
                    "component.checkbox.max-scale": 1.5}
        for path, value in expected.items():
            with self.subTest(path=path):
                self.assertIn(path, leaves)
                self.assertEqual(value, leaves[path]["value"])
        # 只钉 value / dark，描述文案和 JSON 排版不影响断言。摘要来自 git show f088f04。
        canonical = {path: {key: leaf[key] for key in ("value", "dark") if key in leaf}
                     for path, leaf in leaves.items()}
        digest = hashlib.sha256(json.dumps(canonical, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
        self.assertEqual(V051_LEAF_DIGEST, digest)

    def test_check_rejects_drift_without_rewriting_generated_file(self):
        self.generate()
        vectors = Path(self.out, gen.HOME_VECTORS)
        vectors.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(Path(gen.ROOT, gen.HOME_VECTORS), vectors)
        output = Path(self.out, gen.MAIN_KOTLIN, "DesignTokens.kt")
        with patch.object(gen, "ROOT", self.out), patch.object(sys, "argv", ["gen.py", "--check"]):
            with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                self.assertEqual(0, gen.main(), "未变异的副本须先绿，不能用生成失败冒充检出漂移")
                original = output.read_text(encoding="utf-8")
                self.assertIn('"#C44820"', original)
                mutated = original.replace('"#C44820"', '"#C44821"', 1)
                output.write_text(mutated, encoding="utf-8")
                self.assertEqual(1, gen.main(), "生成物改值必须由 --check 拒绝")
                self.assertEqual(mutated, output.read_text(encoding="utf-8"), "--check 不得覆盖漂移证据")


V051_LEAF_DIGEST = "b54f40e96e64623a0ed25dcf8530a2ed273d08db715f9fb2943f085ba6a2ce35"


# ---- TOKENS-PIPE 测试 v2（review-android-r1 S2-2 / S2-3）

# 每段一份最小合法样例（type 与 v0.5.1 源一致）：段 → (令牌子树, 叶子点分路径)。
# 样例先单独生成一次作正向对照，确认红因是「未知 type」而不是样例本身不合法。
_SHADOW_LAYER = {"x": 0, "y": 2, "blur": 8, "spread": 0, "color": "#28251F", "opacity": 0.06}
STRICT_SAMPLES = {
    "color": ({"color": {"text": {"primary": {"type": "color", "value": "#28251F", "dark": "#F2EEE6"}}}},
              "color.text.primary"),
    "font.family": ({"font": {"family": {"system": {"type": "fontFamily", "value": ["system-ui", "sans-serif"]}}}},
                    "font.family.system"),
    "font.size": ({"font": {"size": {"body": {"type": "fontSize", "value": 15, "unit": "logical"}}}}, "font.size.body"),
    "font.weight": ({"font": {"weight": {"regular": {"type": "fontWeight", "value": 400}}}}, "font.weight.regular"),
    "font.line-height": ({"font": {"line-height": {"body": {"type": "lineHeight", "value": 1.5}}}},
                         "font.line-height.body"),
    "space": ({"space": {"md": {"type": "dimension", "value": 12, "unit": "logical"}}}, "space.md"),
    "radius": ({"radius": {"card": {"type": "dimension", "value": 8, "unit": "logical"}}}, "radius.card"),
    "focus": ({"focus": {"width": {"type": "dimension", "value": 2, "unit": "logical"}}}, "focus.width"),
    "interaction.target": ({"interaction": {"target": {"minimum": {"type": "dimension", "value": 44, "unit": "logical"}}}},
                           "interaction.target.minimum"),
    "shadow": ({"shadow": {"card": {"type": "shadow", "value": [_SHADOW_LAYER], "dark": [_SHADOW_LAYER]}}},
               "shadow.card"),
    "component": ({"component": {"button": {"height": {"type": "dimension", "value": 44, "unit": "logical"}}}},
                  "component.button.height"),
}
# 段内放一个「别的段才用的已知 type」：统一校验 type 时也必须拒绝（不能只拦完全陌生的词）。
WRONG_KNOWN_TYPE = {
    "color": "dimension", "font.family": "fontSize", "font.size": "dimension", "font.weight": "fontSize",
    "font.line-height": "number", "space": "color", "radius": "fontSize", "focus": "shadow",
    "interaction.target": "fontWeight", "shadow": "color", "component": "color",
}


def leaf_at(tokens, dotted):
    node = tokens
    for key in dotted.split("."):
        node = node[key]
    return node


class TokensPipeStrictTokensTest(SnapshotCase):
    """S2-2：令牌源里任何生成器不认识的分组 / type 都必须带路径报 GenError，不得静默丢弃。"""

    def write_tokens(self, tokens, version="0.5.1"):
        Path(self.snapshot, "design/design-tokens.json").write_text(
            json.dumps({"metadata": {"version": version}, "tokens": tokens}, ensure_ascii=False), encoding="utf-8")

    def gen_tokens(self, tokens):
        self.write_tokens(tokens)
        gen._WRITTEN.clear()
        shutil.rmtree(self.out, ignore_errors=True)
        gen.gen_tokens(self.out)
        return self.read(f"{gen.MAIN_KOTLIN}/DesignTokens.kt")

    def assert_rejected_with_path(self, tokens, path):
        with self.assertRaises(gen.GenError) as caught:
            self.gen_tokens(tokens)
        self.assertIn(path, str(caught.exception), "报错必须带出出问题的令牌路径")

    def real_tokens(self):
        data = json.loads(Path(self.snapshot, "design/design-tokens.json").read_text(encoding="utf-8"))
        self.assertEqual("0.5.1", data["metadata"]["version"], "本用例须在 f088f04 升锁副本跑")
        return data["tokens"]

    def test_samples_are_valid_positive_controls(self):
        for section, (tokens, _) in STRICT_SAMPLES.items():
            with self.subTest(section=section):
                self.gen_tokens(copy_json(tokens))

    def test_unknown_groups_fail_with_leaf_path(self):
        base = copy_json(STRICT_SAMPLES["color"][0])
        cases = {
            # 全新顶层分组
            "motion.duration": {"motion": {"duration": {"type": "dimension", "value": 200}}},
            # 已知顶层（font）下的陌生子分组
            "font.tracking.body": {"font": {"tracking": {"body": {"type": "dimension", "value": 1}}}},
            # 已知顶层下直接挂的叶子
            "font.tracking": {"font": {"tracking": {"type": "dimension", "value": 1}}},
            # interaction 下 target 以外的子分组
            "interaction.hover.opacity": {"interaction": {"hover": {"opacity": {"type": "number", "value": 0.8}}}},
            # 顶层直接是叶子
            "opacity": {"opacity": {"type": "number", "value": 0.5}},
        }
        for path, extra in cases.items():
            with self.subTest(path=path):
                tokens = copy_json(base)
                for key, value in extra.items():
                    tokens.setdefault(key, {}).update(value)
                if path.startswith("font."):
                    # 同一 font 下保留一个合法段，确认不是「整段 font 都不认」才红
                    tokens["font"]["size"] = {"body": {"type": "fontSize", "value": 15}}
                self.assert_rejected_with_path(tokens, path)

    def test_unknown_type_fails_with_leaf_path_in_every_section(self):
        for section, (tokens, path) in STRICT_SAMPLES.items():
            with self.subTest(section=section):
                mutated = copy_json(tokens)
                leaf_at(mutated, path)["type"] = "mystery"
                self.assert_rejected_with_path(mutated, path)

    def test_known_type_in_wrong_section_fails_with_leaf_path(self):
        for section, (tokens, path) in STRICT_SAMPLES.items():
            with self.subTest(section=section, type=WRONG_KNOWN_TYPE[section]):
                mutated = copy_json(tokens)
                leaf_at(mutated, path)["type"] = WRONG_KNOWN_TYPE[section]
                self.assert_rejected_with_path(mutated, path)

    def test_unknown_group_in_real_v051_source_fails(self):
        for path, extra in (("motion.duration", {"motion": {"duration": {"type": "dimension", "value": 200}}}),
                            ("font.tracking.body", None)):
            with self.subTest(path=path):
                tokens = self.real_tokens()
                if extra:
                    tokens.update(extra)
                else:
                    tokens["font"]["tracking"] = {"body": {"type": "dimension", "value": 1}}
                self.assert_rejected_with_path(tokens, path)

    def test_mystery_type_in_real_v051_source_fails(self):
        tokens = self.real_tokens()
        tokens["color"]["text"]["primary"]["type"] = "mystery"
        self.assert_rejected_with_path(tokens, "color.text.primary")

    def test_check_reports_unknown_group_instead_of_no_drift(self):
        self.generate()
        vectors = Path(self.out, gen.HOME_VECTORS)
        vectors.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(Path(gen.ROOT, gen.HOME_VECTORS), vectors)
        tokens = self.real_tokens()
        stderr = io.StringIO()
        with patch.object(gen, "ROOT", self.out), patch.object(sys, "argv", ["gen.py", "--check"]):
            with redirect_stdout(io.StringIO()), redirect_stderr(stderr):
                self.assertEqual(0, gen.main(), "未注入前副本须先绿")
                tokens["motion"] = {"duration": {"type": "dimension", "value": 200}}
                self.write_tokens(tokens)
                self.assertEqual(2, gen.main(), "源里多出生成器不认识的分组，--check 不得报一致")
        self.assertIn("motion.duration", stderr.getvalue())

    def test_v051_every_leaf_is_consumed_exactly_once(self):
        tokens = self.real_tokens()
        leaves = dict(token_nodes(tokens))
        self.assertEqual(116, len(leaves))
        source = self.gen_tokens(tokens)
        produced = []
        for prefix, map_name in list(TOKEN_MAPS.items()) + [("component", "componentNumber")]:
            produced += [f"{prefix}.{key}" for key in generated_token_map(source, map_name)]
        self.assertEqual(len(produced), len(set(produced)), "同一叶子不得进两个段")
        self.assertEqual(set(leaves), set(produced), "消费路径集合必须等于源的全部叶子集合")
        for prefix, map_name in (("color", "colorsDark"), ("shadow", "shadowsDark")):
            with self.subTest(section=map_name):
                self.assertEqual({path for path in leaves if path.startswith(prefix + ".")},
                                 {f"{prefix}.{key}" for key in generated_token_map(source, map_name)})


def copy_json(value):
    return json.loads(json.dumps(value))


# 必须拒绝的颜色写法：长度、非十六进制、空白、缺 #、int() 宽松解析会放过的 + / - / _、Unicode 数字。
BAD_COLORS = [
    "#ABC", "#ABCD", "#ABCDE", "#ABCDEF1", "#ABCDEF123", "#ABCDEF1234", "#", "#A", "#AB", "",
    "#GGGGGG", "#12345Z", "#1234567G", "#C4 820", "0xC44820", "##C44820",
    " #C44820", "#C44820 ", "#C44820\n", "\t#C44820",
    "C44820", "C44820FF",
    "#+12345", "#-12345", "#12_456", "#1234_678", "# 12345",
    "#١٢٣٤٥٦", "#１２３４５６",
    0xC44820, None, ["#C44820"],
]
GOOD_COLORS = ["#C44820", "#28251F66", "#000000", "#FFFFFFFF", "#0000008C"]
# 现实现口径：大小写不限（Kotlin parseHexColor 用 toLong(16) 也接受小写）；允许原样或统一大写输出，不允许改值。
CASE_COLORS = ["#c44820", "#c4482Aff", "#AbCdEf"]


class TokensPipeColorFormatTest(SnapshotCase):
    """S2-3：只收 #RRGGBB / #RRGGBBAA；浅色、深色、阴影层颜色（含深色阴影）四个位置都要拦。"""

    write_tokens = TokensPipeStrictTokensTest.write_tokens
    gen_tokens = TokensPipeStrictTokensTest.gen_tokens

    @staticmethod
    def placements(color):
        layer = dict(_SHADOW_LAYER)
        bad_layer = dict(_SHADOW_LAYER, color=color)
        return {
            "color.text.primary": {"color": {"text": {"primary": {"type": "color", "value": color, "dark": "#F2EEE6"}}}},
            "color.text.primary.dark": {"color": {"text": {"primary": {"type": "color", "value": "#28251F", "dark": color}}}},
            "shadow.card": {"shadow": {"card": {"type": "shadow", "value": [layer, bad_layer], "dark": [layer]}}},
            "shadow.card.dark": {"shadow": {"card": {"type": "shadow", "value": [layer], "dark": [bad_layer]}}},
        }

    def test_bad_colors_are_rejected_everywhere_with_path(self):
        for color in BAD_COLORS:
            for path, tokens in self.placements(color).items():
                with self.subTest(color=color, where=path):
                    with self.assertRaises(gen.GenError) as caught:
                        self.gen_tokens(tokens)
                    self.assertIn(path.removesuffix(".dark"), str(caught.exception))

    def test_relaxed_three_to_eight_digit_pattern_is_caught(self):
        # 专杀「正则放宽成 {3,8}」：3 / 4 / 5 / 7 位逐个必须在浅色值上被拒。
        for color in ("#ABC", "#ABCD", "#ABCDE", "#ABCDEF1"):
            with self.subTest(color=color), self.assertRaises(gen.GenError):
                self.gen_tokens(self.placements(color)["color.text.primary"])

    def test_six_and_eight_digit_colors_are_accepted_verbatim(self):
        for color in GOOD_COLORS:
            for path, tokens in self.placements(color).items():
                with self.subTest(color=color, where=path):
                    source = self.gen_tokens(tokens)
                    if path.startswith("color."):
                        mode = "colorsDark" if path.endswith(".dark") else "colors"
                        self.assertEqual(color, generated_token_map(source, mode)["text.primary"])
                    else:
                        mode = "shadowsDark" if path.endswith(".dark") else "shadows"
                        self.assertEqual(color, generated_token_map(source, mode)["card"][-1]["color"])

    def test_mixed_case_is_accepted_without_changing_value(self):
        for color in CASE_COLORS:
            for path, tokens in self.placements(color).items():
                with self.subTest(color=color, where=path):
                    source = self.gen_tokens(tokens)
                    if path.startswith("color."):
                        mode = "colorsDark" if path.endswith(".dark") else "colors"
                        got = generated_token_map(source, mode)["text.primary"]
                    else:
                        mode = "shadowsDark" if path.endswith(".dark") else "shadows"
                        got = generated_token_map(source, mode)["card"][-1]["color"]
                    self.assertIn(got, (color, color.upper()))


# ---- TOKENS-PIPE 测试 v3（review-android-r2：S2-1 路径重名、S2-2 非字符串 type）
#
# 含点号键的口径（供实现者照做）：令牌树任何层级的键（顶层、分组名、叶子名）只要含「.」，一律拒绝。
# 报 GenError，信息带该键的完整路径（父路径 + 键原文，例如 space.x.y）。不论它与别的叶子是否重名、
# type 是否合法，都要拒绝。理由：生成物 map 键和报错都用点分路径，含点号的键与嵌套路径分不开；
# v0.5.1 里没有任何含点号的键。只把消费核对改成路径段元组、不拒点号，
# 过不了 test_dotted_keys_are_rejected_even_without_collision。

# 与 v0.5.1 嵌套叶子 font.size.body（fontSize 17）重名的字面键：未知 type 一份，合法 type 一份。
COLLIDING_LEAVES = {
    "mystery": {"type": "mystery", "value": 999},
    "fontSize": {"type": "fontSize", "value": 999, "unit": "logical"},
}
# 重名键放的位置：顶层字面键 "font.size.body"，或 font 下的 "size.body"；完整路径都是 font.size.body。
COLLIDING_PLACEMENTS = {
    "top-level": lambda tokens, leaf: tokens.__setitem__("font.size.body", leaf),
    "under-font": lambda tokens, leaf: tokens["font"].__setitem__("size.body", leaf),
}
# 不重名、type 合法、落在已知段内的含点号键：期望路径 → 注入函数。
DOTTED_KEYS = {
    "space.x.y": lambda t: t["space"].__setitem__("x.y", {"type": "dimension", "value": 4, "unit": "logical"}),
    "component.button.ghost": lambda t: t["component"].__setitem__(
        "button.ghost", {"height": {"type": "dimension", "value": 40, "unit": "logical"}}),
    "color.text.accent.hover": lambda t: t["color"]["text"].__setitem__(
        "accent.hover", {"type": "color", "value": "#123456", "dark": "#654321"}),
    "motion.fast": lambda t: t.__setitem__("motion.fast", {"type": "dimension", "value": 120}),
}


def non_string_types(valid):
    """叶子 type 的非字符串取值；valid 是该段合法的 type，用来造「包着合法词」的数组 / 对象。"""
    return {"[]": [], "{}": {}, "[valid]": [valid], "{valid: true}": {valid: True},
            "7": 7, "1.5": 1.5, "null": None, "true": True}


# --check 逐段必须返回 2 的取值（任务单点名的四种）。
CHECK_NON_STRING_TYPES = ("[]", "{}", "7", "null")


class TokensPipeKeyAndTypeShapeTest(SnapshotCase):
    """v3：路径重名 / 含点号键 / 非字符串 type 都必须带完整路径报 GenError，--check 返回 2，不得抛未捕获异常。"""

    write_tokens = TokensPipeStrictTokensTest.write_tokens
    gen_tokens = TokensPipeStrictTokensTest.gen_tokens

    def real_tokens(self):
        # 本类一个用例里要多次注入：write_tokens 会覆盖副本里的源，所以首次读到的原样源要缓存，每次给深拷贝。
        if not hasattr(self, "_pristine_tokens"):
            self._pristine_tokens = TokensPipeStrictTokensTest.real_tokens(self)
        return copy_json(self._pristine_tokens)

    def assert_gen_error_with_path(self, tokens, path):
        try:
            self.gen_tokens(tokens)
        except gen.GenError as error:
            self.assertIn(path, str(error), "GenError 信息必须带完整令牌路径")
        except Exception as error:  # noqa: BLE001 —— 要把未捕获异常判为失败而不是测试错误
            self.fail(f"须报 GenError，实际抛出未捕获的 {type(error).__name__}: {error}")
        else:
            self.fail(f"含 {path} 的令牌源须报 GenError，实际生成成功（静默放过）")

    def prepare_check(self):
        self.generate()
        vectors = Path(self.out, gen.HOME_VECTORS)
        vectors.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(Path(gen.ROOT, gen.HOME_VECTORS), vectors)
        self.assertEqual(0, self.run_check(self.real_tokens())[0], "未注入前副本 --check 须先绿")

    def run_check(self, tokens):
        self.write_tokens(tokens)
        stderr = io.StringIO()
        with patch.object(gen, "ROOT", self.out), patch.object(sys, "argv", ["gen.py", "--check"]):
            with redirect_stdout(io.StringIO()), redirect_stderr(stderr):
                try:
                    code = gen.main()
                except Exception as error:  # noqa: BLE001
                    code, stderr = None, io.StringIO(f"{type(error).__name__}: {error}")
        if code is None:
            self.fail(f"--check 须返回 2，实际抛出未捕获异常（进程会退出 1）：{stderr.getvalue()}")
        return code, stderr.getvalue()

    def assert_check_rejects(self, tokens, path):
        code, err = self.run_check(tokens)
        self.assertEqual(2, code, f"--check 须返回 2（GenError），不得报一致或漂移；stderr：{err}")
        self.assertIn(path, err, "--check 的 stderr 必须带完整令牌路径")

    def test_real_v051_has_no_dotted_keys(self):
        # 口径的前提：拒绝含点号的键不会误伤合法源。
        def walk(node, prefix):
            for key, child in node.items():
                path = f"{prefix}.{key}" if prefix else key
                self.assertNotIn(".", key, f"v0.5.1 里出现含点号的键 {path}")
                if "value" not in child:
                    walk(child, path)
        walk(self.real_tokens(), "")

    def test_colliding_literal_key_fails_with_path(self):
        for type_name, leaf in COLLIDING_LEAVES.items():
            for where, inject in COLLIDING_PLACEMENTS.items():
                with self.subTest(type=type_name, where=where):
                    tokens = self.real_tokens()
                    self.assertEqual(17, tokens["font"]["size"]["body"]["value"], "嵌套叶子 font.size.body 须在")
                    inject(tokens, copy_json(leaf))
                    self.assert_gen_error_with_path(tokens, "font.size.body")

    def test_check_returns_2_for_colliding_literal_key(self):
        self.prepare_check()
        for type_name, leaf in COLLIDING_LEAVES.items():
            for where, inject in COLLIDING_PLACEMENTS.items():
                with self.subTest(type=type_name, where=where):
                    tokens = self.real_tokens()
                    inject(tokens, copy_json(leaf))
                    self.assert_check_rejects(tokens, "font.size.body")

    def test_dotted_keys_are_rejected_even_without_collision(self):
        real_paths = {path for path, _ in token_nodes(self.real_tokens())}
        for path, inject in DOTTED_KEYS.items():
            with self.subTest(path=path):
                self.assertFalse(any(p == path or p.startswith(path + ".") for p in real_paths), "注入键不得与真实叶子重名")
                tokens = self.real_tokens()
                inject(tokens)
                self.assert_gen_error_with_path(tokens, path)

    def test_check_returns_2_for_dotted_keys(self):
        self.prepare_check()
        for path, inject in DOTTED_KEYS.items():
            with self.subTest(path=path):
                tokens = self.real_tokens()
                inject(tokens)
                self.assert_check_rejects(tokens, path)

    def test_non_string_type_fails_with_path_in_every_section(self):
        for section, (tokens, path) in STRICT_SAMPLES.items():
            valid = leaf_at(tokens, path)["type"]
            for label, bad in non_string_types(valid).items():
                with self.subTest(section=section, type=label):
                    mutated = copy_json(tokens)
                    leaf_at(mutated, path)["type"] = copy_json(bad)
                    self.assert_gen_error_with_path(mutated, path)

    def test_check_returns_2_for_non_string_type_in_every_real_section(self):
        self.prepare_check()
        leaves = [path for path, _ in token_nodes(self.real_tokens())]
        for section in STRICT_SAMPLES:
            path = next(p for p in leaves if p.startswith(section + "."))
            valid = leaf_at(self.real_tokens(), path)["type"]
            types = non_string_types(valid)
            for label in CHECK_NON_STRING_TYPES:
                with self.subTest(section=section, leaf=path, type=label):
                    tokens = self.real_tokens()
                    leaf_at(tokens, path)["type"] = copy_json(types[label])
                    self.assert_check_rejects(tokens, path)


# 精确到文件 + 声明的白名单：这些语义在 v0.5.1 没有对应令牌，等 v0.6.0；
# 不能仅因数值碰巧相等就拿另一种组件令牌替代。允许删掉，不允许增加 / 复制。
PENDING_TOKEN_LINES = {
    "CouliLogin.kt": {
        "private val BRAND_MARK_SIZE = 56.dp",
        "private val OAUTH_TAG_START = 36.dp",
        "private val OAUTH_TARGET_EXTRA = 8.dp",
    },
    "CouliLists.kt": {"private val AVATAR_LARGE = 56.dp"},
    "CouliInputs.kt": {"private val ACTIVE_BORDER: Dp = 2.dp", "private val ERROR_BORDER: Dp = 1.5.dp"},
    "CouliOverlays.kt": {"private val ALERT_MAX_WIDTH = 560.dp", "private val MENU_MIN_WIDTH = 112.dp"},
    "CouliSelection.kt": {"private val CHECKBOX_STROKE = 2.dp"},
    "CouliBusiness.kt": {"private val SHARE_CHECKBOX = 22.dp", "private val TREND_HEIGHT = 96.dp"},
    "PlatformIcons.kt": {"SHEET(32.dp),", "HOME(44.dp),"},
    "CouliFoundation.kt": {"private const val SHEET_TITLE_SIZE = 20", "private const val SHEET_TITLE_LINE = 26"},
}


def token_literal_offenders(relative, source):
    allowed = set(PENDING_TOKEN_LINES.get(relative, set()))
    # 颜色解析器的位掩码不是色值表；只放行这一条声明，不放行整个文件。
    if relative == "CouliColors.kt":
        allowed.add("private const val OPAQUE_ALPHA = 0xFF000000L")
    offenders = []
    for line_number, line in enumerate(without_kotlin_comments(source).splitlines(), 1):
        stripped = line.strip()
        if stripped in allowed:
            allowed.remove(stripped)
            continue
        # 0.dp 是布局零值；不拦布局算法 / 路径坐标 / 索引 / 时间 / 业务计数。
        dimensions = re.findall(r"(?<![\w.])\d+(?:\.\d+)?\.(?:dp|sp)\b", line)
        nonzero = [value for value in dimensions if float(value.rsplit(".", 1)[0]) != 0]
        color = re.search(r"#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?\b|\b0[xX][0-9a-fA-F]{6,8}[Ll]?\b", line)
        constructor = re.search(r"\bColor\s*\(\s*(?:\d|(?:red|green|blue|alpha)\s*=\s*\d)", line)
        named_color = re.search(r"\bColor\s*\.\s*(?!Transparent\b|Unspecified\b)[A-Z]\w*", line)
        known_scalar = re.search(
            r"\b(?:val|const\s+val)\s+(?:CHECKBOX_MAX_SCALE|ICON_TINY|TAG_HEIGHT|SCRIM|CARD_SHADOW)"
            r"\s*(?::\s*\w+)?\s*=\s*[\d.]", line)
        token_table_scalar = relative == "CouliThemeTokens.kt" and re.search(
            r"\bval\s+\w+\s*(?::\s*\w+)?\s*=\s*\d", line)
        literal_alpha = re.search(r"\balpha\s*=\s*\d", line)
        if nonzero or color or constructor or named_color or known_scalar or token_table_scalar or literal_alpha:
            offenders.append(f"{relative}:{line_number}: {stripped}")
    return offenders


class TokensPipeSourceTest(unittest.TestCase):
    def test_designsystem_has_no_copied_token_literals(self):
        root = Path(gen.ROOT, "core/designsystem/src/main/kotlin/com/couli/core/designsystem")
        files = sorted(root.rglob("*.kt"))
        self.assertTrue(files, "不能扫描错目录后空集通过")
        offenders = []
        for path in files:
            offenders.extend(token_literal_offenders(path.relative_to(root).as_posix(), path.read_text(encoding="utf-8")))
        self.assertEqual([], offenders, "手抄令牌必须改读生成物；无源值仅允许逐项白名单：\n" + "\n".join(offenders))

    def test_existing_component_aliases_do_not_reconstruct_values_from_spacing(self):
        source = without_kotlin_comments(Path(
            gen.ROOT, "core/designsystem/src/main/kotlin/com/couli/core/designsystem/CouliFoundation.kt"
        ).read_text(encoding="utf-8"))
        # 这些角色在 v0.5.1 已有专门令牌；space16 + space8 同值也不能成为旁路。
        aliases = ("iconLarge", "iconMedium", "iconSmall", "iconTiny", "listThumbnail",
                   "gridCellMinHeight", "tagHeight", "badgeHeight", "hairline", "cardElevation")
        for name in aliases:
            with self.subTest(alias=name):
                match = re.search(r"\bval\s+" + name + r"\s*:\s*Dp\s*=\s*([^\n]+)", source)
                self.assertIsNotNone(match, f"原有 API {name} 不能悄悄删掉")
                self.assertNotRegex(match[1], r"CouliSpacing\.|\b(?:ICON_TINY|TAG_HEIGHT)\b|\d+\.dp",
                                    "应读取对应 component / shadow 生成值或其语义别名")

    def test_verify_keeps_codegen_drift_and_generator_tests_enabled(self):
        source = Path(gen.ROOT, "tools/ci/verify.sh").read_text(encoding="utf-8")
        active = "\n".join(line for line in source.splitlines() if not line.lstrip().startswith("#"))
        self.assertRegex(active, r'(?m)^step\s+"[^"\n]+"\s+python3 tools/codegen/gen\.py --check\s*$')
        self.assertRegex(active, r'(?m)^step\s+"[^"\n]+"\s+env COULI_GEN_REQUIRE_KOTLINC=1 python3 tools/codegen/test_gen\.py -v\s*$')


class TokensPipeTestSupportTest(unittest.TestCase):
    """测试读出器 / 扫描器自身的正反探针，不依赖产品是否已经实现。"""

    def test_literal_reader_keeps_records_layers_and_empty_lists(self):
        source = '''
            data class Layer(val x: kotlin.Int, val color: kotlin.String, val opacity: kotlin.Double)
            val shadows: Map<String, List<Layer>> = linkedMapOf(
                "none" to emptyList(),
                "card" to listOf(Layer(0, "#28251F", 0.06), Layer(x = -1, color = "#000000", opacity = 0.3)),
            )
        '''
        self.assertEqual({"none": [], "card": [{"x": 0, "color": "#28251F", "opacity": 0.06},
                                               {"x": -1, "color": "#000000", "opacity": 0.3}]},
                         generated_token_map(source, "shadows"))
        with self.assertRaisesRegex(AssertionError, "重复键"):
            generated_token_map('val colors: Map<String, String> = mapOf("a" to "x", "a" to "y")', "colors")
        with self.assertRaises(AssertionError):
            generated_token_map('val colors: Map<String, String> = unsupported()', "colors")

    def test_scanner_catches_copies_even_in_theme_and_helper_files(self):
        for filename, snippet in (
            ("CouliThemeTokens.kt", '"text.primary" to Color(0xFFF2EEE6),'),
            ("NewDarkTable.kt", 'val text = "#F2EEE6"'),
            ("CouliColors.kt", 'val copy = Color.White'),
            ("CouliThemeTokens.kt", 'const val CHECKBOX_MAX_SCALE: Float = 1.5f'),
            ("CouliFoundation.kt", 'const val CARD_SHADOW = 0.06f'),
            ("NewTokens.kt", 'val height = 52.dp'),
            ("CouliColors.kt", 'val copy = Color(red = 40, green = 37, blue = 31)'),
            ("NewShadow.kt", 'val shadow = color.copy(alpha = 0.06f)'),
        ):
            with self.subTest(file=filename, source=snippet):
                self.assertTrue(token_literal_offenders(filename, snippet))

    def test_scanner_allowlist_is_narrow_and_comments_are_not_code(self):
        source = '// Color(0xFFFFFFFF)\n/* 18.dp */\nval zero = 0.dp\nval clear = Color.Transparent'
        self.assertEqual([], token_literal_offenders("Any.kt", source))
        pending = "private val ERROR_BORDER: Dp = 1.5.dp"
        self.assertEqual([], token_literal_offenders("CouliInputs.kt", pending))
        self.assertTrue(token_literal_offenders("Copy.kt", pending))
        self.assertTrue(token_literal_offenders("CouliInputs.kt", pending + "\n" + pending))


def find_jar(gradle_home: str, group: str, artifact: str, version: str | None):
    base = os.path.join(gradle_home, "caches", "modules-2", "files-2.1", group, artifact)
    versions = [version] if version else sorted(os.listdir(base)) if os.path.isdir(base) else []
    for v in reversed(versions):
        for jar in glob.glob(os.path.join(base, v, "*", f"{artifact}-{v}.jar")):
            return jar
    return None


def toml_version(name: str) -> str | None:
    with open(os.path.join(gen.ROOT, "gradle", "libs.versions.toml"), encoding="utf-8") as f:
        match = re.search(rf'^{re.escape(name)}\s*=\s*"([^"]+)"', f.read(), flags=re.M)
    return match.group(1) if match else None


def kotlin_compiler():
    """返回 (java, 编译器 classpath, 编译用 classpath)；找不到返回 None。"""
    gradle_home = os.environ.get("GRADLE_USER_HOME") or os.path.expanduser("~/.gradle")
    java_home = os.environ.get("JAVA_HOME")
    java = os.path.join(java_home, "bin", "java") if java_home else shutil.which("java")
    kotlin = toml_version("kotlin")
    serialization = toml_version("kotlinx-serialization")
    if not java or not os.path.exists(java) or not kotlin or not serialization:
        return None
    compiler = [
        find_jar(gradle_home, "org.jetbrains.kotlin", "kotlin-compiler-embeddable", kotlin),
        find_jar(gradle_home, "org.jetbrains.kotlin", "kotlin-stdlib", kotlin),
        find_jar(gradle_home, "org.jetbrains.kotlin", "kotlin-daemon-embeddable", kotlin),
        find_jar(gradle_home, "org.jetbrains.kotlin", "kotlin-script-runtime", kotlin),
        find_jar(gradle_home, "org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", None),
        find_jar(gradle_home, "org.jetbrains.intellij.deps", "trove4j", None),
        find_jar(gradle_home, "org.jetbrains", "annotations", None),
    ]
    library = [
        find_jar(gradle_home, "org.jetbrains.kotlin", "kotlin-stdlib", kotlin),
        find_jar(gradle_home, "org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", serialization),
        find_jar(gradle_home, "org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm", serialization),
    ]
    if None in compiler or None in library:
        return None
    return java, os.pathsep.join(compiler), os.pathsep.join(library)


# 探针编译的 JVM 参数：固定堆上限、无界面；保留默认分层编译（不用 TieredStopAtLevel=1——那会让 JVM 把
# CodeCache 缩到 48 MB，Kotlin 编译器在高负载下会撑满并以 VirtualMachineError 失败），并显式给足 CodeCache。
PROBE_JVM_OPTIONS = ["-Xmx1536m", "-XX:ReservedCodeCacheSize=256m", "-XX:+UseParallelGC", "-Djava.awt.headless=true"]
# 单次编译的时限（秒）：空闲机器约 10 秒；可用 COULI_GEN_KOTLINC_TIMEOUT 调整。超时后自动再试一次。
PROBE_TIMEOUT_SECONDS = int(os.environ.get("COULI_GEN_KOTLINC_TIMEOUT", "900"))
PROBE_ATTEMPTS = 2


def load_average() -> str:
    try:
        return " / ".join(f"{value:.1f}" for value in os.getloadavg())
    except OSError:
        return "未知"


def run_probe_compiler(command: list, timeout: int) -> tuple[subprocess.CompletedProcess | None, str]:
    """跑一次编译；超时返回 (None, 说明)。stdin 关掉，免得子进程等终端输入。"""
    try:
        return subprocess.run(command, capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL), ""
    except subprocess.TimeoutExpired as expired:
        tail = (expired.stdout or "")[-2000:] if isinstance(expired.stdout, str) else ""
        return None, f"超过 {timeout} 秒未结束（当时负载 {load_average()}）。{tail}"


class CompileProbeTest(SnapshotCase):
    def test_probe_output_compiles(self):
        found = kotlin_compiler()
        if found is None:
            if os.environ.get("COULI_GEN_REQUIRE_KOTLINC") == "1":
                self.fail("找不到 Gradle 缓存里的 kotlin-compiler-embeddable 等 jar（先跑一次 ./gradlew 构建）")
            self.skipTest("Gradle 缓存里没有 kotlin-compiler-embeddable，跳过编译测试")
        java, compiler_cp, library_cp = found
        self.add_enum_file("zprobe.json", probe_enum_file(PROBE_KEYS))
        self.generate()
        sources = [
            os.path.join(self.out, "core/generated/src/main/kotlin"),
            os.path.join(self.out, "core/generated/src/debugFeatures/kotlin"),
        ]
        classes = os.path.join(self.tmp, "classes")
        command = [java, *PROBE_JVM_OPTIONS, "-cp", compiler_cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                   "-no-stdlib", "-no-reflect", "-classpath", library_cp, "-d", classes, *sources]
        notes = []
        result = None
        for attempt in range(1, PROBE_ATTEMPTS + 1):
            shutil.rmtree(classes, ignore_errors=True)
            result, note = run_probe_compiler(command, PROBE_TIMEOUT_SECONDS)
            if result is not None:
                break
            notes.append(f"第 {attempt} 次：{note}")
            print(f"探针编译第 {attempt} 次超时（{PROBE_TIMEOUT_SECONDS} 秒，负载 {load_average()}），重试", file=sys.stderr)
        if result is None:
            self.fail(
                "探针编译超时：机器负载过高时 Kotlin 编译器跑不完，不是生成物错误。"
                "等负载降下来重跑 tools/ci/verify.sh，或设置 COULI_GEN_KOTLINC_TIMEOUT=<秒> 放宽时限。\n"
                + "\n".join(notes)
            )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue(
            glob.glob(os.path.join(classes, "com", "couli", "generated", "ContractString.class")),
            "编译产物里没有 ContractString.class",
        )


if __name__ == "__main__":
    unittest.main()
