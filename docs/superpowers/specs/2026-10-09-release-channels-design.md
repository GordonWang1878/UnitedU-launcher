# 稳定版 / Beta 双通道发布设计(2026-10-09)

Gordon 定的三条(grilling,2026-10-09):

1. **同一个应用 + 通道开关**:包名、签名不变,用户在应用里选「稳定版 / Beta」。
2. **切回稳定版用回退包**:当场回到稳定版、布局与设置保留。
3. **main = Beta,稳定版从 Beta 晋级**:新功能在 main 开发、发 Beta;某个 Beta 验证够了,同一个提交重新构建成稳定版。稳定版急修从上一个稳定 tag 拉临时 hotfix 分支。

## 1. 机制

现在只有一份 `latest.json`,检查更新只读它。双通道 = 三份清单 + 应用按通道决定读哪几份:

| 清单 | 内容 | 谁读 |
|---|---|---|
| `latest.json` | 最新稳定版(**地址与格式不变**,老版本应用照旧读它) | 所有人 |
| `beta.json` | 最新 Beta | Beta 通道 |
| `rollback.json` | 当前稳定版源码、versionCode = 最新 Beta + 1 的回退包 | 从 Beta 切回稳定、且已装版本高于稳定版的人 |

- **Beta 通道**同时读 `latest.json` 与 `beta.json`,取 versionCode 大的那份——稳定版追上 Beta 时,Beta 用户也收到它。
- **稳定通道**只读 `latest.json`;若已装的 versionCode 比它大(刚从 Beta 切回来),改读 `rollback.json`。
- 安装链路(SHA-256、包名、versionCode、签名证书核对)完全复用,不加新路径。

## 2. versionCode 与命名

- versionCode 是**全局单调计数**,三类包共用:例 稳定 6 → Beta 7 + 回退包 8 → Beta 9 + 回退包 10 → 稳定 11。
- Beta:versionName `1.1.0-beta.1`,tag `v1.1.0-beta.1`,GitHub 标 **prerelease**(不影响 `releases/latest` 指向稳定版)。
- 回退包:versionName 与它所含的稳定版相同(用户看到的仍是「1.0.3」),不建 tag,只作为 `channel-beta` 这个固定 prerelease 的附件。
- 晋级稳定:同一提交、去掉后缀重新构建(versionName 带 `-beta.N` 不能直接当稳定版发),versionCode 照计数取下一个。

## 3. 发布地址

- R2:`dl.uniteduone.com/unitedu/{latest,beta,rollback}.json`。
- GitHub 兜底:`latest.json` 仍是 `releases/latest/download/latest.json`;`beta.json` / `rollback.json` 挂在固定 tag `channel-beta` 的 prerelease 上(每次 `--clobber` 覆盖),地址 `releases/download/channel-beta/<名>.json`。
- `release.sh` 增加模式:`release.sh <版本>`(稳定,同现在)、`release.sh <x.y.z-beta.N>`(Beta:构建 Beta + 从上一个稳定 tag 构建回退包,两份清单一起发)、晋级就是对同一提交跑稳定模式。

## 4. 界面(R164)

- 关于页加一颗胶囊「更新通道:稳定版 / Beta」,进选项层二选一(关于页胶囊 2 → 3,仍 ≤ 6)。左侧说明:Beta 先拿到新功能、可能不稳定、随时可切回且不丢布局。
- 选完立即检查一次更新:切到 Beta → 有 Beta 就提示下载;从 Beta 切回稳定 → 提示「回到稳定版 1.0.3(布局与设置保留)」。
- Beta 包的关于页版本号后标「Beta」。
- 设置字段 `updateChannel`(`stable` 默认 / `beta`),进 settings.json,走 `LockedFile`。

## 5. 数据格式向下兼容铁律

回退包是**旧代码读新数据**:Beta 写进 layout / titles / hidden-inputs / settings 的东西,稳定版必须读得懂或能忽略,否则切回时读盘失败退回默认(2026-09-23 事故同型)。

- Beta 只**新增**字段,不改既有字段的含义与类型,不改文件名与目录结构。
- 每个读盘函数都有单测:含未知字段的样本照常解析,已知字段不丢。
- 某个 Beta 确需改格式时,先发一个「能读新格式」的稳定版,再发写新格式的 Beta。
- 铁律写进 CLAUDE.md。

## 6. 不做

- 不做独立 Beta 包名、不做功能开关、不做长期 stable 分支。
- 不做「卸载重装」路径。
