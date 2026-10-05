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
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

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
        result = subprocess.run(
            [java, "-cp", compiler_cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
             "-no-stdlib", "-no-reflect", "-classpath", library_cp, "-d", os.path.join(self.tmp, "classes"),
             *sources],
            capture_output=True, text=True, timeout=600,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue(
            glob.glob(os.path.join(self.tmp, "classes", "com", "couli", "generated", "ContractString.class")),
            "编译产物里没有 ContractString.class",
        )


if __name__ == "__main__":
    unittest.main()
