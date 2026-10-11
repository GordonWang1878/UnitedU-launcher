# 用法:pixdiff.py a.png b.png [最大通道差=8] [平均通道差=0.5] [x0,y0,x1,y1]
# 两张同尺寸截图逐像素比:打印最大 / 平均通道差;都在阈值内退出码 0,否则 1。Task 4 / 6 的截图闸门用。
import sys
from PIL import Image, ImageChops, ImageStat
a = Image.open(sys.argv[1]).convert("RGB"); b = Image.open(sys.argv[2]).convert("RGB")
mx_lim = int(sys.argv[3]) if len(sys.argv) > 3 else 8
mean_lim = float(sys.argv[4]) if len(sys.argv) > 4 else 0.5
box = tuple(map(int, sys.argv[5].split(","))) if len(sys.argv) > 5 else (0, 0, a.width, a.height)
d = ImageChops.difference(a.crop(box), b.crop(box))
mx = max(hi for _, hi in d.getextrema())
mean = sum(ImageStat.Stat(d).mean) / 3
print(f"max {mx} mean {mean:.3f} (limits {mx_lim} / {mean_lim})")
sys.exit(0 if mx <= mx_lim and mean <= mean_lim else 1)
