# 官方图库资源生成与发布

资源发布独立于 APK。默认工具只在本地生成、检查和列出操作；不会访问网络、执行 wrangler 或读取凭据。云端上传与正式清单发布需要另行确认，本任务没有发布资源。

## 源目录与身份

固定源目录为 `/Users/gordonwang/GitHub/unitedu-gallery`，独立 Git 仓库仅追踪 `resources.json`、维护说明与目录占位文件；`images/`、`cards/`、预留的 `videos/` 内的原图不进入 Git，须另行备份。新增图片放入对应目录后发起“同步图库”，先审阅变化再确认上传。发布产物建议保存到独立的 `/Users/gordonwang/GitHub/unitedu-gallery-builds/`，完整保留每版图片、清单与 ID 历史；已保留 revision-1，当前共享图片产物为 revision-2。

在仓库外维护源目录，`resources.json` 示例：

```json
{"items":[{"id":"example-card","type":"image","usages":["cards"],"names":{"zh-CN":"示例","zh-TW":"範例","en":"Example"},"order":0,"packages":["com.example.app"],"credit":"作者；来源 URL；许可及授权范围","source":"cards/example.png"}]}
```

当前 type 仅允许 image；usages 为用途列表，共享背景图片使用 ["wallpapers", "screensavers"]，卡片只能为 ["cards"]。在线视频暂未推出。必须有三语名称、整数 order 和来源许可说明；卡片必须有精确包名。source 为源目录内相对路径。ID 与名称、排序分离；更新同一资源可以改变原图 hash，但不能把同一 ID 转作另一张不相关的图片。工具只能检查种类与历史 hash，语义身份和许可有效性由审阅者确认。

原图最长边 ≤8192、≤32 MiB；支持 JPEG / PNG / WebP。壁纸、屏保必须为现有双写法 HDR JPEG：结构化 MPF 索引必须关联基础图及增益图；Android 14 XMP 与 Android 15+ ISO 21496-1 元数据分别由真实 libultrahdr 解码器探测，参数有效且一致。需要已安装的 `/opt/homebrew/bin/ultrahdr_app`，也可通过 `UHDR_READER` 指定现有 reader；缺工具立即失败并给出安装指引。卡片为 SDR。工具拒绝不合格 HDR，不转换、不建立 HOME 备份；使用现有 `scripts/hdr-assets.py <input> <separate-output-directory>` 先转换，详见 [HDR 规范](hdr-image-spec.md)。SDR 无法凭空转换为 HDR。

## 本地生成与审阅

```bash
python3 scripts/gallery-assets.py --source /absolute/source --out /absolute/build-v1 --revision 1
python3 scripts/gallery-assets.py --source /absolute/source --previous /absolute/build-v1/index.json --out /absolute/build-v2 --revision 2
python3 scripts/gallery-publish.py --artifact /absolute/build-v2 --bucket unitedu --base-url https://dl.uniteduone.com/gallery/ --dry-run
```

out 必须是新的、位于源目录外的目录。工具先完整检查再原子移入 out；拒绝路径穿越、symlink、重复 ID / JSON 字段、损坏图片、非法类型（bool 不是整数）。原图字节保持不变；生成最长边 640、≤512 KiB 的 SDR JPEG 缩略图。清单 ≤2 MiB、最多 2000 项，SHA-256 为小写。

产物包含 `originals/<sha256>.<ext>`、`thumbnails/<sha256>.jpg`、客户端 `index.json`、审阅用 `changes.json`（新增/更新/下架）和本地 `id-history.json`（已用 ID / 资源族 images 或 cards / 内容 hash / 是否在架）。后三者中只有 index.json 会上传。完整保留每版产物，避免审计历史丢失。previous 可以是单独合法清单；若缺少同目录历史，只能审计该清单仍包含的 ID，无法发现更早已下架 ID，正式维护应始终携带历史。

在 changes.json 审阅变化，并打开原图与缩略图确认画面、名称、包名、来源及许可。下架通过从源 items 删除资源完成，旧对象不删除。仍在架的同 ID 允许更新原图；已下架 ID 只能恢复其历史已知原图 hash，因此允许更高 revision 回滚而阻止换图复用。

## 获确认后发布

```bash
python3 scripts/gallery-publish.py --artifact /absolute/build-v2 --bucket unitedu --base-url https://dl.uniteduone.com/gallery/ --publish
```

此显式参数是工具执行门；不是本任务的生产发布授权。发布器先完整验证所有本地文件，再用参数数组调用 wrangler `r2 object put --remote`。图片使用一年 immutable 缓存；上传后通过限定 HTTPS 同域图库目录 GET 读取并核对实际体积与 hash，重定向也受同一约束。整个远端连接/读取由 120 秒 Unix 进程定时器硬截止，持续 trickle 数据也不能延长；工具需要 macOS/Linux 主线程运行，不支持已经占用 SIGALRM 定时器的宿主。任一图片上传/验证失败，禁止上传新清单；清单最后上传，缓存 60 秒。失败可能留下未引用图片，重试可复用相同内容地址，不删除旧对象。仅上传最后清单时的服务端失败可能存在结果不确定，须另行检查正式 index.json 后决定是否重试。

回滚不是降低 revision：以当前最新版本作为 previous，恢复旧源内容/名称/排序并使用更高 revision。保留历史使已下架资源恢复可核对；所有原图及旧产物继续保留。

## 验证边界

图片检查解析真实 MPF 关联并解码基础图及增益图；保留长度及索引偏移屏蔽另一份元数据，分别交给现有 libultrahdr reader 验证两份编码及参数一致性，任意字面标记不构成 HDR；不宣称验证 HDR 光学表现、原始转换 PSNR 或许可真实性。源码工具为 Python，不声明 zsh/bash 数据传递支持。JSON 可保留真实多行、字面反斜杠 n/c；上传参数不经 shell。测试不联网，云端权限、CDN 行为与电视视觉效果仍须正式发布授权后的独立验收。

## 共享图片清单兼容

新产物 schemaVersion 为 2，以 type 与 usages 分开描述资源和用途。客户端和生成器继续读取版本 1 的 kind 字段，映射为原来的单一用途。历史 wallpapers/screensavers 统一按 images 资源族比较，已有 ID 和原图 hash 不变。卡片与背景图片不可互换身份。

旧 APK 无法读取版本 2；正式切换清单前必须先提供支持新版的 APK。现有用户上传视频屏保继续可用，在线视频清单本期拒绝。两场景独立采用，共用原图；本地记录保留已经支持过的用途，防止清单撤销用途使已选图片失效。
