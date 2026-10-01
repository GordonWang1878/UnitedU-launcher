#!/usr/bin/env python3
"""01F「字标合一」banner:United 换 8 种字体(+ 现用的 Futura Bold 作对照)。

每种字体都从字体文件直接取字形转路径;碗(最后一个 U)的线宽取该字体 U 的竖画实测粗细,
碗宽跟着该字体 U 的宽度走,圆头字体的碗口也做成圆头。
用法:python3 gen.py → svg/ + fonts.png + fonts-tv.png
"""
import os
import subprocess
import tempfile

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
SVG_DIR = os.path.join(HERE, "svg")
CJK = "PingFang SC, Hiragino Sans GB, sans-serif"
GSF = os.path.join(HERE, "../../../../../app/src/main/res/font/google_sans_flex.ttf")
SUP = "/System/Library/Fonts/Supplemental"

CREAM, INK, RED, YEL, BLU = "#EFE8DA", "#1B1B1B", "#E0402C", "#F2B705", "#2156A8"


class Face:
    def __init__(self, path, index=0, variations=None):
        f = TTFont(path, fontNumber=index)
        if variations:
            f = instancer.instantiateVariableFont(f, variations)
            tmp = tempfile.NamedTemporaryFile(suffix=".ttf", delete=False)
            f.save(tmp.name)
            path, index = tmp.name, 0
        self.f, self.path, self.index = f, path, index
        self.upm = f["head"].unitsPerEm
        self.cmap = f.getBestCmap()
        self.gs = f.getGlyphSet()
        self.kern = f["kern"].kernTables[0].kernTable if "kern" in f else {}
        b = self.bounds("H")
        self.cap = b[3] / self.upm
        u = self.bounds("U")
        self.u_width = (u[2] - u[0]) / self.upm
        self.stem = self._stem()

    def bounds(self, ch):
        bp = BoundsPen(self.gs)
        self.gs[self.cmap[ord(ch)]].draw(bp)
        return bp.bounds

    def _stem(self):
        """把 U 渲染成大图,在大写高一半处量左竖画的墨宽。"""
        size = 1000
        font = ImageFont.truetype(self.path, size=size, index=self.index)
        img = Image.new("L", (size * 2, size * 2), 0)
        ImageDraw.Draw(img).text((100, 1500), "U", font=font, fill=255, anchor="ls")
        y = int(1500 - self.cap * size / 2)
        row = [img.getpixel((x, y)) > 127 for x in range(size * 2)]
        start = row.index(True)
        end = start
        while row[end]:
            end += 1
        return (end - start) / size

    def width(self, s, size):
        w, prev = 0.0, None
        for ch in s:
            g = self.cmap[ord(ch)]
            if prev is not None:
                w += self.kern.get((prev, g), 0)
            w += self.f["hmtx"][g][0]
            prev = g
        return w * size / self.upm

    def paths(self, s, x, baseline, size, fill):
        sc, pen_x, prev, out = size / self.upm, x, None, []
        for ch in s:
            g = self.cmap[ord(ch)]
            if prev is not None:
                pen_x += self.kern.get((prev, g), 0) * sc
            sp = SVGPathPen(self.gs)
            self.gs[g].draw(TransformPen(sp, (sc, 0, 0, -sc, pen_x, baseline)))
            out.append(sp.getCommands())
            pen_x += self.f["hmtx"][g][0] * sc
            prev = g
        return f'<path d="{" ".join(out)}" fill="{fill}"/>'


# (编号, 名字, 一句话, 授权, 字体, 碗口是否圆头)
FONTS = [
    ("0", "Futura Bold", "对照:上一轮的 F", "系统字体", Face(f"{SUP}/Futura.ttc", 2), False),
    ("1", "Google Sans Flex", "应用界面现在用的就是它", "开源 OFL", Face(GSF, variations={"wght": 700, "opsz": 144}), False),
    ("2", "Google Sans Flex 圆头", "同一款字体把圆角轴开满", "开源 OFL", Face(GSF, variations={"wght": 700, "opsz": 144, "ROND": 100}), True),
    ("3", "Roboto Bold", "安卓自己的字体,最中性", "开源 Apache", Face("/Library/Fonts/Roboto-Bold.ttf"), False),
    ("4", "Avenir Next Heavy", "几何但更柔和,笔画更重", "系统字体", Face("/System/Library/Fonts/Avenir Next.ttc", 8), False),
    ("5", "Gill Sans Bold", "英伦人文体,亲切", "系统字体", Face(f"{SUP}/GillSans.ttc", 1), False),
    ("6", "DIN Alternate Bold", "路牌字体,本来就是给远看设计的", "系统字体", Face(f"{SUP}/DIN Alternate Bold.ttf"), False),
    ("7", "Rockwell Bold", "粗衬线(板状衬线),像老海报", "系统字体", Face(f"{SUP}/Rockwell.ttc", 2), False),
    ("8", "Futura 窄体特粗", "同族的窄体,字能做得最高", "系统字体", Face(f"{SUP}/Futura.ttc", 4), False),
]


def banner(face, round_cap, avail_w=260, avail_h=116):
    word = "United"
    # 先按 1 号字算比例,再按可用宽高取较小的缩放
    def dims(size):
        cap = face.cap * size
        stem = face.stem * size
        # 碗宽跟着该字体的 U 走,但碗里至少留 0.33 个大写高的空间,粗字体的形状才不会缩成点
        bw = max(face.u_width * size * 1.12, cap * 0.98, 2 * (stem + 0.33 * cap))
        inner = bw / 2 - stem
        sq = inner * 0.98
        gap = size * 0.07
        w = face.width(word, size) + gap + bw
        h = cap + sq * 0.55
        return cap, stem, bw, inner, sq, gap, w, h

    *_, w1, h1 = dims(1)
    size = min(avail_w / w1, avail_h / h1)
    cap, stem, bw, inner, sq, gap, w, h = dims(size)
    left = (320 - w) / 2
    baseline = (180 - h) / 2 + h
    top = baseline - cap
    cx = left + face.width(word, size) + gap + bw / 2
    r = (bw - stem) / 2
    arc_cy = baseline - bw / 2
    cr = inner * 0.8
    leg_top = top + (stem / 2 if round_cap else 0)
    cap_attr = "round" if round_cap else "butt"
    body = (
        f'<rect width="320" height="180" fill="{CREAM}"/>'
        + face.paths(word, left, baseline, size, INK)
        + f'<circle cx="{cx:.2f}" cy="{baseline - stem - cr - 0.5:.2f}" r="{cr:.2f}" fill="{RED}"/>'
        + f'<rect x="{cx - inner + 0.6:.2f}" y="{top - sq * 0.55:.2f}" width="{sq:.2f}" height="{sq:.2f}" fill="{YEL}"/>'
        + f'<path d="M{cx + 0.9:.2f} {top + sq * 0.45:.2f} L{cx + 0.9 + sq:.2f} {top + sq * 0.45:.2f} '
        f'L{cx + 0.9 + sq / 2:.2f} {top - sq * 0.55:.2f} Z" fill="{BLU}"/>'
        + f'<path d="M{cx - r:.2f} {leg_top:.2f} V{arc_cy:.2f} A{r:.2f} {r:.2f} 0 0 0 {cx + r:.2f} {arc_cy:.2f} V{leg_top:.2f}" '
        f'fill="none" stroke="{INK}" stroke-width="{stem:.2f}" stroke-linecap="{cap_attr}"/>'
    )
    return body, size


def banner_at(p, body, x, y, w, rx=10):
    return (
        f'<svg x="{x}" y="{y}" width="{w}" height="{w * 9 / 16}" viewBox="0 0 320 180">'
        f'<clipPath id="{p}b"><rect width="320" height="180" rx="{rx * 320 / w:.2f}"/></clipPath>'
        f'<g clip-path="url(#{p}b)">{body}</g></svg>'
    )


def render(svg, png):
    subprocess.run(["rsvg-convert", svg, "-o", png], check=True)


def main():
    os.makedirs(SVG_DIR, exist_ok=True)
    built = []
    for n, name, note, lic, face, rc in FONTS:
        body, size = banner(face, rc)
        built.append((n, name, note, lic, body, size))
        with open(os.path.join(SVG_DIR, f"F{n}-banner.svg"), "w") as f:
            f.write(f'<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 320 180">{body}</svg>')
        print(n, name, f"字号 {size:.1f}", f"大写高 {face.cap * size:.1f}", f"竖画 {face.stem * size:.1f}")

    # 大图:3 × 3
    W, cw, ch, top = 1640, 520, 370, 92
    H = top + 3 * ch
    s = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}">',
        f'<rect width="{W}" height="{H}" fill="#151517"/>',
        f'<text x="40" y="56" font-family="{CJK}" font-size="30" font-weight="600" fill="#F2F2F2">'
        '01F 字标合一 · 换字体(0 是对照)</text>',
    ]
    for i, (n, name, note, lic, body, size) in enumerate(built):
        col, row = i % 3, i // 3
        x, y = 40 + col * cw + col * 20, top + row * ch
        s.append(banner_at(f"g{n}", body, x, y, 500))
        lic_color = "#8FD18A" if lic.startswith("开源") else "#E0A66A"
        s.append(
            f'<text x="{x}" y="{y + 316}" font-family="{CJK}" font-size="23" font-weight="600" fill="#F2F2F2">{n}  {name}</text>'
            f'<text x="{x + 498}" y="{y + 316}" text-anchor="end" font-family="{CJK}" font-size="18" fill="{lic_color}">{lic}</text>'
            f'<text x="{x}" y="{y + 344}" font-family="{CJK}" font-size="18" fill="#9A9AA3">{note}</text>'
        )
    s.append("</svg>")
    path = os.path.join(SVG_DIR, "_fonts.svg")
    open(path, "w").write("".join(s))
    render(path, os.path.join(HERE, "fonts.png"))

    # 电视实际大小:1920 × 1080,banner 320 × 180
    s = [
        '<svg xmlns="http://www.w3.org/2000/svg" width="1920" height="1080">',
        '<defs><linearGradient id="wall" x1="0" y1="0" x2="0" y2="1">'
        '<stop offset="0" stop-color="#22242A"/><stop offset="1" stop-color="#0E0F12"/></linearGradient></defs>',
        '<rect width="1920" height="1080" fill="url(#wall)"/>',
        f'<text x="96" y="110" font-family="{CJK}" font-size="34" fill="#E6E6EA">电视上的实际大小 · 全屏后退两步看</text>',
    ]
    for i, (n, name, note, lic, body, size) in enumerate(built):
        row, col = (0, i) if i < 5 else (1, i - 5)
        x, y = 96 + col * 352, 190 + row * 330
        s.append(banner_at(f"t{n}", body, x, y, 320, rx=12))
        s.append(f'<text x="{x}" y="{y + 222}" font-family="{CJK}" font-size="24" fill="#9A9AA3">{n} {name}</text>')
    s.append("</svg>")
    path = os.path.join(SVG_DIR, "_fonts-tv.svg")
    open(path, "w").write("".join(s))
    render(path, os.path.join(HERE, "fonts-tv.png"))


if __name__ == "__main__":
    main()
