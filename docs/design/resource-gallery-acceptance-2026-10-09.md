# 官方图库本地集成验收 · 2026-10-09

客户端、发布工具和主要模拟器使用路径已验证；修复实际孤儿清理成功却显示失败的问题。生产图库、正式 APK 和真机验收尚未发布/执行。版本保留 1.0.3/code 6。

## 验收证据层级

| 设计 §9 项 | 实际证据 | 限制 |
|---|---|---|
| 解析、三语、稳定 ID、排序、路径、卡片精确包名 | 全量 JVM；Task7 最终真实三类产物由 Kotlin 解析，逐个原图/缩略图 byte/hash 一致；模拟器 Settings 卡片只出现适用资源 | 三语实际布局未逐语重放 |
| 首次离线、缓存离线、304、刷新失败 | 新目录离线只有内置/我的及网络提示；缓存离线保留目录；HTTP 日志实际两次304与503，旧选择/网格保留 | 同时打开两个 Activity 的共享刷新由 JVM 验证 |
| 下载成功、坏 hash、截断、取消、重试 | 慢下载12%取消回原格，旧选择不变；坏hash/截断提示校验失败；重试采用成功 | 读取超时、120秒截止、ENOSPC/写盘故障采用 JVM 注入证据，未填满设备或等实际120秒 |
| 迟到任务与页面关闭 | HOME 关闭慢下载页，选择不变且没有遗留 part；旧/新意图串行及旧页面取消不误伤新页面由 JVM 验证 | 没有在所有竞争时刻手工强杀进程；启动实际清理 seeded stale.part |
| 壁纸/卡片采用 | 壁纸持久化 id/hash并关闭页，冷启动不变；卡片关闭页，9092字节独立副本 hash 等于原图 | Settings卡片原文件备份缺口见下节 |
| 屏保加入/移除与共享播放 | ✓截图；移除写空集合，重加恢复id/hash；排除内置后，桌面屏保及系统Dream实际渲染同一screen-00 | 未重放完整旧上传视频/全部legacy E2E |
| 系统Dream真正进入 | dumpsys `mCurrentDream=DreamRecord(...UnitedUDream...)`，窗口 `android.service.dreams.DreamActivity`；全部5项系统设置恢复比较一致 | 仅模拟器；不以截图或Somnambulator启动成功代替证明 |
| 内容更新不暗换图、下架保留 | revision B新hash显示Update available，旧选择不变；明确重选才改hash；revision3下架后两旧版本仍可见，断开本地网络后选旧版成功并关页 | 补验同一mounted picker：插入+重排保持wall-04；下架wall-04落同组相邻wall-inserted，均1焦点且有坐标/截图。重新开页默认落焦不算证明 |
| 内置/我的、遥控焦点 | 内置保持可见、我的上传入口保持；取消回原格；按住DPAD_DOWN后1个焦点且bounds在屏内，自算offset网格可到保留版本 | 上传图片新增/视频全路径未重放；焦点截图不是动画时序证明 |
| 原图HDR保真 | 真正下载1557093字节，SHA256与源完全一致；Android14 BitmapFactory探针3840×2160，`hasGainmap=true`；采用原图路径 | 模拟器不能证明HDR亮度/电视面板视觉表现，真机须经正式检查更新路径另行验收 |
| 删除保护/未使用下载/孤儿清理 | 当前壁纸拒删；未使用wall-01原图删除且状态更新；孤儿d*64文件删除，修复后不再失败提示；保护hash未变 | 原图自动淘汰未实施，设计就是显式清理 |
| 发布完整、顺序、回滚 | Python20项、实际三类离线产物及交叉parser；假上传/HTTP验证先图片后清单及失败不提交、递增revision回滚 | 没有生产CDN、许可真实性或云端凭据/上传检查 |

## 实际缺陷及修复

`GalleryStore.cleanupOrphans` 返回**成功删除的 hash**，菜单误把这个非空集合当成尚未删除的残留。因此原图确实删掉，却显示失败。调用方现在先清理，再重查 `orphanedDownloads - protected`，只对真实残留报错，异常仍进入失败提示。

行为回归先观察 GalleryPickerTest12项中1项失败，再12/12通过；同一模拟器人工孤儿菜单重测成功返回刷新格，无失败提示。没有改动存储接口语义或原图删除保护。

同时收掉四个小问题：设置只解析一次图库字段；提示页使用中性的「在线图库」标题；测试名称对应旧/取消意图断言；清单大小测试输入改为真正有效的超限JSON。

## 最终验证与产物边界

```sh
source scripts/env.sh && gradle --no-daemon testDebugUnitTest assembleDebug assembleRelease
python3 -m unittest discover -s scripts/tests -p 'test_gallery_*.py'
git diff --check
```

最终全套在清理修复后成功：**906项，905执行通过、1既有忽略、0失败/异常；84个JVM suites；Python20/20**。忽略项为 `StandbyScheduleBoundaryTest.hugeSumsSaturateInsteadOfWrappingIntoThePast`。debug/release及release必要lint/R8成功。日志 `/tmp/task8-final2-gradle.log`、`/tmp/task8-final2-python.log`；临时日志可能随会话清理，结论及证据摘要保留在本文。

生产构建没有临时签名init或图库URL属性覆盖；解包APK的所有dex没有18766、fixture根、loopback图库URL，ZIP没有fixture文件。release BuildConfig使用 `https://dl.uniteduone.com/gallery/`、DEBUG=false、1.0.3/code6。现有APK更新器loopback测试支持不属于图库fixture泄漏。

## 数据保留与例外

只操作专用 emulator-5560，没有 uninstall/pm clear、没有触碰其他模拟器或真机。临时debug使用既有签名仅为保留模拟器数据，未改正式签名配置。

设置/layout在测试前保存，恢复后JSON比较一致；内部gallery保存完整tar，恢复后10个文件的SHA256逐个与基线一致。系统屏保3个secure值、超时和stay-awake共5项恢复一致，电池模拟覆盖reset。fixture目录A清单已恢复，临时探针/测试目录删除，本地HTTP服务停止。源码资源图片未覆盖。

**保留例外：采用Settings卡片前没有备份 `icons/com.android.tv.settings.png`，无法证明此前不存在或恢复其旧内容。Task6仅预览，没有该文件原状态证据。本轮已留下测试卡片文件，不基于“原来为空”的猜测删除它；该单文件不能宣称恢复。**

## 截图与复核入口

真实观察截图在 [resource-gallery](../screenshots/resource-gallery/)：`wallpaper-adopted`、`card-adopted`、`screensaver-joined`、`custom-screensaver`、`system-dream`、`cached-offline`、`first-offline`、`retained-version`、`hdr-wallpaper-adopted`、`invalid-original`、`long-list-focus`、`cleanup-success`。截图用于确认画面，持久化/HTTP/解码/Dream结论另有上述独立证据。`protected-download`与`refresh-failed`截图来自提示标题小修前APK，保护/失败行为有效，当前标题已统一为在线图库。

设计见 [官方图库](../superpowers/specs/2026-10-08-resource-gallery-design.md)，发布步骤见 [发布工具](resource-gallery-publishing.md)。实际上传、推远程、正式APK发布和电视验证是独立授权边界，本文不宣称上线。

## Task8 独立评审补验（2026-10-09）

有界核查原Settings图恢复来源：`/tmp/gallery-task6-ui`9项、`/tmp/task8-restoration`2项、`/tmp/unitedu-e2e`229项、`/tmp/gallery-task6-fixture`34项，以及/tmp顶层包名/icon候选，未找到独立的测试前原图。模拟器icons目录仅有22:02写入的9092字节测试图，无prev/part；`/tmp/task8-card-copy.png`是采用后拉取的测试图，不是恢复源。基线layout三行空应用不记录icons文件，也不能证明文件原来不存在；Paths.iconFor按包名固定映射到这一basename，没有可据此认定的旧别名。gallery基线tar包含内部gallery10个文件，不包含外置icons。

因此，本轮已知来源没有可认证的原Settings图字节，仍无法确认此前文件存在与否，**无法恢复或证明恢复；完整数据保留约束尚未满足，该例外待Gordon/控制器处置**。没有猜测原图、替换成系统默认图或删除当前测试文件。这里不是对设备全部潜在法证恢复可能性的断言。

同页焦点补验采用延迟manifest响应，没有产品debug hook或功能修改。在缓存网格聚焦wall-04后释放revision10（插入wall-inserted并把wall-04重排到首项）：wall-04保持唯一焦点，控件bounds由`(548,586,940,860)`移到`(548,280,940,554)`，截图里焦框确实跟随这一紫色资源。然后在同一picker从刷新菜单启动下一请求，返回网格、移到wall-04再释放revision11（下架未下载wall-04）：焦点按同组旧位置落到邻项wall-inserted，仍只有1个焦点、bounds`(548,280,940,554)`。三个画面均已目视核对名称和焦框；两次响应之间没有返回/关闭或重开picker。

持久证据：[same-page-focus-evidence.json](../screenshots/resource-gallery/same-page-focus-evidence.json)与同目录三张`same-page-*`截图；临时逐步输出`/tmp/task8-focus.log`。最初Activity捕获过滤字段写为mResumedActivity而实际字段是topResumedActivity，输出为空，因此没有将两个空值相等当作Activity身份保持的证明。真正依据是同一次挂载中的按键/响应序列、焦点自报及实际画面。

这轮补验只改目录缓存和已备份设置，恢复后settings/layout JSON比较一致，gallery10个文件逐个SHA256与原基线一致；Settings未知原图仍保持不动。只增加文档/证据，不修改功能代码或重复全套构建。既有最终全套结果仍是906项JVM（905执行通过、1既有忽略）、Python20/20及两构建。

## 最终评审修复（2026-10-09）

I1/I2/M1/M2已修复：在线缩略图按格子实际像素长边使用二次幂采样，保留真实编码宽高供卡片横幅判定；每页记录缩略图失败并显示失败标记/菜单重试。手动刷新完成（含304、目录不变及清单失败）推进页面缩略图代次，只有可见格子的produceState重启；稳定cell key/FocusRequester不重建。换代、离屏或页面离开取消旧effect的仓库等待者，取消刷新不推进代次，原图预览/采用状态独立。

新增回归覆盖真实195759字节8192² PNG采样计划、真实源比例、窄/奇数/非法bounds、同一mounted可见代次观察者失败→304→成功、明确重试、隐藏格不下载、取消刷新及最后缩略图等待者释放。最终针对图库85/85通过。Android14 emulator-5560的app_process直接调用本次APK的生产decodeGalleryThumbnail与Apps.cardBackdropFor：8192²→256×256，实际allocationByteCount=262144；7680×4320→480×270，allocation=518400、backdrop=null（横幅）。探针没有安装APK、进入选图页或写应用数据，本次专用临时设备目录已删除；没有重跑UI矩阵。

最终全量结果：912项JVM，911执行通过、1既有忽略、0失败/异常，86 suites；Python20/20。debug/release、release lint/R8成功（BUILD SUCCESSFUL in 1m22s）；两APK全部dex无本轮/既有图库fixture端口与临时目录标记，ZIP无fixture文件，生产HTTPS根正确。版本保持1.0.3/code6，没有发布或推远程。

**I3仍未关闭：Settings卡片测试前原字节没有可信备份，不能恢复或证明恢复。本轮完全未触碰icons文件，保留现有文件与既有备份；完整数据保留例外仍待Gordon/控制器明确处置。** 本轮没有新增真机、三语实际布局或完整legacy E2E证据，既有限制保留。
