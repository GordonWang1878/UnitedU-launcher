import sys
sys.path.insert(0, __import__("os").environ.get("PERFETTO_PY", "/tmp/edit-jank/tp/py"))
from perfetto.trace_processor import TraceProcessor
tp = TraceProcessor(trace=sys.argv[1]); parent = sys.argv[2]
q = f"""
with recursive d(id) as (select s.id from slice s join thread_track tt on s.track_id=tt.id join thread t using(utid) join process p using(upid) where p.name='com.uniteduone.launcher' and s.name like '{parent}%'
 union all select s.id from slice s join d on s.parent_id=d.id)
select s.name as n, count(*) as c, sum(s.dur)/1e6 as ms from slice s join d using(id) where s.name not like '{parent}%' group by n order by ms desc limit 30"""
for r in tp.query(q): print(f"{r.ms:8.1f}ms {r.c:5d}  {r.n[:100]}")
