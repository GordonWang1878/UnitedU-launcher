# 实机兼容报告 / Real-TV compatibility reports

这张表只记**真电视上的实机结果**（自测或用户报告）。README 里「适用范围」那张表是 2026-10 的调研结论，两张表分开维护。
This table records **results measured on real TVs** only (our own tests or user reports). The table under "Compatibility" in the README is the October 2026 research summary; the two are kept separate.

想贡献一份报告？请用 [电视兼容性报告模板 / TV compatibility report](https://github.com/GordonWang1878/UnitedU-launcher/issues/new?template=compat.yml)，并在「补充 / Notes」栏写上：UnitedU 版本、固件版本号、有没有 Google 服务（GMS）、待机唤醒后按 HOME 进的是谁、打开主页键接管时音量键是否正常，以及测试时原厂桌面是开着还是已停用。
Want to add a report? Use the [TV compatibility report](https://github.com/GordonWang1878/UnitedU-launcher/issues/new?template=compat.yml) template, and in the Notes field include: the UnitedU version, the firmware build number, whether the TV has Google services (GMS), where HOME goes after waking from standby, whether the volume keys work with Home Button Takeover on, and whether the stock launcher was enabled or disabled during the test.

## 结果分档 / Result tiers

| 档名 / Tier | 含义 / Meaning |
|---|---|
| 已验证 / Verified | 原厂桌面开着时实测，主要功能都正常 / Tested with the stock launcher enabled; main features work |
| 部分可用 / Partially working | 能用，但有条件或有项目没测；原厂桌面停用时测得的结果最高只记这一档 / Works with conditions or untested items; results measured with the stock launcher disabled are capped at this tier |
| 不可用 / Not working | 实测装不上或不能当桌面用 / Tested and cannot be installed or used as the home screen |
| 待验证 / Not yet verified | 还没有实机结果 / No real-TV result yet |

前三档每条都必须附证据的完整 URL，并在「证据来源」列写明是自测、用户报告还是上游资料。
Every entry in the first three tiers must link its evidence with a full URL and state its source in the "Evidence source" column: our own test, a user report, or upstream material.

## 报告 / Reports

| 品牌型号（市场）/ Brand & model (market) | 系统 / 固件 / System / firmware | GMS | UnitedU 版本 / Version | 原厂桌面状态 / Stock launcher | 主页键去哪 / HOME goes to | 重启 / 断电 / 待机后 / After reboot / power cut / standby | 主页键接管 / Home Button Takeover | 接管时音量键 / Volume keys with takeover | 原厂桌面应用列表里有 UnitedU / Listed in stock app list | 信号源 / 屏保 / 遥控器 / Inputs / screensaver / remote | 结果 / Result | 证据来源 / Evidence source | 证据 / Evidence | 记录日期 / Date |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Hisense HiSmartTV A4（`xiaoyushan` / `m7322`，市场未确认） | Android TV 11，`RTT2.221108.001 / 00.00.00.18` | 有 / Yes | 1.2.0（41） | 海信桌面和 Google 桌面均已停用 / Hisense and Google TV launchers disabled | UnitedU | 重启、断电后保持；待机未测 / Kept after reboot and power cut; standby not tested | 未测 / Not tested | 未测 / Not tested | 未测 / Not tested | 都正常 / All OK | 部分可用 / Partially working（原厂桌面停用时测得，最高只到此档 / measured with stock launchers disabled, capped at this tier） | 用户报告 / User report | https://github.com/GordonWang1878/UnitedU-launcher/issues/1 | 2026-10-24 |
