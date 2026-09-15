#!/usr/bin/env python3
"""生成 UnitedU 内置壁纸(6 张,1920×1080,JPEG q85)。

手法与 M1 金雾底相同:低频噪声 → 放大 → 高斯模糊 → 归一化 → 压暗曲线 → 乘染色。
只依赖 Pillow;参数、种子全在下面,改了重跑即可复现。
用法:python3 scripts/gen-wallpapers.py [输出目录]   默认 app/src/main/assets/wallpapers
"""
import pathlib
import random
import sys

from PIL import Image, ImageFilter, ImageOps

W, H = 1920, 1080
NOISE_W, NOISE_H = 8, 5      # 噪声格数:越少,云团越大
BLUR_RADIUS = 78             # 与 ffmpeg 版 sigma 78 同量级
# 压暗曲线(x 输入亮度, y 输出亮度),分段线性:压掉暗部,峰值 0.62
CURVE = [(0.0, 0.0), (0.55, 0.0), (0.80, 0.22), (1.0, 0.62)]

# (文件名, 染色 RGB, 噪声种子)。00 中性灰是默认底(M2 决策:默认背景固定中性暗);
# 其余对应主题预设(ThemePresets.kt 的 accent),石墨 ↔ 中性。
WALLPAPERS = [
    ("00-neutral",   (0x8C, 0x8C, 0x8C), 11),
    ("01-gold",      (0xC0, 0xA7, 0x3A), 12),
    ("02-champagne", (0xD9, 0xC7, 0xA0), 13),
    ("03-blue",      (0x6E, 0x8F, 0xB0), 14),
    ("04-purple",    (0x92, 0x80, 0xAA), 15),
    ("05-green",     (0x7F, 0xA0, 0x7A), 16),
]


def curve_lut():
    lut = []
    for i in range(256):
        x = i / 255
        for (x0, y0), (x1, y1) in zip(CURVE, CURVE[1:]):
            if x <= x1:
                t = (x - x0) / (x1 - x0) if x1 > x0 else 0.0
                lut.append(round(255 * (y0 + t * (y1 - y0))))
                break
    return lut


def make(name, rgb, seed, out_dir):
    rnd = random.Random(seed)
    noise = Image.new("L", (NOISE_W, NOISE_H))
    noise.putdata([rnd.randrange(256) for _ in range(NOISE_W * NOISE_H)])
    img = noise.resize((W, H), Image.BICUBIC).filter(ImageFilter.GaussianBlur(BLUR_RADIUS))
    img = ImageOps.autocontrast(img)      # 归一化到 0..255
    img = img.point(curve_lut())          # 压暗曲线
    channels = [img.point(lambda v, c=c: round(v * c / 255)) for c in rgb]   # 乘染色
    out = out_dir / f"{name}.jpg"
    Image.merge("RGB", channels).save(out, "JPEG", quality=85, optimize=True)
    print(f"wrote {out} ({out.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    out_dir = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/wallpapers")
    out_dir.mkdir(parents=True, exist_ok=True)
    for name, rgb, seed in WALLPAPERS:
        make(name, rgb, seed, out_dir)
