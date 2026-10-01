# Google TV 原生截图索引(launcherx 1.0.976298245)

全部来自 AVD `unitedu-gtv`,1920×1080 @ 320 dpi,已登 Google 账号。测量结论见 `docs/research/2026-09-20-google-tv-launcherx-measurements.md`。
`90-` 开头的是**更新前的旧版**(1.0.595789376,上一代设计),留作版本对照;`91-` 是无账号时的门,留作记录。

| 文件 | 看什么 |
|---|---|
| `01-home-default.jpg` | **首页默认态**。顶栏药丸组、右侧小字时钟 + `Google TV` 字标、hero、`Top picks for you` 行、`Your apps` 圆形图标行 |
| `02-topbar-home-focused.jpg` | 顶栏 `Home` 拿到焦点(注意「选中」是浅色实填、「聚焦未选中」是中灰填,两套画法) |
| `03-topbar-apps-focused.jpg` | 顶栏 `Apps` 拿到焦点 |
| `04-topbar-screensaver-focused.jpg` | 第二组药丸里的屏保图标聚焦 |
| `05-topbar-settings-focused.jpg` | 快捷设置(齿轮)图标聚焦,带红色未读角标 |
| `06-topbar-avatar-focused.png` | 最左头像聚焦 |
| `07-hero-focused.jpg` | 焦点落在 hero 上 |
| `08-content-card-focused.jpg` | **最关键的一张**:焦点落到内容卡时,该片全幅剧照铺满整屏 + 左上标题/片源/评分元数据,顶栏折叠成一个向上箭头 |
| `09-apps-row-focused.jpg` | 焦点落到 `Your apps` 圆形图标(放大 1.105 倍 + 描边 + 柔光) |
| `10-row-anchor-step0.jpg` / `11-…step3` / `12-…step6` | **行锚定实证**:连按右键,焦点卡永远钉在左基准线 58 dp,整行在它下面平移 |
| `13-apps-page.jpg` | Apps 页:`Your apps` 圆形图标 + 搜索框 + `App categories` 芯片 + `Apps from my other devices` |
| `14-apps-page-icon-focused.jpg` | Apps 页里图标聚焦态 |
| `15-quick-settings-panel.jpg` | 快捷设置面板:右侧浮出 sheet、2 列磁贴、聚焦项浅蓝实填、背景重度压暗 |
| `16-app-longpress-menu.png` | 应用长按菜单:全屏黑底、左 banner 右整宽药丸、聚焦项浅蓝实填 |
| `19-vertical-transition-frames.jpg` | Google 纵向换行的逐帧接触表(#40→#54):整页平移 + hero 收起 + backdrop 交叉淡出**同时**发生,到顶那行的标题顶进 hero 大字位。配合研究文档 §10d |
| `20-app-tile-focused.jpg` | 聚焦态 app 磁贴。看点是描边**外面**那层柔光(峰值 +44/255,铺到半径 73 dp)——我们原先只画了 2 dp 描边 |
| `21-app-tile-unfocused.jpg` | 同一颗磁贴的未聚焦态,与上一张逐点相减即得柔光剖面 |
| `90-old-version-home-1.0.595789376.jpg` | 更新前的旧版首页(`Home / Apps / Library` 三个文字 tab、方形应用卡)——**不是要复刻的那版** |
| `91-account-gate.png` | 无 Google 账号时 launcherx 只显示这道门,真首页在门后 |
| `24-new-tray-icon-in-google-tv-your-apps.jpg` | 2026-10-01 新图标(托盘,R156)在 Google TV「Your apps」里聚焦的样子;左边两个是旧图标(彩虹 U、更早的「United」光效字)。只裁了应用行,不含账号头像 |

## UnitedU 现状截图在哪

不在本目录,在上一层 `docs/screenshots/`:`m8-after-home.png`(M8 美化后的首页)、`m8-after-row2.png`、`m8-after-settings-preview.png`、`m8-after-idle.png`,以及 09-19 的并排对比 `gtv-compare-side-by-side-*.png`(**那批对标的是旧版 Android TV 桌面,不是本目录这版 Google TV**)。
