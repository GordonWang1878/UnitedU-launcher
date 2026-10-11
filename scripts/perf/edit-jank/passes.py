import sys, statistics as st, collections
sys.path.insert(0, __import__("os").environ.get("PERFETTO_PY", "/tmp/edit-jank/tp/py"))
from perfetto.trace_processor import TraceProcessor
for fn in sys.argv[1:]:
    tp = TraceProcessor(trace=fn)
    q = """with f as (select s.id fid, s.ts, s.dur from slice s join thread_track tt on s.track_id=tt.id join thread t using(utid) join process p using(upid) where p.name='com.uniteduone.launcher' and t.name='RenderThread' and s.depth=0 and s.name like 'DrawFrame%')
    select f.fid, f.dur,
      (select count(*) from slice c where c.track_id=(select track_id from slice where id=f.fid) and c.ts>=f.ts and c.ts<f.ts+f.dur and c.name like '%OpsTask::onExecute%') ops,
      (select count(*) from slice c where c.track_id=(select track_id from slice where id=f.fid) and c.ts>=f.ts and c.ts<f.ts+f.dur and c.name like 'drawLayer%') layers,
      (select count(*) from slice c where c.track_id=(select track_id from slice where id=f.fid) and c.ts>=f.ts and c.ts<f.ts+f.dur and c.name like 'Texture upload%') ups
    from f"""
    rows = list(tp.query(q))
    ops = [r.ops for r in rows]; lay=[r.layers for r in rows]; ups=[r.ups for r in rows]
    print(f"{fn:22} frames {len(rows):4d} renderpasses/frame p50 {st.median(ops):5.1f} max {max(ops):4d} | drawLayer p50 {st.median(lay)} max {max(lay)} | uploads total {sum(ups)}")
