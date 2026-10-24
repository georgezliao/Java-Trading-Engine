#!/usr/bin/env python3
"""Percentile plot and latency-vs-throughput table from sweep folders.

    python3 scripts/plot.py results/local/<run> [more run folders...] [--out DIR]

Reads only the .hgrm/.meta files the engine writes (values in microseconds). Writes
percentiles.png and latency_vs_throughput.md to --out (default: the first run folder) and
prints the table.
"""
import argparse
import math
import re
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

PCTS = [50, 99, 99.9, 99.99]
ROW = re.compile(r"^\s*([\d.]+)\s+([\d.]+)\s+(\d+)\s+(\S+)\s*$")
MAX = re.compile(r"#\[Max\s*=\s*([\d.]+)")


def read_hgrm(path):
    rows, mx = [], None
    for line in path.read_text().splitlines():
        if m := ROW.match(line):
            rows.append((float(m[1]), float(m[2])))  # (value_us, percentile 0..1)
        elif m := MAX.search(line):
            mx = float(m[1])
    return rows, mx


def at(rows, pct):
    target = pct / 100
    for value, p in rows:
        if p >= target:
            return value
    return rows[-1][0] if rows else math.nan


def read_meta(path):
    if not path.exists():
        return {}
    return dict(l.split("=", 1) for l in path.read_text().splitlines() if "=" in l)


def runs(folders):
    for folder in folders:
        for hgrm in sorted(Path(folder).glob("*.hgrm")):
            if hgrm.stem.endswith("-triggered"):
                continue
            rows, mx = read_hgrm(hgrm)
            trig_rows, _ = read_hgrm(hgrm.with_name(hgrm.stem + "-triggered.hgrm")) \
                if hgrm.with_name(hgrm.stem + "-triggered.hgrm").exists() else ([], None)
            meta = read_meta(hgrm.with_suffix(".meta"))
            yield dict(name=hgrm.stem, rows=rows, max=mx, trig=trig_rows, meta=meta,
                       variant=meta.get("variant", hgrm.stem.rsplit("-", 1)[0]),
                       rate=int(meta.get("rate", hgrm.stem.rsplit("-", 1)[-1])))


def fmt(v):
    return "-" if v is None or math.isnan(v) else f"{v:,.1f}"


def table(rs):
    head = ["variant", "target msg/s", "achieved", "p50 us", "p99 us", "p99.9 us", "p99.99 us",
            "max us", "trig p99.9 us", "GCs"]
    lines = ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    for r in rs:
        m = r["meta"]
        cells = [r["variant"], f"{r['rate']:,}", f"{int(m['achieved.rate']):,}" if "achieved.rate" in m else "-"]
        cells += [fmt(at(r["rows"], p)) for p in PCTS]
        cells += [fmt(r["max"]), fmt(at(r["trig"], 99.9)) if r["trig"] else "-", m.get("gc.collections", "-")]
        lines.append("| " + " | ".join(cells) + " |")
    return "\n".join(lines) + "\n"


def plot(rs, out):
    fig, ax = plt.subplots(figsize=(8, 5))
    for r in rs:
        pts = [(1 / (1 - p), v) for v, p in r["rows"] if p < 1]
        if pts:
            xs, ys = zip(*pts)
            ax.plot(xs, ys, label=f"{r['variant']} @ {r['rate']:,}/s", linewidth=1.2)
    ticks = [2, 10, 100, 1000, 10000, 100000]
    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.set_xticks(ticks)
    ax.set_xticklabels(["50%", "90%", "99%", "99.9%", "99.99%", "99.999%"])
    ax.set_xlabel("percentile")
    ax.set_ylabel("latency (us, from intended send time)")
    ax.grid(True, which="major", alpha=0.3)
    ax.legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(out, dpi=150)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("folders", nargs="+")
    ap.add_argument("--out")
    a = ap.parse_args()
    rs = sorted(runs(a.folders), key=lambda r: (r["variant"], r["rate"]))
    if not rs:
        raise SystemExit("no .hgrm files found")
    out = Path(a.out or a.folders[0])
    out.mkdir(parents=True, exist_ok=True)
    md = table(rs)
    (out / "latency_vs_throughput.md").write_text(md)
    plot(rs, out / "percentiles.png")
    print(md)
    print(f"wrote {out / 'percentiles.png'} and {out / 'latency_vs_throughput.md'}")


if __name__ == "__main__":
    main()
