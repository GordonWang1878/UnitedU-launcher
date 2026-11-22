# 云端发布第一阶段：离线计划

这一阶段只增加一个不能发布的计划入口。它不取代 `scripts/release.sh`，也不改变应用版本、签名或已有 CI。

## 这一阶段做什么

- `scripts/cloud_release_plan.py` 核对版本号、完整提交 SHA、非空发布说明，以及三项非密钥通道地址。
- 成功时只在标准输出写一份 dry-run JSON。退出码 0 表示计划通过，不表示已经发布。
- 历史检查使用调用方提供的离线快照，区分稳定版与 Beta，并为 Beta 算出下一个回退号。快照里的重复 tag、残缺 Release、versionCode 冲突和已保留的回退号都会失败。
- 失败报告按 `inputs`、`source`、`channels`、`history`、`capability`、`effects` 分段。程序不删除、不覆盖、不重试已经存在的 tag 或 Release。
- `.github/workflows/cloud-release-plan.yml` 只能手动 `workflow_dispatch`。权限只有 `contents: read`。它不读取 Actions secrets，不调用 `scripts/release.sh`。
- `execute`，以及 `UNITEDU_PLAN_ALLOW_PUBLISH`、`UNITEDU_RELEASE_ENABLED` 这两个开关，只会被拒绝。发布能力在代码里固定为 `absent`。

## 这一阶段不做什么

- 不构建 APK，不读取或生成签名材料，不核对 APK 证书。
- 不创建、删除或推送 tag，不创建 GitHub Release，不上传 R2。
- 不查询 GitHub Releases、`dl.uniteduone.com` 或其它线上清单。因此它不能证明线上 tag 与调用方快照一致。
- 不修改 `versionName`、`versionCode`，不修改 `scripts/release.sh` 的发布步骤。
- 不配置 GitHub Environment、secret、分支保护或付费资源。没有实际派发过这个工作流。
- 对话里确认版本、提交和说明只是流程约定。计划 JSON 里 `approvalEnforced` 固定为 false，工作流没有批准记录可核验。

## 工具链

`scripts/env.sh` 在 macOS 上仍把 `JAVA_HOME`、`ANDROID_HOME` 和 `PATH` 设到 `~/Library` 下的 JDK 17、Android SDK 和 Gradle 8.14.5。其它系统不覆盖已经设好的这三个值；缺目录或缺 `gradle` 就失败，不下载、不猜测路径。`UNITEDU_TOOLCHAIN_OS` 只给测试指定系统名，平时不要设置。

本阶段的计划工作流不 source `scripts/env.sh`，也不需要 Android SDK。将来若在 Linux 上构建，必须由运行环境自己提供 JDK 17、Android SDK platform 35、build-tools 35.0.0 和 Gradle 8.14.5。

## 非密钥参数

计划必须显式得到这些值。仓库里看得到的是提交内容，不是某一台开发机的最终生效值。

| 参数 | 本阶段基线提交 `4fb71e30f3437d74bf4616db8669279ea484bee2` 里能看到的值 |
| --- | --- |
| `versionName` | `1.2.1-beta.1` |
| `versionCode` | `42`（`versionCodeOverride` 不存在时的值；计划不接受覆盖） |
| `unitedu.updateUrls` | `https://dl.uniteduone.com/unitedu/latest.json,https://github.com/GordonWang1878/UnitedU-launcher/releases/latest/download/latest.json` |
| `unitedu.betaUrls` | `https://dl.uniteduone.com/unitedu/beta.json,https://github.com/GordonWang1878/UnitedU-launcher/releases/download/channel-beta/beta.json` |
| `unitedu.rollbackUrls` | `https://dl.uniteduone.com/unitedu/rollback.json,https://github.com/GordonWang1878/UnitedU-launcher/releases/download/channel-beta/rollback.json` |
| 发布说明 | 不在仓库里，每次计划单独传入 |
| 提交 SHA | 必须等于被计划工作区的 `HEAD`；工作流还要求等于 `github.sha` |

`app/build.gradle.kts` 在 Gradle 属性缺失或空白时另有回落：`unitedu.updateUrls` 变成只含 GitHub `latest.json` 的一条地址，`unitedu.betaUrls` 和 `unitedu.rollbackUrls` 变成空串。计划程序不使用这三条回落。属性缺失、为空，或与传入值不一致，都失败。

## 未读取、不臆造的值

- 开发机的 `~/.gradle/gradle.properties`，以及 `ORG_GRADLE_PROJECT_unitedu.updateUrls`、`ORG_GRADLE_PROJECT_unitedu.betaUrls`、`ORG_GRADLE_PROJECT_unitedu.rollbackUrls`。`scripts/release.sh` 构建时不传 `-P` 覆盖这三项。若某台机器用它们覆盖了仓库，云端只检出仓库时会得到另一套地址。本阶段没有读取那台机器，不能确认提交里的三行就是目前对外发布所使用的值。
- `R2_BUCKET` 与 `R2_BASE_URL`。`scripts/release.sh` 只在两者和 `wrangler` 都存在时上传。脚本注释写过桶名 `unitedu`、基址 `https://dl.uniteduone.com`。那是注释，不是本阶段确认过的云端配置。这里不读取环境、不上传，也不声称旧的 R2 镜像会跟着 GitHub 发布更新。
- 签名密钥、keystore 口令和任何 Actions secret。不读取、不命名、不配置。
- 下一次要发布的版本号、说明和被批准的提交。本文件不代为选择。
- 线上 `latest.json`、`beta.json`、`rollback.json` 的 versionCode。本阶段没有重新请求它们。调用方必须把离线快照交给计划程序；检查结论只对那份快照成立。

离线快照的形状是：

```json
{
  "tags": ["v1.2.0", "channel-beta"],
  "releases": ["v1.2.0", "channel-beta"],
  "manifests": {
    "latest": {"versionCode": 41, "versionName": "1.2.0"},
    "beta": {"versionCode": 42, "versionName": "1.2.1-beta.1"},
    "rollback": {"versionCode": 43, "versionName": "1.2.0"}
  }
}
```

上面的数字只说明快照形状，不是本阶段重新查询到的线上清单。

`beta` 与 `rollback` 可以同时为 `null`，表示还没有这一对清单。只缺其中一个、versionCode 不连续、tag 与 Release 只存在一边，都按残缺状态失败。`channel-beta` 若出现，tag 和 Release 必须同时在场。未知字段失败，避免快照里夹带一个发布开关。

## 稳定版与 Beta

稳定版计划的预期顺序是版本 tag 上的 APK，然后 `latest.json`。它不写 `beta.json` 或 `rollback.json`。Beta 计划的预期顺序是版本 tag 上的 Beta APK、`channel-beta` 上的回退 APK、`rollback.json`，最后 `beta.json`。回退包不建新 tag，versionName 取快照里的最新稳定版，versionCode 是本次 Beta + 1。这些顺序只写在计划里，`performed` 全部为 false。

## 未来的生产接口

下面只是后续需要单独批准的设计，本仓库没有接上：

- 另一条手动工作流，在计划退出码为 0 之后才构建。
- 由运行环境提供 Linux 工具链，并把三项 `unitedu.*Urls` 显式传给 Gradle，避免静默用上代码回落。
- 签名材料与创建 Release 的权限来自当时另行配置的 secret 和 GitHub Environment。失败时不删除已有 tag，也不自动重试。
- R2 仍不在第一阶段内。要恢复镜像同步，需要单独确认桶名、基址和凭据。

这条未来路径没有 workflow 文件，没有 secret 名称，也没有 Environment。

## 自测范围

`python3 -m unittest scripts/tests/test_cloud_release_plan.py` 使用临时 git 仓库、假命令和仓库里的当前文本。它是作者自测，不是独立验收，也不是一次真实发布。macOS 路径只在测试里用 `UNITEDU_TOOLCHAIN_OS=Darwin` 核对赋值；没有在 Mac 上 source `scripts/env.sh`。没有运行 `scripts/release.sh` 的构建或 `--dry-run` 发布流程。
