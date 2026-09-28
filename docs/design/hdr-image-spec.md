# UnitedU 壁纸 / 屏保生图规范(HDR)

2026-09-28 定。给两类读者:**第 1 部分给生图 AI(GPT 等)读**,管画面;**第 2 部分给 Gordon / Claude 读**,管「生完之后怎么变成安卓 14、15 都认的 HDR 文件」。

> 为什么分两部分:HDR 照片文件里有一份「说明书」(元数据),告诉系统增益图在哪、怎么用。它有两种写法——Android 14 只认安卓自家写法(Ultra HDR 1.0 / XMP `hdrgm`),Android 15+ 和苹果认国际标准写法(ISO 21496-1)。生图工具给的文件用哪种写法、写不写全,它自己控制不了(2026-09-28 那批 8 张只有 ISO 写法,Android 14 上当普通图显示)。所以「两种写法都有」由我们的转换脚本保证,不靠生图工具。

---

## 第 1 部分 · 给生图 AI 的要求

请按下面要求生成 UnitedU(电视桌面)的**壁纸**或**屏保**图片。

### 规格
- **尺寸**:3840 × 2160 像素(16:9 横图)。不要竖图、方图,不要加边框或留白。
- **HDR**:如果你能输出 HDR 照片,请输出**带增益图(gain map)的 HDR JPEG**(Ultra HDR 或 ISO 21496-1 格式都可以)。高光最多比普通白亮约 4–5 倍(约 2–2.3 档),不要更高。不能输出 HDR 就输出普通高质量 JPEG(质量 95 以上)。
- **色彩**:sRGB 或 Display P3。
- **不要**:任何文字、Logo、水印、签名、边框、UI 元素;真实人物的可识别面孔;品牌商标。

### 构图(电视桌面的界面会盖在图上)
- **左上角**(约左 25%、上 15%):有一排小按钮。这里保持相对简单、偏暗,别放主体。
- **右上角**(约右 30%、上 12%):有一行时钟文字。同样保持简单、偏暗。
- **下方 35%**:应用卡片压在这里,而且程序会从 30% 高度起往下逐渐压暗,到底部接近全黑。主体、最亮的高光都**不要放在下方 35%**。
- **主体与高光**放在画面**上方 30%–65% 高度、横向中间或偏右**的区域。
- **屏保**会缓慢推拉(放大 1.06–1.22 倍)并平移画面的 5–8%:四周各留约 12% 的「可以被裁掉」的余量,重要内容别贴边。

### 画面风格
- 整体**偏暗到中间调**,让白色文字、卡片在上面清楚;不要大面积纯白、纯亮区域(HDR 下会刺眼)。
- HDR 高光用在**点状或小面积**的光源上:灯光、车灯、霓虹、反光、星光、月亮、日出的一小片天空。
- 系列内风格统一(同一批 4 张壁纸 / 4–6 张屏保)。

### 交付
- 文件名随意,交给 Gordon 统一改名。

---

## 第 2 部分 · 生完之后(Gordon / Claude)

1. **原图先放仓库外**,别直接放进 `app/src/main/assets/builtin/`:例如 `~/unitedu-assets-originals/<日期>/wallpapers/`、`…/screensavers/`。
2. **改名**:`两位序号-短名.jpg`(短名可用中文,规则见 `docs/design/builtin-assets.md`);壁纸 01 = 默认壁纸。**发布后不能改名。**
3. **跑转换脚本**(Claude 代跑也可以):
   ```bash
   scripts/hdr-assets.py ~/unitedu-assets-originals/<日期>/wallpapers app/src/main/assets/builtin/wallpapers
   scripts/hdr-assets.py ~/unitedu-assets-originals/<日期>/screensavers app/src/main/assets/builtin/screensavers
   ```
   脚本做的事:4K 分辨率不变;普通画面 JPEG 质量 90;增益图转单通道(Android 14 写法只支持单通道)、质量 85;**同一个文件同时写 Android 14 的 XMP 和 Android 15+ 的 ISO 两份说明书**;然后逐张校验并打印:两份标记都在、最大提亮倍数与原图一致、普通画面 PSNR ≥ 38 dB。任何一张不过,脚本以非 0 退出。没有增益图的原图只按 SDR 压缩并警告(不会凭空造 HDR)。
4. **体积参考**:2026-09-28 那批 8 张 4K,原图 31 MB → 转换后约 10 MB(单张 0.95–1.56 MB),安装包约 15 MB。

### 工具准备(一次性)
- `pip3 install pillow numpy`
- `brew install libultrahdr`(读取原图参数用,2.x)
- 自编译写入工具(libultrahdr 1.4.0,打开双写法):
  ```bash
  mkdir -p ~/Library/uhdr-build && cd ~/Library/uhdr-build
  git clone --depth 1 --branch v1.4.0 https://github.com/google/libultrahdr.git
  cd libultrahdr && mkdir -p build && cd build
  cmake .. -DUHDR_WRITE_XMP=1 -DUHDR_WRITE_ISO=1 -DCMAKE_BUILD_TYPE=Release -DUHDR_BUILD_TESTS=0 -DUHDR_BUILD_DEPS=0
  make -j8 ultrahdr_app
  ```
  (需要 `brew install cmake jpeg-turbo`。)

### 验证记录(2026-09-28)
- Android 14 模拟器上 `BitmapFactory.decodeFile(...).hasGainmap()`:原图(只有 ISO)= false;转换后(XMP + ISO)= true,`ratioMax` = 4.935,增益图宽 3840。
- 显示 HDR 还需要:应用窗口 `COLOR_MODE_HDR`(主界面与系统屏保已开)、绘制链不丢增益图、电视界面层支持 HDR 输出。~~首页壁纸处理链目前会丢~~ → 2026-09-28 R122–R125 补齐(裁定全文见 `docs/superpowers/specs/2026-09-20-gtv-line-design.md` 的「HDR 壁纸 / 屏保显示链」一节),结论如下。

### 验证记录(2026-09-28 · 显示链,R122–R125)

| 显示路径 | 增益图是否保留 | 证据(`unitedu-tv` 模拟器,Android 14) |
|---|---|---|
| 首页壁纸 · 原图(模糊 / 亮度都为 0) | 保留 | 日志 `壁纸原图 01-夏日数码门.jpg 目标 1920x1080 → 1920x1080 ARGB_8888 增益图 1920x1080 ALPHA_8 ratioMax 4.94`;R125 起底图 ARGB_8888(原 F16),SDR 画面与改前 0 差 |
| 首页壁纸 · 模糊 / 亮度处理后 | 保留(R123) | 日志 `壁纸处理 … → … 增益图 1920x1080 ALPHA_8`;缓存文件含 `hdrgm` XMP + MPF 第二帧;4:3 测试图的增益图裁剪 / 模糊与期望平均差 < 1 级 |
| 首页壁纸 · 缓存命中 | 保留 | 日志 `壁纸缓存命中 … → … 增益图 …` |
| 首页壁纸 · R110 缓存图层 | 保留;比例变了靠 R124 重建 | HWUI 探针:比例 4 时图层画与直接画峰值同为 3.86 × SDR 白;比例 1 建的图层切到 4 后 0.95(丢),重建后 3.86 |
| 设置页预览(外观 / 布局) | 同首页 | 预览就是首页那一层缩进预览框,同一张位图、同一组图层 |
| 自定义屏保 / 系统屏保 | 保留 | 日志 `屏保照片 01-云海天光.jpg 目标 1920x1080 → 1920x1080 ARGB_8888 增益图 1920x1080 ALPHA_8`;推拉摇移 / 淡入是每帧的 `graphicsLayer` 变换与临时 saveLayer(每帧按当前比例重画),不是缓存图层;系统屏保走同一个 `MotionSlideshow` |
| 屏保图库全屏预览 | 保留 | 同屏保日志(R122 起与屏保同一个解码) |

- **模拟器上读不到「HDR 比例」**:这台 AVD 的显示器 `hdrSdrRatio not_available`、只支持色彩模式 0,系统把 HDR 窗口降成默认 sRGB(SurfaceFlinger 图层 `dataspace=V0_SRGB`),增益图永远不被用上;而且 Android 14 的 SurfaceFlinger 文字 dump 本来就不打印 desired / current HDR 比例,desired 比例也与画面内容无关(HDR 模式恒 5)。所以上表用「位图带不带增益图」的日志 + HWUI 探针 + 源码路径论证。
- **上电视时读这几项**(装包后被动读取,不按键):
  ```bash
  # 1. 电视界面层给不给 HDR 余量:not_available = 系统不报比例 → 应用的 HDR 窗口会被降级,任何应用内的增益图都不会亮
  adb -s <电视序列号> shell dumpsys display | grep -o "hdrSdrRatio [^,]*" | sort -u
  # 2. UnitedU 窗口实际的色彩模式:Display P3 + 扩展范围(RANGE_EXTENDED)= HDR 模式生效;V0_SRGB = 默认;Display P3 = 广色域
  adb -s <电视序列号> shell dumpsys SurfaceFlinger | grep -A 12 "^\* Layer.*com.uniteduone.launcher/com.uniteduone.launcher.MainActivity#" | grep -o "dataspace=[^)]*)"
  # 3. 当前实际用上的比例:whitePointNits ÷ SDR 白点;dimmingRatio < 1 表示系统在给 HDR 让余量
  adb -s <电视序列号> shell dumpsys SurfaceFlinger | grep -A 16 "Output Layer.*com.uniteduone.launcher" | grep -o "dataspace=[^)]*) whitePointNits=[^ ]* dimmingRatio=[^ ]*"
  # 4. 位图带不带增益图(换一次壁纸 / 等一张屏保照片后)
  adb -s <电视序列号> logcat -d -s UnitedU | grep -E "壁纸原图|壁纸处理|壁纸缓存命中|屏保照片"
  ```
  判读:1 是数字且 > 1、2 是扩展范围 → HDR 显示链全通,肉眼看高光(灯、月亮)应比界面白更亮;1 是 `not_available` → 电视界面层不支持 HDR,这台电视上壁纸 / 屏保只能显示 SDR(不是我们的链路问题)。

- **A95L 真机(2026-09-28 装 `4c1c9c6` 后读)**:`dumpsys display` → `hdrSdrRatio not_available`;面板 `supportedHdrTypes=[1, 2, 3]`(杜比视界 / HDR10 / HLG,仅视频通道)。结论:A95L 的应用界面层不输出 HDR,Android 14 把 HDR 窗口降级,内置 HDR 图在这台上显示为 SDR——固件限制;显示链本身已保留增益图(见 R122–R125),在会报告 HDR/SDR 比例的电视上生效。

- **A95L 视频通道实验(2026-09-28 22:40)**:同一张 HDR 照片转成 HDR10 视频(PQ / BT.2020,SDR 白 203 nit,峰值约 1000 nit,x265 10 bit)用索尼系统播放器播放,电视报 `SignalType is updated to HDR10`、画质引擎收到 `Hdr 1` 的 3840×2160 帧;SDR 对照版为 `Hdr 0`。即:这台电视的界面层不给 HDR,但**视频通道能以 4K HDR10 显示静止画面**——要在 A95L 这类电视上真的亮起来,屏保需要改走视频播放(SurfaceView / 视频层)而不是位图 + 增益图。转换步骤:`ultrahdr_app -m 1 -j <图> -o 0 -O 4 -z hdr.raw`(线性半浮点 RGBA)→ numpy 乘 203 nit、BT.709→BT.2020 矩阵、PQ 编码成 rgb48 → `ffmpeg … -c:v libx265 -x265-params hdr10=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:master-display=…:max-cll=1000,200`。

### 停止:屏保 / 壁纸改走视频层(2026-09-28 Gordon「如果确实有烧屏风险,那就别研究 HDR 了」)
烧屏风险主要在壁纸(同一画面挂几小时);屏保画面一直在动、定时换图,风险小。下面四题留作将来若重启的起点。
Gordon 肉眼确认 HDR10 视频高光明显更亮,但 SDR↔HDR 切换时屏幕明显黑一下。定了「先做屏保、壁纸以后再说」,随后摸底也暂缓。重启时先用一个独立探针小程序(SurfaceView + MediaPlayer)在 A95L 上答这四题,判据是 logcat 的 `SignalType is updated to …` / `flipToPq … Hdr 0|1`:
1. 两段 HDR 视频之间切换(释放重建 / 双 SurfaceView 预加载 / `setNextMediaPlayer`)信号会不会掉回 SDR;单段 `setLooping` 的循环点会不会掉。
2. 视频层上 SurfaceView 的 alpha 淡化、缩放动画能不能做、好不好看(要人看)。
3. 内置 HDR 与用户 SDR 照片混放时怎么避免每次黑一下(分组放 / 暂停的 HDR 视频上叠界面层照片)。
4. 4 张内置图连同推拉摇移、淡化预先做成一整段 4K HDR10 循环视频的体积。
另:现有屏保视频走 TextureView(画进界面层),用户上传的 HDR 视频在 A95L 屏保里也只是 SDR(推断,未实测);改走视频层会一并解决。壁纸做成视频的额外代价:每次回首页都切 HDR、模糊 / 亮度 / 设置预览不可用、QD-OLED 静态高光的烧屏风险。
