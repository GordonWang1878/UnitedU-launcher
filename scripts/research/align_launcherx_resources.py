#!/usr/bin/env python3
"""
把 launcherx「资源名被收拢」的新版,对齐到「资源名可读」的旧版,把名字传过去。

原理(2026-09-22 实证,见 docs/research/2026-09-20-google-tv-launcherx-measurements.md §12):
  aapt2 给同一类型的资源分配 ID 时**严格按名字字母序**(旧版 dimen/integer/fraction/string/color
  五类各 100% 单调)。新版虽然把名字抹成 0_resource_name_obfuscated,**顺序还在**——所以两版之间
  是一道序列对齐题:值相同且顺序一致的条目直接把名字传过去(LCS),对不上的新条目,名字也被夹在
  相邻两个已匹配名字之间。新版残留的 3 条真名用作地面真值,3/3 通过。

用法:
  aapt2 dump resources old.apk > old.txt
  aapt2 dump resources new.apk > new.txt
  python3 align_launcherx_resources.py old.txt new.txt dimen integer fraction > out.md

输出三段:① 对上名字的(值在两版一致);② 旧版有名、新版同区间里有个值不同的——最可能是
「同名改值」;③ 新版新增、旧版没有的(只给上下界)。
"""
import re, sys
import numpy as np

def parse(path):
    out={}; cur=None; vals=[]
    def flush():
        if cur: out.setdefault(cur[1],[]).append((cur[0],cur[2],tuple(vals)))
    for l in open(path,encoding="utf-8",errors="replace"):
        m=re.match(r"\s+resource (0x[0-9a-f]+) (\w+)/(\S+)",l)
        if m: flush(); cur=(int(m.group(1),16),m.group(2),m.group(3)); vals=[]; continue
        if cur and re.match(r"\s+\(",l):
            v=l.strip()
            # 引用型的值:被引用的 ID 会漂、名字在新版被抹,所以只保留"是个指向某类型的引用"
            v=re.sub(r"@0x[0-9a-f]+","@REF",v)
            v=re.sub(r"@(\w+)/\S+",r"@REF:\1",v)
            v=re.sub(r"\(0x[0-9a-f]+\)","",v)
            vals.append(v)
    flush()
    for t in out: out[t].sort(key=lambda r:r[0])
    return out

def lcs_align(A,B):
    idx={}
    a=[idx.setdefault(s,len(idx)) for s in A]; b=np.array([idx.setdefault(s,len(idx)) for s in B])
    n,m=len(a),len(b); dp=np.zeros((n+1,m+1),dtype=np.int32)
    for i in range(1,n+1):
        eq=(b==a[i-1]).astype(np.int32); prev=dp[i-1]; row=dp[i]; diag=prev[:-1]+eq
        for j in range(1,m+1):
            v=prev[j]
            if row[j-1]>v: v=row[j-1]
            if diag[j-1]>v: v=diag[j-1]
            row[j]=v
    i,j=n,m; pairs=[]
    while i>0 and j>0:
        if a[i-1]==b[j-1] and dp[i][j]==dp[i-1][j-1]+1: pairs.append((i-1,j-1)); i-=1; j-=1
        elif dp[i-1][j]>=dp[i][j-1]: i-=1
        else: j-=1
    return pairs[::-1]

def fmt(sig): return " / ".join(sig) if sig else ""

def kind(sig):
    """值的"种类":单位(dp/sp/px/%)、纯数、引用、其它——同名改值时种类不会变,种类不同一律不配对。"""
    if not sig: return "empty"
    v=sig[0]
    m=re.search(r"[-\d.]+(dp|sp|px|pt|in|mm|%)\b",v)
    if m: return m.group(1)
    if "@REF" in v: return "ref"
    if re.search(r"\)\s*[-\d.]+\s*$",v): return "num"
    return "other"

def main():
    old=parse(sys.argv[1]); new=parse(sys.argv[2]); types=sys.argv[3:]
    print(f"# launcherx 资源名对齐结果\n\n旧版 `{sys.argv[1]}`(有名)→ 新版 `{sys.argv[2]}`(名字被收拢)。方法见脚本头注释。\n")
    for t in types:
        O=old.get(t,[]); N=new.get(t,[])
        pairs=lcs_align([r[2] for r in O],[r[2] for r in N])
        oi={i for i,_ in pairs}; ni={j for _,j in pairs}; name_of={j:O[i][1] for i,j in pairs}
        print(f"\n## {t}:旧 {len(O)} 条,新 {len(N)} 条,对上 {len(pairs)} 条({len(pairs)/max(1,len(N))*100:.0f}% 的新条目)\n")
        # ② 同名改值候选:在相邻两个匹配锚点之间,旧未匹配 × 新未匹配 一一对应(数量相等时置信高)
        print(f"### {t} · 「同名改值」候选 —— **只是候选,用前要人工核**(两个锚点之间旧、新未匹配条目数相等且单位一致时,按位次配对)\n")
        print("| 新版 ID | 推定名字 | 旧版值 | 新版值 | 置信 |\n|---|---|---|---|---|")
        anchors=[(-1,-1)]+pairs+[(len(O),len(N))]
        changed=0
        for (i0,j0),(i1,j1) in zip(anchors,anchors[1:]):
            og=[O[i] for i in range(i0+1,i1)]; ng=[N[j] for j in range(j0+1,j1)]
            if not og or not ng: continue
            conf="高" if len(og)==len(ng) else "中" if abs(len(og)-len(ng))==1 else "低"
            if conf!="高": continue                    # 「中/低」噪声太多(旧删新增混在同一区间),只报锚点间数量相等的
            for k in range(min(len(og),len(ng))):
                if kind(og[k][2])!=kind(ng[k][2]): continue     # 单位/种类不一致 → 不是同一个资源
                print(f"| {hex(ng[k][0])} | `{og[k][1]}` | `{fmt(og[k][2])}` | `{fmt(ng[k][2])}` | {conf} |"); changed+=1
        if not changed: print("| — | — | — | — | — |")
        print(f"\n### {t} · 对上名字的(值两版一致)\n")
        print("| 新版 ID | 名字 | 值 |\n|---|---|---|")
        for j,(nid,_,sig) in enumerate(N):
            if j in ni: print(f"| {hex(nid)} | `{name_of[j]}` | `{fmt(sig)}` |")
        print(f"\n### {t} · 新版新增、旧版没有的(只知道名字的字母序区间)\n")
        print("| 新版 ID | 名字区间 | 值 |\n|---|---|---|")
        lo=None; rows=[]
        for j,(nid,_,sig) in enumerate(N):
            if j in ni: lo=name_of[j]
            else: rows.append([nid,lo,None,sig])
        hi=None
        for j in range(len(N)-1,-1,-1):
            if j in ni: hi=name_of[j]
        # 回填 hi:按位置
        hi=None; k=len(rows)-1
        for j in range(len(N)-1,-1,-1):
            if j in ni: hi=name_of[j]
            elif k>=0 and rows[k][0]==N[j][0]: rows[k][2]=hi; k-=1
        for nid,lo,hi,sig in rows:
            print(f"| {hex(nid)} | {lo or '(开头)'} … {hi or '(结尾)'} | `{fmt(sig)}` |")

if __name__=="__main__": main()
