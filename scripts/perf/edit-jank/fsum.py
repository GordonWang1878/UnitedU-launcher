# per-frame summary from trace: main doFrame(dur, recompose, measureLayout, record, postAndWait) + RT DrawFrames dur; flags input frames
import sys, statistics as st
sys.path.insert(0, __import__("os").environ.get("PERFETTO_PY", "/tmp/edit-jank/tp/py"))
from perfetto.trace_processor import TraceProcessor
tp = TraceProcessor(trace=sys.argv[1]); verbose = len(sys.argv) > 2
P = "com.uniteduone.launcher"
def kids(id, names):
    q = f"""with recursive d(id) as (select {id} union all select s.id from slice s join d on s.parent_id=d.id)
    select s.name n, sum(s.dur)/1e6 ms from slice s join d using(id) group by n"""
    m = {r.n: r.ms for r in tp.query(q)}
    return [sum(v for k, v in m.items() if k.startswith(n)) for n in names]
main = list(tp.query(f"select s.id, s.ts, s.dur, s.name from slice s join thread_track tt on s.track_id=tt.id join thread t using(utid) join process p using(upid) where p.name='{P}' and t.tid=p.pid and s.depth=0 and s.name like 'Choreographer#doFrame%' order by s.ts"))
rt = list(tp.query(f"select s.ts, s.dur, s.name from slice s join thread_track tt on s.track_id=tt.id join thread t using(utid) join process p using(upid) where p.name='{P}' and t.name='RenderThread' and s.depth=0 and s.name like 'DrawFrame%' order by s.ts"))
inp = [r.ts for r in tp.query(f"select s.ts from slice s join thread_track tt on s.track_id=tt.id join thread t using(utid) join process p using(upid) where p.name='{P}' and t.tid=p.pid and s.name = 'processInputEventForCompatibility' order by s.ts")]
rtmap = {r.name.split()[-1]: r.dur/1e6 for r in rt}
t0 = main[0].ts if main else 0
rows = []; used=set()
for f in main:
    vs = f.name.split()[-1]
    rec, ml, recd, paw, inpd = kids(f.id, ["Recomposer:recompose", "AndroidOwner:measureAndLayout", "Record View#draw()", "postAndWait", "deliverInputEvent"])
    prior = [x for x in inp if x <= f.ts]
    isk = bool(prior) and f.ts - prior[-1] < 40e6 and (prior[-1] not in used)
    if isk: used.add(prior[-1])
    if isk: inpd = (f.ts - prior[-1]) / 1e6
    rows.append((f.ts, f.dur/1e6, rec, ml, recd, paw, rtmap.get(vs, 0), isk))
    if verbose: print(f"{(f.ts-t0)/1e6:8.1f} {('K%4.1f'%inpd) if isk else '     '} main {f.dur/1e6:6.1f} recomp {rec:5.1f} m&l {ml:5.1f} rec {recd:5.1f} wait {paw:5.1f} | RT {rtmap.get(vs,0):5.1f}")
def stats(sel, label):
    if not sel: print(label, "n=0"); return
    def p(v, q): v = sorted(v); return v[min(len(v)-1, int(q*len(v)))]
    for i, nm in [(1, "main"), (2, "recomp"), (3, "meas&lay"), (4, "record"), (6, "RT")]:
        v = [r[i] for r in sel]
        print(f"  {label:9} {nm:9} n={len(v):3d} p50 {p(v,.5):5.1f} p90 {p(v,.9):5.1f} max {max(v):5.1f}")
stats([r for r in rows if r[7]], "key")
stats([r for r in rows if not r[7]], "anim")
# frame interval jank: gaps between consecutive RT frames within bursts
ts = [r.ts for r in rt]
gaps = [(ts[i]-ts[i-1])/1e6 for i in range(1, len(ts)) if (ts[i]-ts[i-1]) < 2e8]
over = [g for g in gaps if g > 20]
print(f"  RT frame gaps in-burst: n={len(gaps)} >20ms: {len(over)} ({100*len(over)/max(1,len(gaps)):.0f}%)  max {max(gaps) if gaps else 0:.0f}")
