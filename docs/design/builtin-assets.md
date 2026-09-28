# 内置图放法(壁纸 / 屏保 / 卡片装饰图)

Gordon 自己放文件,程序按目录自动识别,**不用改代码**。放好后告诉 Claude,Claude 构建、验证、装电视。

## 放在哪

| 类型 | 文件夹 | 张数 |
|---|---|---|
| 壁纸 | `app/src/main/assets/builtin/wallpapers/` | 4 |
| 屏保 | `app/src/main/assets/builtin/screensavers/` | 4–6 |
| 卡片装饰图 | `app/src/main/assets/builtin/cards/` | 若干 |

(完整路径:`/Users/gordonwang/GitHub/UnitedU-launcher/app/src/main/assets/builtin/…`;每个文件夹里的 `.gitkeep` 别删。)

## 怎么命名

`两位序号-短名.扩展名`,例如:

```
wallpapers/01-dusk-city.jpg      ← 排第一 = 默认壁纸
wallpapers/02-forest-mist.jpg
screensavers/01-aurora.jpg
cards/01-gradient-blue.png
```

- **序号**决定显示顺序(01、02、03……,程序按文件名升序排);壁纸里 01 那张就是默认壁纸。网格里显示的名字去掉序号(`01-dusk-city` 显示成 `dusk-city`)。
- **短名**只用小写英文字母、数字、连字符 `-`,不要空格、中文、大写。不合规的文件**照样会显示**,只是日志里记一条警告;发布前请改成合规的。
- **扩展名**:jpg / jpeg / png / webp(大小写都行)。别的文件(`.gitkeep`、README、视频、子文件夹)一律忽略。
- **同一个名字只能有一张**:`01-a.jpg` 和 `01-a.png` 同时在,只认排在前面的 `01-a.jpg`,另一张忽略(日志警告)。
- **发布后不要改文件名**:文件名(去掉扩展名)就是这张图的身份,用户选中了哪张壁纸(settings.json 里记成 `builtin:01-dusk-city`)、屏保关掉了哪张(`excludedBuiltinScreensavers`)都记的是它;改名 = 用户的选择失效(壁纸回到默认那张、关掉的屏保图重新进轮播)。发布前随便改。卡片装饰图例外:用户选用时复制的是图的内容,改名不影响已经换上的卡片。

## 尺寸与体积建议

- 壁纸、屏保:横图 16:9,1920×1080 起;4K(3840×2160)也可以。jpg 质量 85 左右,单张尽量 ≤ 2 MB(安装包现在约 5.5 MB,10 张大图会让它翻几倍)。
- 卡片装饰图:16:9,1280×720 足够;不是 16:9 也能用,两侧会用边缘色补齐(R107)。
- 不支持视频(视频放进安装包太大);用户自己的屏保视频照常从手机上传。

## 用户那边看到什么

- 三个选图页(换壁纸、屏保图库、换卡片图)上块「内置」、下块「我的」;某个文件夹是空的,那一页就没有上块(和现在一样)。
- 内置图删不掉、不占用户图库,手机上传网页里也看不到它们。
- 屏保:内置图默认全部进轮播,用户在屏保图库里长按(或按菜单键)某张可以「不参与轮播」/「加入轮播」,关掉的那张变暗、角上标「已关」。
- 卡片装饰图任何应用都能选,照原图显示;不是 16:9 的按卡片同样的规则补边(R88 / R107)。

## 来源说明

都是 AI 生成的:放好后 Claude 在 `NOTICE` 里补一行「内置壁纸 / 屏保 / 卡片装饰图为 AI 生成,随本项目以同一许可发布」。若某张图用了别的来源,告诉 Claude 出处。

## 给 Claude:用测试图验证(不进仓库)

正式 commit 里三个文件夹只有 `.gitkeep`(和 Gordon 放的正式图)。模拟器上要看效果时,测试图放在仓库外的私有目录(结构同 `assets/`,即 `<目录>/builtin/<分类>/…`),用一个 gradle init 脚本把它并进 release 的 assets,**不改 build.gradle、不往仓库里放文件**:

```groovy
// test-assets.init.gradle(放在仓库外)
allprojects {
    plugins.withId('com.android.application') {
        android.sourceSets.release.assets.srcDir('<私有目录>')
    }
}
```

```bash
source scripts/env.sh && gradle --no-daemon --init-script <test-assets.init.gradle 的路径> assembleRelease
unzip -l app/build/outputs/apk/release/app-release.apk | grep assets/builtin/   # 确认测试图进包了
```

测完不带 `--init-script` 再构建一次,才是正式包。2026-09-28 R115 验证用的就是这一套(TEST 字样的 PIL 图:壁纸 4、屏保 5、卡片 3)。
