import sys
sys.path.insert(0, __import__("os").environ.get("PERFETTO_PY", "/tmp/edit-jank/tp/py"))
from perfetto.trace_processor import TraceProcessor
for fn in sys.argv[1:]:
    tp = TraceProcessor(trace=fn)
    base = "select s.id from slice s join thread_track tt on s.track_id=tt.id join thread t using(utid) join process p using(upid) where p.name='com.uniteduone.launcher' and t.name='RenderThread'"
    tot = list(tp.query(f"select sum(s.dur)/1e6 ms, count(*) n from slice s join thread_track tt on s.track_id=tt.id join thread t using(utid) join process p using(upid) where p.name='com.uniteduone.launcher' and t.name='RenderThread' and s.depth=0 and s.name like 'DrawFrame%'"))[0]
    def m(pat):
        r = list(tp.query(f"select sum(s.dur)/1e6 ms, count(*) n from slice s join thread_track tt on s.track_id=tt.id join thread t using(utid) join process p using(upid) where p.name='com.uniteduone.launcher' and t.name='RenderThread' and s.name like '{pat}'"))[0]
        return (r.ms or 0, r.n)
    parts = {k: m(v) for k, v in [("prepareTree", "prepareTree"), ("tex upload", "Texture upload%"), ("glGenTextures", "glGenTextures encode"), ("flush layers", "flush layers"), ("drawLayer", "drawLayer%"), ("dequeueBuffer", "dequeueBuffer"), ("shader_compile", "shader_compile"), ("DrawGainmap", "DrawGainmapBitmap")]}
    print(f"{fn:16} RT frames {tot.n} total {tot.ms:.0f}ms | " + " | ".join(f"{k} {v[0]:.0f}ms/{v[1]}" for k, v in parts.items()))
