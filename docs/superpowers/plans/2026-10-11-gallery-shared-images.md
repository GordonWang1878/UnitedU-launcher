# Gallery共享图片执行清单

依据docs/design/gallery-shared-resource-proposal.md，用户选定只做图片。BASE22f7028。

1. 客户端与Python共同采用schemaVersion2、type=image、usages非空集合；兼容v1单kind，写出v2。cards用途独占且须包名，视频拒绝。回归旧清单、共享筛选、非法组合。
2. 更新客户端采用/播放器/壁纸判断，缓存同ID/hash元数据合并已知用途、保留原文件。回归一次下载、旧用途保护/恢复；在线屏保分组标注视频暂未推出。
3. 源目录归并images并保持11原图hash；4合格清单写双用途，7待兼容保留；旧revision1不改，生成revision2、离线预演及Python/Kotlin交叉清单验证；同步文档。
4. 必要定向测试后最终一次全JVM及Debug/Release构建；一次最终改动审查，处理实质问题后本地提交，不上传/推送。

预检：源与客户端usages规则一致；旧history kind须规范化为image背景/卡片两种身份；元数据恢复文件须同步新用途；共享资源只按hash保护与下载；视频提示仅针对在线资源。

状态：1–4 已完成；验证结果与未验范围见 WORKLOG 2026-10-11。未发布或推送。
