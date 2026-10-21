# 平台内置图标（F-X-05a）

- 来源：规划仓库 couli `design/icons/platform/`，提交 `8209d6e31bf5e4eb336421f51aa5354eb6b539c2`（负责人 2026-10-19 提供的官方原图）。
- 拷法：`git show <提交>:design/icons/platform/<文件>` 逐字节原样写入；224×224 PNG，不放大、不缩小、不重压缩、不改色。
- 每个文件的源 SHA-256 与包内 SHA-256 登记在同目录 `manifest.json`（`transform: identity`）；单测 `Fx05aPlatformAssetTest` 对独立来源清单逐字节核对。
- 放在 assets 而不是 res/drawable：保留 `pinduoduo.png` 原文件名，且 AAPT 不对 assets 做 PNG crunch / 重编码。
- 用途：`PlatformBadge`（14dp）与首页金刚区平台格（44dp，APP-11v），入口 `platformIconAssetPath` / `PlatformIcon`。编码映射照规划/08 BR-TEXT-24：`pdd` → `pinduoduo.png`，其余编码同名。
- 暂缺（不入包、不自画）：天猫、微信、微信支付、支付宝、企业微信；这些平台的徽标只显示文字。`douyin-white.png` 本端未用，未入包。
- 本目录只放平台官方标识；凑狸自有品牌素材不得放进来。后台下发替换图归 F-X-05。
