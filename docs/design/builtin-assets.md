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

- **序号**决定显示顺序(01、02、03……);壁纸里 01 那张就是默认壁纸。
- **短名**只用小写英文字母、数字、连字符 `-`,不要空格、中文、大写。
- **扩展名**:jpg / jpeg / png / webp。
- **发布后不要改文件名**:文件名(去掉扩展名)就是这张图的身份,用户选中了哪张、屏保关掉了哪张都记的是它;改名 = 用户的选择失效。发布前随便改。

## 尺寸与体积建议

- 壁纸、屏保:横图 16:9,1920×1080 起;4K(3840×2160)也可以。jpg 质量 85 左右,单张尽量 ≤ 2 MB(安装包现在约 5.5 MB,10 张大图会让它翻几倍)。
- 卡片装饰图:16:9,1280×720 足够;不是 16:9 也能用,两侧会用边缘色补齐(R107)。
- 不支持视频(视频放进安装包太大);用户自己的屏保视频照常从手机上传。

## 来源说明

都是 AI 生成的:放好后 Claude 在 `NOTICE` 里补一行「内置壁纸 / 屏保 / 卡片装饰图为 AI 生成,随本项目以同一许可发布」。若某张图用了别的来源,告诉 Claude 出处。
