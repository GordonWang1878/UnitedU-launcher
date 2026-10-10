# list main-thread doFrame and RenderThread DrawFrame slices > threshold with top child slices
import sys
sys.path.insert(0, __import__("os").environ.get("PERFETTO_PY", "/tmp/edit-jank/tp/py"))
from perfetto.trace_processor import TraceProcessor
tp = TraceProcessor(trace=sys.argv[1])
thr = float(sys.argv[2]) if len(sys.argv) > 2 else 8
pkg = "com.uniteduone.launcher"
q = f"""
select s.id, s.ts, s.dur, s.name, t.name as tname, t.tid, p.pid from slice s join thread_track tt on s.track_id=tt.id join thread t using(utid) join process p using(upid)
where p.name='{pkg}' and s.depth=0 and (s.name like 'Choreographer#doFrame%' or s.name like 'DrawFrame%' or s.name like 'deliverInputEvent%' or s.name like 'traversal%' or t.name='RenderThread' or t.tid=p.pid)
order by s.ts
"""
rows = list(tp.query(q))
if not rows: print("no rows"); sys.exit()
t0 = rows[0].ts
for r in rows:
    if r.dur / 1e6 < thr: continue
    print(f"{(r.ts - t0)/1e6:8.1f}ms {r.dur/1e6:7.1f}ms [{r.tname}] {r.name[:60]}")
    kids = tp.query(f"""
      with recursive d(id, depth) as (select id, 0 from slice where id={r.id} union all select s.id, d.depth+1 from slice s join d on s.parent_id=d.id where d.depth<6)
      select s.name, s.dur, s.depth from slice s join d using(id) where s.id!={r.id} and s.dur > {thr*0.25*1e6} order by s.ts limit 25""")
    for k in kids:
        print(f"{'':20}{'  '*k.depth}{k.dur/1e6:6.1f} {k.name[:90]}")
