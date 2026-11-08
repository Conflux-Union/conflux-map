#!/usr/bin/env python3
"""Aggregate test_legacy_fullscale unit outputs into per-stride tables."""
import glob, re, sys

LINE = re.compile(
    r"UNIT seed=(\d+) region=(\d+) stride=(\d+) "
    r"(opt|mast): cells=(\d+) mae=([-\d.]+) p2=([-\d.]+)% p4=([-\d.]+)% max=(\d+) "
    r"flips=([-\d.]+)% bucket=([-\d.]+)% biome=([-\d.]+)% "
    r"falseCliff=(\d+) missedCliff=(\d+) spike=(\d+) "
    r"gradHist=(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+)")

S = {}  # (stride, tag) -> dict of sums
R = {}  # (stride, tag, region) -> [maeSum, cells]
units = set()
for path in glob.glob(sys.argv[1] + "/unit_*.txt"):
    for line in open(path, encoding="utf-8"):
        m = LINE.search(line)
        if not m:
            continue
        seed, region, stride, tag = int(m[1]), int(m[2]), int(m[3]), m[4]
        c = int(m[5])
        v = dict(mae=float(m[6])*c, p2=float(m[7])*c, p4=float(m[8])*c, max=int(m[9]),
                 flips=float(m[10])*c, bucket=float(m[11])*c, biome=float(m[12])*c,
                 falseCliff=int(m[13]), missedCliff=int(m[14]), spike=int(m[15]))
        gh = [int(m[i]) for i in range(16, 23)]
        units.add((seed, region))
        k = (stride, tag)
        d = S.setdefault(k, dict(cells=0, max=0, gh=[0]*7))
        d["cells"] += c
        for f in v:
            d[f] = d.get(f, 0) + v[f]
        d["max"] = max(d["max"], v["max"])
        for i in range(7):
            d["gh"][i] += gh[i]
        rk = (stride, tag, region)
        e = R.setdefault(rk, [0.0, 0])
        e[0] += float(m[6]) * c
        e[1] += c

print(f"units: {len(units)} (expect 50)")
for stride in (1, 2, 4, 8, 16):
    for tag in ("opt", "mast"):
        k = (stride, tag)
        if k not in S:
            continue
        d = S[k]
        c = d["cells"]
        gh = "|".join(f"{x:,}" for x in d["gh"])
        print(f"stride={stride:2d} {tag:4s}: cells={c:>11,} mae={d['mae']/c:8.4f} "
              f"<=2: {d['p2']/c:6.2f}%  <=4: {d['p4']/c:6.2f}%  max={d['max']:4d}  "
              f"waterFlips={d['flips']/c:7.4f}%  depthBucket={d['bucket']/c:7.4f}%  "
              f"biome={d['biome']/c:8.4f}%")
        if stride == 1:
            print(f"          artifacts: falseCliff={d['falseCliff']:,} ({100.0*d['falseCliff']/c:.4f}%)  "
                  f"missedCliff={d['missedCliff']:,} ({100.0*d['missedCliff']/c:.4f}%)  "
                  f"spike={d['spike']:,}")
            print(f"          |gradC-gradE| hist 0/1/2/3-4/5-6/7-10/>10: {gh}")
    rows = sorted(((R[(stride,'opt',r)][0]/R[(stride,'opt',r)][1], r)
                   for r in range(5) if (stride,'opt',r) in R), reverse=True)
    if rows:
        print(f"          opt MAE by region: " + ", ".join(f"R{r}:{m:.2f}" for m, r in rows))
for f in ("falseCliff", "missedCliff", "spike"):
    if (1,"opt") in S and (1,"mast") in S:
        print(f"stride=1 opt-mast {f}: {S[(1,'opt')][f]-S[(1,'mast')][f]:+d}")
