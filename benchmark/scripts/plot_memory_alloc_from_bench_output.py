"""
Plot memory allocation graph from JsonValidator bench_output.txt.

Source format expected:
  "Median allocated bytes per validation call:"
  table with columns: Length | Jackson | AlwaysEscape | Validason | FILE

This script extracts selected target columns (default: Jackson, AlwaysEscape, Validason),
aggregates duplicate lengths by median, and draws a memory-allocation-vs-length chart
with the same visual style used by plot_bench.py.
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker
import numpy as np


BG = "#1e1e2e"
GRID = "#313244"
TEXT = "#cdd6f4"

FIXED_TARGET_COLORS = {
    "jackson": "#f38ba8",
    "alwaysescape": "#bd93f9",
    "validason": "#94e2d5",
    "jacksoncanonoff": "#f38ba8",
    "jacksondefaultcanonon": "#f9e2af",
    "jacksonreadtree": "#4dabf7",
}

TEMP_COLORS = [
    "#fab387",
    "#89b4fa",
    "#f2cdcd",
    "#a6adc8",
    "#f5c2e7",
    "#74c7ec",
    "#f7d794",
    "#7dd3fc",
    "#f4a261",
    "#84cc16",
]


def normalize_color_key(name: str) -> str:
    return re.sub(r"[^a-z0-9]", "", name.lower())


def build_series_colors(keys: list[str]) -> dict[str, str]:
    colors: dict[str, str] = {}
    idx = 0
    for key in keys:
        normalized = normalize_color_key(key)
        if normalized in FIXED_TARGET_COLORS:
            colors[key] = FIXED_TARGET_COLORS[normalized]
        else:
            colors[key] = TEMP_COLORS[idx % len(TEMP_COLORS)]
            idx += 1
    return colors


def resolve_color(name: str, colors: dict[str, str]) -> str:
    return colors.get(name, TEMP_COLORS[0])


def key_sort(name: str) -> tuple[int, int, str]:
    normalized = normalize_color_key(name)
    if normalized == "jackson":
        return (0, 0, name)
    if normalized == "alwaysescape":
        return (0, 1, name)
    if normalized == "validason":
        return (0, 2, name)
    return (1, 0, name)

SEP_RE = re.compile(r"^\s*[-=]{10,}\s*$")
VALUE_RE = re.compile(r"([0-9]+(?:\.[0-9]+)?)\s*(B|KB|MB|GB)\s*$", re.IGNORECASE)


def to_bytes(value: float, unit: str) -> float:
    u = unit.upper()
    if u == "B":
        return value
    if u == "KB":
        return value * 1024.0
    if u == "MB":
        return value * 1024.0 * 1024.0
    if u == "GB":
        return value * 1024.0 * 1024.0 * 1024.0
    raise ValueError(f"Unknown unit: {unit}")


def human_bytes(v: float) -> str:
    if v >= 1024.0 * 1024.0 * 1024.0:
        return f"{v / (1024.0 * 1024.0 * 1024.0):.1f} GB"
    if v >= 1024.0 * 1024.0:
        return f"{v / (1024.0 * 1024.0):.1f} MB"
    if v >= 1024.0:
        return f"{v / 1024.0:.1f} KB"
    return f"{v:.0f} B"


def parse_allocation_table(path: Path, targets: list[str]) -> tuple[np.ndarray, dict[str, np.ndarray]]:
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()

    start = -1
    for i, line in enumerate(lines):
        if "Median allocated bytes per validation call:" in line:
            start = i
            break
    if start < 0:
        raise ValueError("Allocation section header not found.")

    header_idx = -1
    columns: list[str] = []
    for i in range(start + 1, min(len(lines), start + 50)):
        line = lines[i]
        if "Length" in line and "|" in line and "FILE" in line:
            header_idx = i
            columns = [c.strip() for c in line.split("|")]
            break
    if header_idx < 0:
        raise ValueError("Allocation table header row not found.")

    selected = [t for t in targets if t in columns]
    if not selected:
        raise ValueError(f"No selected targets found in allocation table. selected={targets}, columns={columns}")
    target_indices = {t: columns.index(t) for t in selected}

    length_to_values: dict[str, dict[int, list[float]]] = {t: {} for t in selected}
    seen_separator = False
    for i in range(header_idx + 1, len(lines)):
        line = lines[i]
        if SEP_RE.match(line):
            seen_separator = True
            continue
        if not seen_separator:
            continue
        if not line.strip():
            continue
        if line.lstrip().startswith("Total files:"):
            break
        if "|" not in line:
            continue
        parts = [p.strip() for p in line.split("|")]
        try:
            length = int(parts[0])
        except ValueError:
            continue
        for target, idx in target_indices.items():
            if len(parts) <= idx:
                continue
            cell = parts[idx]
            m = VALUE_RE.match(cell)
            if not m:
                continue
            value = float(m.group(1))
            unit = m.group(2)
            b = to_bytes(value, unit)
            length_to_values[target].setdefault(length, []).append(b)

    if not any(length_to_values[t] for t in selected):
        raise ValueError("No allocation rows parsed from allocation table.")

    all_lengths = set()
    for t in selected:
        all_lengths.update(length_to_values[t].keys())
    lengths = np.array(sorted(all_lengths), dtype=float)

    series: dict[str, np.ndarray] = {}
    for t in selected:
        values = []
        by_len = length_to_values[t]
        for x in lengths:
            arr = by_len.get(int(x))
            if arr:
                values.append(float(np.median(arr)))
            else:
                values.append(np.nan)
        series[t] = np.array(values, dtype=float)

    return lengths, series


def style_ax(ax, title: str, xlabel: str, ylabel: str) -> None:
    ax.set_facecolor(BG)
    ax.set_title(title, color=TEXT, fontsize=13, pad=10, fontweight="bold")
    ax.set_xlabel(xlabel, color=TEXT, fontsize=10)
    ax.set_ylabel(ylabel, color=TEXT, fontsize=10)
    ax.tick_params(colors=TEXT, labelsize=8)
    ax.grid(True, color=GRID, linestyle="--", linewidth=0.5, alpha=0.7)
    for spine in ax.spines.values():
        spine.set_color(GRID)
    for label in ax.get_xticklabels() + ax.get_yticklabels():
        label.set_color(TEXT)


def resolve_line_style(n: int) -> tuple[float, float]:
    if n >= 3000:
        return 0.35, 0.70
    if n >= 1500:
        return 0.50, 0.75
    if n >= 800:
        return 0.70, 0.80
    if n >= 300:
        return 0.90, 0.90
    return 1.5, 1.0


def plot_memory_alloc(x: np.ndarray, series: dict[str, np.ndarray], output: Path) -> None:
    fig = plt.figure(figsize=(13, 6), dpi=150)
    fig.patch.set_facecolor(BG)
    ax = fig.add_subplot(1, 1, 1)

    lw, la = resolve_line_style(len(x))
    step = max(1, len(x) // 150)
    ymax = 0.0
    ordered_targets = sorted(series.keys(), key=key_sort)
    colors = build_series_colors(ordered_targets)
    for target in ordered_targets:
        y = series[target]
        color = resolve_color(target, colors)
        finite = np.isfinite(y)
        if not finite.any():
            continue
        ymax = max(ymax, float(np.nanmax(y)))
        ax.plot(
            x,
            y,
            color=color,
            linewidth=lw,
            alpha=la,
            marker="o",
            markersize=3,
            markerfacecolor=color,
            markeredgewidth=0,
            markevery=step,
            label=target,
            zorder=3,
        )

        idxs = np.where(finite)[0]
        idx = int(idxs[-1])
        ax.annotate(
            human_bytes(float(y[idx])),
            xy=(x[idx], y[idx]),
            xytext=(5, 0),
            textcoords="offset points",
            color=color,
            fontsize=7,
            va="center",
        )

    style_ax(
        ax,
        "Memory Allocation by JSON Length (Median by length)",
        "JSON Length (chars)",
        "Allocated Bytes",
    )
    ax.set_xlim(0.0, float(np.max(x)))
    ax.set_ylim(0.0, ymax * 1.02 if ymax > 0 else 1.0)

    ax.xaxis.set_major_formatter(ticker.FuncFormatter(lambda v, _: f"{int(v):,}"))
    ax.yaxis.set_major_formatter(ticker.FuncFormatter(lambda v, _: human_bytes(float(v))))
    ax.legend(facecolor="#313244", edgecolor=GRID, labelcolor=TEXT, fontsize=9, loc="upper left")

    fig.tight_layout()
    output.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(output, facecolor=BG)
    plt.close(fig)


def main() -> None:
    parser = argparse.ArgumentParser(description="Plot memory allocation graph from bench_output allocation table.")
    parser.add_argument("--input", required=True, help="Path to bench_output.txt")
    parser.add_argument(
        "--targets",
        default="Jackson,AlwaysEscape,Validason",
        help="Comma-separated target column names in allocation table",
    )
    parser.add_argument("--output", default="docs/graphs/memory_alloc_from_speed_case01_r5.png", help="Output PNG path")
    args = parser.parse_args()

    src = Path(args.input)
    out = Path(args.output)
    targets = [t.strip() for t in args.targets.split(",") if t.strip()]

    x, series = parse_allocation_table(src, targets)
    plot_memory_alloc(x, series, out)

    print(f"parsed_points={len(x)}")
    print(f"targets={','.join(series.keys())}")
    max_alloc = max(float(np.nanmax(y)) for y in series.values() if np.isfinite(y).any())
    print(f"max_alloc_bytes={max_alloc:.6f}")
    print(f"saved={out}")


if __name__ == "__main__":
    main()
