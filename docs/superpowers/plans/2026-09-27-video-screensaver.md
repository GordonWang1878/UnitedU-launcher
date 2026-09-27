# 视频屏保 实施计划(2026-09-27,R100 起)

> 需求(Gordon 2026-09-27):两种动态屏保都要;照片动感已完成(R95),这是视频那一半。
> 分支 `feat/video-screensaver`(基于 main `22ed745`),工作区 `UnitedU-wt-video`。

**目标**:屏保图库里短视频与照片混放;手机上传网页的「屏保」分页收视频;图库网格有首帧缩略图 + 播放角标 + 时长;
全屏预览循环静音播放;屏保(桌面自定义屏保 + 系统屏保 `UnitedUDream`,同一个播放层)轮到视频时静音完整播放一遍
(上限 60 s),照片 ↔ 视频用 R95 同一种 1.4 s 交叉淡化;失败跳过,不卡、不黑屏。

**不加依赖**:系统 `MediaPlayer` + `TextureView`(TextureView 才能吃 `graphicsLayer` 的 alpha,交叉淡化要它;
SurfaceView 的 alpha 只在 API 29+ 有限支持)。

## 裁定(spec 编号)

| 编号 | 内容 |
|---|---|
| R100 | 屏保图库 = 照片(jpg/jpeg/png/webp)+ 视频(mp4/m4v/mov/webm);扫描、计数、「图库非空」判据、网格、播放器同一份类型表 `ScreensaverMedia` |
| R101 | 视频播放规则:静音;完整播一遍,**超过 60 s 只播前 60 s**(`VIDEO_MAX_PLAY_MS`);只有一项可播且是视频时从头循环;不做推拉摇移;进出都是 R95 的 1.4 s 交叉淡化(新项在旧项之上淡入);首帧出来才开始淡入;解码失败 / 8 s 内出不了首帧 → 跳过该项并记入本次会话的失败表,Log |
| R102 | 资源:进程内同一时刻最多一个 `MediaPlayer`(`VideoGate`,后来者直接收回前一个);照片之后是视频时只 `prepare` 不 `start`(预加载);视频之后是视频不预加载,前一个的最后一帧截成静图垫底、释放后再准备下一个;退出屏保(任意键 / 系统屏保结束 / 退到后台)当场截帧、释放 |
| R103 | 上传:屏保分页逐个文件走原始字节上传 `POST /api/upload-raw`(不经 NanoHTTPD multipart 的整包落盘 + mmap,32 位进程映射 500 MB 会失败),视频单文件上限 500 MB,网页提示;服务端校验扩展名 + 文件头(ISO BMFF `ftyp` / EBML)+ `MediaMetadataRetriever` 有视频轨;落盘仍走 `saveIntoLibrary`;`/file` 支持 Range(手机浏览器播视频要) |
| R104 | 图库网格:视频格 = 首帧缩略图(IO 线程 `MediaMetadataRetriever`,LRU 缓存)+ 右下角标「▶ 0:08」;全屏预览里视频循环静音播放;长按删除照旧;标题计数「张」→「项」 |

## 阶段

1. **纯逻辑 + 单测**(`ScreensaverMedia.kt`):类型识别、容器嗅探、播放时长上限、时长格式化、跳过失败项的下一项、
   等待预算、Range 解析、Crop 变换缩放。
2. **上传**:`UploadServer` 原始字节路由 + 类型 / 大小 / 可解校验 + Range;`index.html` 屏保分页 accept 视频、逐个上传、
   500 MB 提示、网页视频角标与预览;三语文案。
3. **图库网格 + 预览**:`VideoThumbs`(缓存)、`ThumbCard` 视频分支 + 角标、`ScreensaverPreview` 视频分支(同一个
   `VideoSurface` composable,looping)。
4. **屏保播放**:`VideoGate` / `VideoController`(MediaPlayer 状态机)/ `VideoSurface`(TextureView + 生命周期);
   `MotionSlideshow` 层模型扩成 照片 / 静图 / 视频;`ScreensaverPlayer` 计时按项:照片 = 间隔,视频 = 等 UI 报
   开始 / 完成 / 失败(带超时,无人渲染时不卡死)、`round` 让单视频循环。
5. **模拟器验证 + 文档**:ffmpeg 生成 H.264 / HEVC 测试视频,curl 上传;网格 / 预览截图;录屏按 pts 量完整播放与 1.4 s
   过渡;系统屏保到点触发一次;按键退出后 `dumpsys media.player` 确认释放。spec / DESIGN / CLAUDE.md 焦点表同步。

## 焦点

不新增可聚焦节点:视频格仍是 `PickerGrid` 的一格(焦点、冻结、看门狗一行不动);预览里视频只是画面,
按键仍由预览 Box 收。CLAUDE.md 焦点表只在「全屏预览」一行补一句视频。
