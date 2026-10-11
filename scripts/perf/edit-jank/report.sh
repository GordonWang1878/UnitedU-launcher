#!/usr/bin/env bash
# 用法:report.sh <tag>  把 suite.sh <tag> 的各段汇总到 $T/summary_<tag>.txt(mp4 pts、framestats 分位、trace 按键帧 / 动画帧、RT 拆分、渲染遍数)
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; T="${T:-/tmp/edit-jank}"; tag=$1
{
for seg in H A B C D E A2 E2; do
  b="$T/${seg}_$tag"; [ -f "$b.mp4" ] || continue
  echo "=== $seg"
  python3 "$HERE/ana.py" "$b"
  python3 "$HERE/fsum.py" "$b.pftrace"
done
python3 "$HERE/rtbreak.py" "$T"/*_"$tag".pftrace
python3 "$HERE/passes.py" "$T"/*_"$tag".pftrace
} 2>&1 | tee "$T/summary_$tag.txt"
