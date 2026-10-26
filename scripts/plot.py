#!/usr/bin/env python3
"""Percentile plot and latency-vs-throughput table from sweep folders.

    python3 scripts/plot.py results/local/<run> [more run folders...] [--out DIR]
    python3 scripts/plot.py results/oscar/<jobid> --summary

--summary groups repeated runs (names ending in -r<N>) by config and rate and reports the
median across reps, the p99.9 range, a p99.9-vs-rate plot and a percentile plot at --rate
using each config's median-p99.9 rep.

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
    head = ["run", "target msg/s", "achieved", "p50 us", "p99 us", "p99.9 us", "p99.99 us",
            "max us", "trig p99.9 us", "GCs"]
    lines = ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    for r in rs:
        m = r["meta"]
        cells = [r["name"], f"{r['rate']:,}", f"{int(m['achieved.rate']):,}" if "achieved.rate" in m else "-"]
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
            ax.plot(xs, ys, label=r["name"], linewidth=1.2)
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


def summarize(rs, out, plot_rate):
    import statistics as st
    groups = {}
    for r in rs:
        config = re.sub(r"-\d+-r\d+$", "", r["name"])
        groups.setdefault((config, r["rate"]), []).append(r)
    head = ["config", "rate", "reps", "p50 us", "p99 us", "p99.9 us", "p99.9 range", "max us", "behind"]
    lines = ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    by_config = {}
    for (config, rate), runs in sorted(groups.items()):
        med = lambda p: st.median(at(r["rows"], p) for r in runs)
        p999 = sorted(at(r["rows"], 99.9) for r in runs)
        # A run "fell behind" if it delivered under 99% of the target rate.
        behind = sum(1 for r in runs if "achieved.rate" in r["meta"]
                     and int(r["meta"]["achieved.rate"]) < 0.99 * rate)
        lines.append("| " + " | ".join([
            config, f"{rate:,}", str(len(runs)), fmt(med(50)), fmt(med(99)), fmt(med(99.9)),
            f"{fmt(p999[0])} - {fmt(p999[-1])}", fmt(st.median(r["max"] for r in runs)), str(behind)]) + " |")
        by_config.setdefault(config, []).append((rate, med(99.9), runs))
    md = "\n".join(lines) + "\n"
    (out / "summary.md").write_text(md)

    fig, ax = plt.subplots(figsize=(8, 5))
    for config, pts in sorted(by_config.items()):
        pts.sort()
        ax.plot([p[0] for p in pts], [p[1] for p in pts], marker="o", label=config, linewidth=1.2)
    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.set_xlabel("target rate (msg/s)")
    ax.set_ylabel("p99.9 latency (us), median of reps")
    ax.grid(True, which="major", alpha=0.3)
    ax.legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(out / "p999_vs_rate.png", dpi=150)

    reps = []
    for config, pts in sorted(by_config.items()):
        for rate, _, runs in pts:
            if rate == plot_rate:
                runs = sorted(runs, key=lambda r: at(r["rows"], 99.9))
                rep = dict(runs[len(runs) // 2])
                rep["name"] = config
                reps.append(rep)
    if reps:
        plot(reps, out / f"percentiles_{plot_rate}.png")
    return md


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("folders", nargs="+")
    ap.add_argument("--out")
    ap.add_argument("--summary", action="store_true")
    ap.add_argument("--rate", type=int, default=1_000_000, help="rate for the --summary percentile plot")
    a = ap.parse_args()
    rs = sorted(runs(a.folders), key=lambda r: (r["variant"], r["rate"], r["name"]))
    if not rs:
        raise SystemExit("no .hgrm files found")
    out = Path(a.out or a.folders[0])
    out.mkdir(parents=True, exist_ok=True)
    if a.summary:
        print(summarize(rs, out, a.rate))
        print(f"wrote summary.md, p999_vs_rate.png, percentiles_{a.rate}.png to {out}")
        return
    md = table(rs)
    (out / "latency_vs_throughput.md").write_text(md)
    plot(rs, out / "percentiles.png")
    print(md)
    print(f"wrote {out / 'percentiles.png'} and {out / 'latency_vs_throughput.md'}")


if __name__ == "__main__":
    main()
