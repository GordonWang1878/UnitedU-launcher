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
- 显示 HDR 还需要:应用窗口 `COLOR_MODE_HDR`(主界面与系统屏保已开)、绘制链不丢增益图(屏保 RGBA_F16 解码保留;**首页壁纸处理链目前会丢**,见 WORKLOG 2026-09-28)、电视界面层支持 HDR 输出(A95L 待 Gordon 真机看)。
