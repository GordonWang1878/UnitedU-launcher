import sys, subprocess, os, glob
from PIL import Image, ImageDraw
mp4, outp = sys.argv[1], sys.argv[2]
sel = sys.argv[3] if len(sys.argv) > 3 else None  # e.g. "1.0-2.2"
d = outp + "_frames"; os.makedirs(d, exist_ok=True)
for f in glob.glob(d + "/*.png"): os.remove(f)
subprocess.run(["ffmpeg", "-y", "-loglevel", "quiet", "-i", mp4, "-fps_mode", "passthrough", "-vf", "scale=320:180", d + "/f%04d.png"])
pts = [float(x) for x in subprocess.run(["ffprobe", "-v", "error", "-show_entries", "frame=pts_time", "-of", "csv=p=0", mp4], capture_output=True, text=True).stdout.split()]
fs = sorted(glob.glob(d + "/*.png"))
items = list(zip(pts, fs))
if sel:
    a, b = map(float, sel.split("-")); items = [x for x in items if a <= x[0] <= b]
cols = 5; W, H = 320, 196
rows = (len(items) + cols - 1) // cols
im = Image.new("RGB", (cols * W, rows * H), "white"); dr = ImageDraw.Draw(im)
for i, (p, f) in enumerate(items):
    x, y = (i % cols) * W, (i // cols) * H
    im.paste(Image.open(f), (x, y)); dr.text((x + 4, y + 182), f"{p*1000:.0f} ms", fill="black")
im.save(outp + ".png"); print(len(items), "frames")
