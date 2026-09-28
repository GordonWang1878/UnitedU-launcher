#!/usr/bin/env python3
"""
内置壁纸 / 屏保的 HDR 转换脚本(2026-09-28,规范见 docs/design/hdr-image-spec.md)。

把任意来源的 HDR 照片(ISO 21496-1 增益图 / Android Ultra HDR 1.0 / 苹果等 MPF 增益图 JPEG)统一转成
**双写法** Ultra HDR JPEG:同一个文件里既有 Android 14 认的 XMP(hdrgm)说明书,也有 Android 15+ / iOS 认的
ISO 21496-1 说明书。分辨率不变(4K 原样),普通画面 JPEG 质量 90,增益图转单通道、JPEG 质量 85。
没有增益图的 SDR 输入:只压成 JPEG 90 并警告(不会凭空造 HDR)。

依赖:
- Pillow + numpy(pip)
- Homebrew 的 `ultrahdr_app`(libultrahdr 2.x,用来读出原图的增益图参数)
- 自编译的 `ultrahdr_app`(libultrahdr 1.4.0,cmake 打开 -DUHDR_WRITE_XMP=1 -DUHDR_WRITE_ISO=1),默认路径
  ~/Library/uhdr-build/libultrahdr/build/ultrahdr_app,编译步骤见规范文档。

用法:
  scripts/hdr-assets.py --in-place <内置图目录>     ← 常用:原地处理仓库里新放进来的图
  scripts/hdr-assets.py <输入图片或目录> <输出目录>
  例:scripts/hdr-assets.py --in-place app/src/main/assets/builtin/wallpapers
  --in-place:已经处理过(两份标记都在)的图跳过;没处理过的先把原图备份到
  ~/unitedu-assets-originals/<日期>/<目录名>/,再原地换成转换后的 .jpg(原来是 png/webp 的,原文件删掉)。
  构建时的单测 BuiltinHdrAssetsTest 会拦下没处理过的图,报错信息里就是这条命令。
输出文件名与输入相同(扩展名统一 .jpg)。每张图打印:大小、两种标记是否都在、提亮参数、普通画面 PSNR。
任何一张校验不过,脚本以非 0 退出。
"""
import math
import os
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image

READER = os.environ.get("UHDR_READER", "/opt/homebrew/bin/ultrahdr_app")
WRITER = os.environ.get("UHDR_WRITER", str(Path.home() / "Library/uhdr-build/libultrahdr/build/ultrahdr_app"))
BASE_QUALITY = 90
GAINMAP_QUALITY = 85
MIN_PSNR = 38.0


def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True)


def read_metadata(src: Path, tmp: Path):
    """用 libultrahdr 2.x 读出增益图参数;没有增益图返回 None。"""
    probe = run([READER, "-m", "1", "-P", "-j", str(src)])
    if "Ultra HDR Image: Yes" not in probe.stdout:
        return None
    cfg = tmp / "meta.cfg"
    run([READER, "-m", "1", "-j", str(src), "-f", str(cfg), "-o", "3", "-O", "3", "-z", str(tmp / "dec.raw")])
    meta = {}
    for line in cfg.read_text().splitlines():
        k, *v = line.split()
        meta[k] = [float(x) for x in v]
    return meta


def mono_metadata(meta):
    """Android 14 的 XMP 写法只支持单通道:三通道参数取均值(实测三通道差 < 0.2%)。"""
    out = {}
    for k, v in meta.items():
        out[k] = v[0] if k in ("--hdrCapacityMin", "--hdrCapacityMax", "--useBaseColorSpace") else sum(v) / len(v)
    return out


def psnr(a: Image.Image, b: Image.Image) -> float:
    x = np.asarray(a, dtype=np.float32)
    y = np.asarray(b, dtype=np.float32)
    mse = float(np.mean((x - y) ** 2))
    return 99.0 if mse == 0 else 10 * math.log10(255 * 255 / mse)


def convert(src: Path, out_dir: Path) -> bool:
    out_dir.mkdir(parents=True, exist_ok=True)
    dst = out_dir / (src.stem + ".jpg")
    with tempfile.TemporaryDirectory() as t:
        tmp = Path(t)
        im = Image.open(src)
        im.seek(0)
        base = im.convert("RGB")
        base_path = tmp / "base.jpg"
        base.save(base_path, "JPEG", quality=BASE_QUALITY, optimize=True)
        meta = read_metadata(src, tmp)
        if meta is None:
            base.save(dst, "JPEG", quality=BASE_QUALITY, optimize=True)
            print(f"WARN {src.name}: 没有增益图,按 SDR 压成 JPEG {BASE_QUALITY}(不是 HDR)")
            return True
        if getattr(im, "n_frames", 1) < 2:
            print(f"FAIL {src.name}: 读得到增益图参数,但文件里找不到第二帧增益图(非 MPF 容器),脚本不处理")
            return False
        im.seek(1)
        gm = np.asarray(im.convert("RGB"), dtype=np.float32).mean(axis=2)
        gm_path = tmp / "gainmap.jpg"
        Image.fromarray(np.clip(gm + 0.5, 0, 255).astype(np.uint8), "L").save(gm_path, "JPEG", quality=GAINMAP_QUALITY, optimize=True)
        mono = mono_metadata(meta)
        cfg = tmp / "mono.cfg"
        cfg.write_text("".join(f"{k} {v:.6g}\n" for k, v in mono.items()))
        r = run([WRITER, "-m", "0", "-i", str(base_path), "-g", str(gm_path), "-f", str(cfg), "-z", str(dst)])
        if not dst.exists():
            print(f"FAIL {src.name}: 编码失败 {r.stdout.strip()[-200:]} {r.stderr.strip()[-200:]}")
            return False
    data = dst.read_bytes()
    has_xmp = b"hdrgm" in data
    has_iso = b"iso:ts:21496" in data
    back = read_metadata(dst, Path(tempfile.mkdtemp()))
    same = back is not None and abs(back["--maxContentBoost"][0] - mono["--maxContentBoost"]) < 0.01
    out_base = Image.open(dst)
    out_base.seek(0)
    p = psnr(base, out_base.convert("RGB"))
    ok = has_xmp and has_iso and same and p >= MIN_PSNR
    print(
        f"{'OK  ' if ok else 'FAIL'} {src.name}: {os.path.getsize(src)/1e6:.1f} MB → {len(data)/1e6:.2f} MB | "
        f"XMP={'有' if has_xmp else '无'} ISO={'有' if has_iso else '无'} | "
        f"maxBoost {mono['--maxContentBoost']:.3f} → {back['--maxContentBoost'][0] if back else float('nan'):.3f} | PSNR {p:.1f} dB"
    )
    return ok


IMAGE_SUFFIXES = (".jpg", ".jpeg", ".png", ".webp")
MAX_CONVERTED_BYTES = 2_500_000


def already_converted(p: Path) -> bool:
    """处理过的判据(与 BuiltinHdrAssetsTest 同一口径):.jpg、两份 HDR 标记都在、体积 ≤ 2.5 MB;
    或是脚本按 SDR 压过的 .jpg(没有增益图、体积 ≤ 2.5 MB)。"""
    if p.suffix.lower() != ".jpg" or p.stat().st_size > MAX_CONVERTED_BYTES:
        return False
    data = p.read_bytes()
    has_xmp, has_iso = b"hdrgm" in data, b"iso:ts:21496" in data
    return (has_xmp and has_iso) or (not has_xmp and not has_iso and b"MPF" not in data)


def in_place(d: Path) -> bool:
    import datetime, shutil
    backup = Path.home() / "unitedu-assets-originals" / datetime.date.today().isoformat() / d.name
    ok = True
    for f in sorted(p for p in d.iterdir() if p.suffix.lower() in IMAGE_SUFFIXES):
        if already_converted(f):
            print(f"SKIP {f.name}: 已处理")
            continue
        backup.mkdir(parents=True, exist_ok=True)
        shutil.copy2(f, backup / f.name)
        with tempfile.TemporaryDirectory() as t:
            staged = Path(t) / f.name
            shutil.copy2(f, staged)
            if not convert(staged, Path(t) / "out"):
                ok = False
                continue
            out = Path(t) / "out" / (f.stem + ".jpg")
            if f.suffix.lower() != ".jpg":
                f.unlink()
            shutil.copy2(out, d / out.name)
        print(f"     原图备份 → {backup / f.name}")
    return ok


def main():
    for tool in (READER, WRITER):
        if not Path(tool).exists():
            print(f"缺工具:{tool}(见 docs/design/hdr-image-spec.md)")
            sys.exit(2)
    if len(sys.argv) == 3 and sys.argv[1] == "--in-place":
        sys.exit(0 if in_place(Path(sys.argv[2]).expanduser()) else 1)
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    src, out = Path(sys.argv[1]).expanduser(), Path(sys.argv[2]).expanduser()
    out.mkdir(parents=True, exist_ok=True)
    files = [src] if src.is_file() else sorted(p for p in src.iterdir() if p.suffix.lower() in IMAGE_SUFFIXES)
    results = [convert(f, out) for f in files]
    print(f"{sum(results)}/{len(results)} 通过")
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
