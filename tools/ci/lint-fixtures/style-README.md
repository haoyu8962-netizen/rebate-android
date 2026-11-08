# LINT-STYLE Android v1 样例

这些文件只供源码扫描，不编译、不打包。按 D10-734 验证；没有中文字符串或 SDK 初始化干扰。

| fixture 目录 | 预期 |
| --- | --- |
| style-no-hex-color | 失败；no-hex-color × 1；Transparent 不报 |
| style-no-numeric-spacing | 失败；no-numeric-style × 4；每个 dp 字面量计一次 |
| style-no-numeric-radius | 失败；no-numeric-style × 2；整数圆角及 dp 圆角 |
| style-no-numeric-text | 失败；no-numeric-style × 1 |
| style-exemption-reason | 失败；exemption-reason × 1，原 no-hex-color × 1 仍报 |
| style-legal | 通过；零值、令牌、数字常量引用、普通尺寸、边框宽、带理由豁免 |
| style-system-buttons | 通过；无 design-system-required 警告（Button / TextButton / IconButton 不在 G1 名单，编排 D10-830 / ui-rules/android.md §8） |

执行 `python3 tools/ci/tests/test_hardcoded_style_cli.py`：测试复制 fixture 到临时根，
通过现有 `couliRules -Pcouli.rules.root=…` 跑真实任务，不改本目录与仓库基线。
生成测试使用待实现属性 `-Pcouli.rules.writeBaseline=true`；生成位置为扫描根下
`tools/ci/hardcoded-style-baseline.json`。普通检查必须读取该根的基线，不得自动重写。

基线格式为 `{ "couli-style/<rule>": { "repo/relative/File.kt": count } }`；
规则键、路径键排序，UTF-8、两格缩进、末尾换行、零条目不写。
诊断沿用仓库格式 `路径:行: [no-hex-color] …` / `[no-numeric-style]` / `[exemption-reason]`。

安卓 v1 豁免采用行尾注释：
`// couli-style-disable-line couli-style/no-hex-color -- 外部品牌色，组件令牌没有等价项`
只豁免本行指定规则。没有 `--` 或理由为空白，报 exemption-reason，原违规保留。
G1 同样可用 `couli-style/design-system-required` 标注组件库没有替身的原因。
这里没有给现有页面追加豁免，也不改变旧 DS-02 的 13 类 fixture。

## v2 追加（LINT-STYLE-android 测试 v2，口径见 NATIVE/LINT-STYLE/tests-android-v2-handback.md）

| fixture 目录 | 预期 |
| --- | --- |
| style-v2-equivalent-forms | 失败；no-hex-color × 4（`0x…L`、按通道 Color、parseColor / toColorInt 的 `"#…"`），no-numeric-style × 9（TextUnit(…, Sp)、`.em`、`14f.sp`、TextStyle 两项、`RoundedCornerShape(8f)`、CutCornerShape、间距 `Dp(8f)`、contentPadding） |
| style-v2-equivalent-legal | 通过；dp 常量声明与引用、Dp(N) 宽高 / 描边、运行时 parseColor、字号 / 圆角令牌、Transparent |

`build/`、`.kotlin/` 这类目录被仓库 `.gitignore` 忽略，不能做成入库 fixture；源码集内这类目录照扫、
模块构建产物不扫的断言放在 CLI 测试的临时树里（`test_v2_generation_covers_hidden_dirs_main_java_and_release`）。
