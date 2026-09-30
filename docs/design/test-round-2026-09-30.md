# 测试轮 2026-09-30:发现的 bug 与修复

用例表见 [`test-cases-2026-09-30.md`](test-cases-2026-09-30.md)。环境:模拟器 `unitedu-tv-2`(Android 14 TV,1920×1080 / 320 dpi,`-gpu host`),release 包;端到端脚本在 `scripts/e2e/`,JVM 单测在 `app/src/test/`。

本轮两条分支(都从 main `739e35d` 切出;主线已合到本分支 `03ac89e` 与单测分支全部):
- **本分支** `worktree-agent-a331e5c671c796028`:测试脚本、用例表、本文件、B-03 / B-05 / B-06 修复。
- **单测分支** `worktree-agent-a4949bfd77ec91a88`(本轮分出去写 JVM 边界单测的子任务):9 个 `*BoundaryTest.kt`(146 个用例)+ B-01 / B-02 / B-04 三个修复。本分支自己 cherry-pick 被权限规则拦下,由协调方合进主线;合并时 `fitFileName`(B-05)改成复用单测分支的 `truncateStem` 与 `MAX_UPLOAD_NAME_BYTES`。

汇总:6 个 bug 全部修了(中 4 个、低 2 个),每个都有 JVM 单测防线,其中 B-03 / B-06 另有端到端断言、修前修后都在模拟器上跑过。

## B-01 超长中文文件名上传失败(中)

- **现象**:手机传一张文件名 ≥ 84 个汉字的图,网页回执「拒收:write」,电视上什么都没有。
- **复现**:扫码页开着,`curl -F "files=@x.jpg;filename=<120 个「长」>.jpg"` → `{"saved":[],"rejected":[{"reason":"write"}]}`(`j_upload_edge.py`「upload-edge-names」)。
- **根因**:`sanitizeUploadName` 只按 100 个 **UTF-16 字符**截主名;96 个汉字 + `.jpg` = 292 **字节**,超过 ext4 / f2fs 单个文件名 255 字节的上限,rename 与复制回落都建不出文件。
- **修法**:加 `MAX_UPLOAD_NAME_BYTES = 255`,超过就按码点截主名、保扩展名(单测分支 `422eae6`)。取 255 而不是更小:删除 / 缩略图接口也拿这个函数清洗**已在图库里**的名字再去找文件,能落盘的名字必须原样通过。
- **遗留**:接近 255 字节的名字撞名时加 `-1` 会超限——见 B-05(本分支已修)。
- **防线**:`UploadPureBoundaryTest.longChineseNameFitsTheFilesystemNameLimit` / `namesThatAlreadyFitOnDiskPassUnchanged`;端到端 `j_upload_edge.py` 同一条断言(**合并单测分支后才会通过**,本分支单独跑是 FAIL,属预期)。

## B-02 超长 emoji 文件名被劈开,留下删不掉的「僵尸」文件(中)

- **现象**:`a` + 60 个 😀 + `.jpg` 上传成功,但落盘名末尾是半个 emoji;网页列表里显示成 `…?.jpg`,缩略图 404、删除 404——手机上删不掉,电视上也打不开。
- **复现**:同上,`j_upload_edge.py`「超长 emoji 名」;`adb shell ls … | xxd` 能看到落盘名里的 `ed a0 bd`(落单的高代理按 modified UTF-8 编码)。
- **根因**:`sanitizeUploadName` 用 `take(n)` 按 UTF-16 单元截,截点落在代理对中间。
- **修法**:按码点截(`truncateStem`),至少留一个完整码点(单测分支 `daabfa9`)。
- **防线**:`UploadPureBoundaryTest.truncationNeverSplitsASurrogatePair`;端到端同上(合并单测分支后通过)。

## B-03 只差大小写的上传会覆盖图库里已有的图(中,数据丢失)

- **现象**:图库里已有 `Beach.jpg`,再传 `BEACH.JPG`:网页回执「已保存 BEACH.JPG」,图库里仍只有一张——旧图被新图盖掉,没有任何提示;若它正被用作壁纸 / 卡片图,画面悄悄变了。
- **复现**:`j_upload_edge.py`「upload-edge-case-collision」:两次上传后 `ls` 只剩 1 张,`md5sum Beach.jpg` 变了。
- **根因**:图库在 `/sdcard/Android/data/…`,Android 11+ 的外置存储是 **casefold**(不区分大小写)的——模拟器实测 `ls /sdcard/Download/x/PHOTO.JPG` 找得到 `photo.jpg`。`uniqueName` 按字面比较,认为 `BEACH.JPG` 不重名,`saveIntoLibrary` 的 `renameTo` 在不区分大小写的文件系统上直接替换了 `Beach.jpg`。
- **修法**:`uniqueName` 按 `nameFoldKey`(NFC 规范化 + 大小写折叠)比较,只差大小写也加 `-N`(本分支 `6e3223a`)。多判成重名只会多一个后缀,少判才会丢图。
- **防线**:`UploadCaseFoldTest`(5 个 JVM 用例,含一个模拟 casefold rename 的 `saveIntoLibrary` 用例);端到端 `j_upload_edge.py`:两张都在、旧图 md5 不变(修后模拟器实测通过)。

## B-04 内置图文件名里连着两个点时认不回来(低)

- **现象**:内置图目录里若有 `05-极光..jpg` 这样手工改名打错的文件,网格里有这一格,但解不出图、也选不中。
- **根因**:`builtinCatalog` 照收这种名字,`builtinAssetPathOf` 却按**子串**拒掉一切含 `..` 的伪路径。
- **修法**:按路径段判,只有整段是 `..` 才拒,穿越防御不变(单测分支 `0dd7567`)。
- **防线**:`BuiltinCatalogBoundaryTest.everyNameTheCatalogAcceptsResolvesBackThroughItsPseudoPath` / `traversalSegmentsAreStillRejected`。目前仓库里的内置图没有这种名字,属预防。

## B-05 接近上限的文件名撞名加 `-1` 后超过 255 字节(低)

- **现象**:B-01 修好之后,250 个 ASCII 字符 / 83 个汉字这类接近 255 字节的名字能落盘;同名再传一次,`uniqueName` 加 `-1` 变成 256+ 字节,又以「write」被拒。
- **根因**:`uniqueName` 只拼后缀,不管字节长度。只在合并单测分支(B-01 放宽上限)之后才碰得到;本分支单独时名字被 100 字符上限挡着,汉字名本身就存不下(B-01)。
- **修法**:`fitFileName`:超限时从主名尾部按码点截短(不劈开 emoji),后缀照加、照样往下探测空位(本分支 `94c395f`)。
- **防线**:`UploadUniqueNameLengthTest`(5 个 JVM 用例)。

## B-06 编辑页卡片菜单开着时那张卡的应用被卸载,菜单悄悄换成下一张卡(中,会移错应用)

- **现象**:编辑页里对某张卡按确定打开卡片菜单(移动 / 换卡片图 / 移出),这时这个应用在后台被卸载:菜单不关,左半的名字与图换成同一行**下一张**卡;再按「移出」,移掉的是下一张卡的应用(用户根本没选它)。
- **复现**:`j_pkg.py`「pkg-edit-card-menu」:编辑页第一张卡(app00)打开卡片菜单 → `pm uninstall app00` → 菜单仍开着、标题变成 app01 → 按「移出」→ layout.json 第一行少了 app01。REPRO_B06
- **根因**:菜单状态 `acting` 只记 (行, 列) 坐标,包名每次组合从 `viewRows[行][列]` 现取。卸载让同一行后面的卡左移一格,同一个坐标就指向了下一张卡——铁律 5「目标要按身份认」在这里漏了;首页的长按菜单(`CardRef` 带包名,`startMove` 按包名找)没有这个问题。
- **修法**:`acting` 改成 `EditActing(row, col, pkg)`,此刻的列号按包名在看得见的那份里现查(`editActingCol`,`LayoutOps.kt`);包不在那一行了 → 收掉菜单,看门狗随 `overlayOpen` 翻回 false 接回焦点。返回键兜底的落点也按包名算(本分支 `03ac89e`)。
- **防线**:`EditActingTest`(3 个 JVM 用例);端到端 `j_pkg.py`「pkg-edit-card-menu」:菜单随那张卡收掉、不误删别的卡(修后模拟器 `j_pkg` 79/79)。

## 没修的

- **`standbyPlan(1, Long.MAX_VALUE)` 溢出成负数**:设置读盘时两个时长都被夹到固定档位,MainActivity 只用差值,实际走不到;单测分支记成 `@Ignore` 用例(`StandbyScheduleBoundaryTest`),要不要改成饱和加法留给维护者。
- **`sanitizeUploadName` 不去 C1 控制字符(U+0080–009F)**:KDoc 说「去控制字符」,实际只去 C0 与 DEL;这些字符在 ext4 上是合法文件名,不影响落盘与删除,暂不改。

## 测试中看到、但不算 bug 的

- 首页顶栏「应用」胶囊的 content-desc 就是「All Apps」,焦点在它上面时右边的名字小胶囊(R133)也以 text 画出「All Apps」:旧脚本 `j_apps_inputs.py` 用 `has("All Apps")` 判「应用页开着」恒为真(断言失效)。本轮改成认页头下那行提示(`apps_page_hint`)。`j_overlays` 第 1 次跑里「所有应用页」几条「收干净 / 关了」失败都是这个判据误报(落点断言通过),用例表里逐条注明了。
- 同理 `has()` 是子串匹配:设置第一层的说明文字里就有「Language」「Wallpaper」,判某一页开着要整段相等(`has_exact`)。
- 编辑页第 1 行 8 张卡会横向滚动,焦点框停在屏幕同一处,「一直往右直到焦点框不动」会在行中间就停——找行尾「＋」改用第 2 行。
- 上传脚本的 `adb forward --remove-all` 会删掉**所有**设备的转发、固定本机端口 18090 也会被别的模拟器占:并行跑时互相干扰(协调方提醒,主线已改成每台设备一个端口)。本轮 B-01 / B-02 / B-03 的修前失败出现在 16:31 之前那一次(干扰窗口 16:36–16:58 之外),并有设备侧证据(`ls | xxd`、`md5sum`);17:23 以后本机改用自己的端口、只删自己的转发重跑,结论不变。
- monkey 会把语言切到繁体、改卡片大小 / 主题色 / 壁纸模糊,这是它按到了设置;每个种子前推回基线,不是 bug。
- 扫码页关掉后的落点是「本次打开以来传的第一张还在的图」(`landingCell`),不是最后一张;一开始按「最后一张」写的断言是错的。
- 语法合法、字段类型怪的 `layout.json`(apps 里混数字 / null / 对象)照读不改写,盘上保持原样;不是损坏,不改名 `.bad`。
