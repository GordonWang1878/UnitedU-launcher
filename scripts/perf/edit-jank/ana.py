import sys, subprocess, statistics as st
base = sys.argv[1]
# ---- framestats ----
lines = open(base + ".gfx.txt").read().splitlines()
hdr = None; rows = []
for l in lines:
    if l.startswith("Flags,"): hdr = l.strip().strip(",").split(","); continue
    if hdr and l and l[0].isdigit() and "," in l:
        v = l.strip().strip(",").split(",")
        if len(v) >= len(hdr) - 2: rows.append(dict(zip(hdr, map(int, v))))
    if l.startswith("---PROFILEDATA---") and rows: pass
summ = [l.strip() for l in lines if any(k in l for k in ("Total frames rendered", "Janky frames", "50th percentile", "90th percentile", "95th percentile", "99th percentile", "50th gpu", "90th gpu"))]
print("gfxinfo:", " | ".join(summ[:8]))
rows = [r for r in rows if r.get("Flags", 1) == 0]
rows.sort(key=lambda r: r["IntendedVsync"])
if rows:
    t0 = rows[0]["IntendedVsync"]
    if not __import__("os").environ.get("VERB"): rows=[]
    print(f"{'t(ms)':>7} {'inp':>3} {'ui':>6} {'rt':>6} {'gpu':>6} {'total':>6}  gap")
    prev = None; prevg = 0
    for r in rows:
        ui = (r["SyncQueued"] - r["HandleInputStart"]) / 1e6
        rt = (r["FrameCompleted"] - r["SyncStart"]) / 1e6
        g = r.get("GpuCompleted", 0)
        gpu = (g - max(r["SwapBuffers"], prevg)) / 1e6 if g else 0
        prevg = g or prevg
        tot = (r["FrameCompleted"] - r["IntendedVsync"]) / 1e6
        gap = (r["IntendedVsync"] - prev) / 1e6 if prev else 0
        prev = r["IntendedVsync"]
        mark = " <<" if tot > 16.7 or ui > 12 else ""
        print(f"{(r['IntendedVsync']-t0)/1e6:7.0f} {'K' if r['InputEventId'] else '':>3} {ui:6.1f} {rt:6.1f} {gpu:6.1f} {tot:6.1f} {gap:5.0f}{mark}")
# ---- mp4 pts ----
out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v", "-show_entries", "frame=pts_time", "-of", "csv=p=0", base + ".mp4"], capture_output=True, text=True).stdout.split()
pts = [float(x) for x in out if x]
if pts:
    gaps = [(pts[i] - pts[i-1]) * 1000 for i in range(1, len(pts))]
    print(f"mp4: {len(pts)} frames over {pts[-1]-pts[0]:.2f}s")
    # print bursts: sequences of frames with gap<200ms (= motion), with gap list
    seg = []; cur = [pts[0]]
    for i in range(1, len(pts)):
        if pts[i] - pts[i-1] < 0.25: cur.append(pts[i])
        else: seg.append(cur); cur = [pts[i]]
    seg.append(cur)
    for s in seg:
        if len(s) < 3: continue
        g = [(s[i]-s[i-1])*1000 for i in range(1, len(s))]
        big = [f"{x:.0f}" for x in g if x > 25]
        print(f"  motion @{s[0]:.2f}s dur {1000*(s[-1]-s[0]):.0f}ms frames {len(s)} fps {len(s)/max(1e-3,s[-1]-s[0]):.0f} gaps>25ms: {big}")
