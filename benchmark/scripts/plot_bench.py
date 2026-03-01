"""
JSON Validation Benchmark Plotter

Reads bench_output_<string>.txt files and generates PNG charts directly.

Examples:
  python bench.py full 1000 130
  python bench.py

Requirements:
  pip install matplotlib numpy

Input:
  bench_output_<string>.txt (same directory as this script)
Output:
  bench_full_<string>/bench_full_<string>.png
  bench_full_<string>/bench_full_<string>_funcA.png    (per-function, when 2+)
  bench_<limit>_<string>/bench_<limit>_<string>.png
  bench_<limit>_<string>/bench_<limit>_<string>_funcA.png
"""

import argparse
import re
import sys
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker
import numpy as np


# ── Colors ────────────────────────────────────────────────────────────────────

BG = "#1e1e2e"
GRID = "#313244"
TEXT = "#cdd6f4"

FIXED_TARGET_COLORS = {
    "jackson": "#f38ba8",
    "jacksoncanonoff": "#f38ba8",
    "alwaysescape": "#bd93f9",
    "validason": "#94e2d5",
    "jacksondefaultcanonon": "#f9e2af",
    "jacksonreadtree": "#4dabf7",
}

BASE_COLORS = {
    "Gatling": "#a6e3a1",
    "Lightweight": "#89dceb",
    "Strict": "#fab387",
}

FALLBACK_COLORS = [
    "#fab387", "#89b4fa", "#f2cdcd", "#a6adc8", "#f5c2e7",
    "#74c7ec", "#f7d794", "#7dd3fc", "#f4a261", "#84cc16",
]

# ── Parsing patterns ──────────────────────────────────────────────────────────

ROW_START_PATTERN      = re.compile(r"^\s*(\d+)\s*\|\s*\d+\s*\|\s*\d+\s*\|")
FUNCTIONS_LINE_PATTERN = re.compile(r"^\s*Functions:\s*(.+?)\s*$")
TABLE_HEADER_PATTERN   = re.compile(r"^\s*Length\s*\|.+\|\s*Best\s*$")
SEPARATOR_PATTERN      = re.compile(r"^\s*[-=]{5,}\s*$")

TIME_VALUE_PATTERN     = re.compile(r"([\d.]+)\s*(ms|us)")

VALIDATION_FUNC_PATTERN = re.compile(r"^\s*Validation Funcs?\s*:\s*(.+?)\s*$", re.IGNORECASE)
SINGLE_TIME_HEADER_PATTERN = re.compile(
    r"^\s*Length\s*\|\s*Time\s*\|\s*FILE\s*$", re.IGNORECASE
)
SINGLE_TIME_ROW_PATTERN = re.compile(
    r"^\s*(\d+)\s*\|\s*([\d.]+)\s*(ms|us)\s*\|"
)

MULTI_COL_HEADER_PATTERN = re.compile(
    r"^\s*Length\s*\|(?:\s*(?!FILE\s*$)[^|]+\|)+\s*FILE\s*$", re.IGNORECASE
)
MULTI_COL_ROW_PATTERN = re.compile(r"^\s*(\d+)\s*\|")

INPUT_PREFIX = "bench_output_"


# ── File selection / naming utilities ─────────────────────────────────────────

def extract_suffix(path: Path) -> str:
    stem = path.stem
    return stem[len(INPUT_PREFIX):] if stem.startswith(INPUT_PREFIX) else ""


def make_output_name(range_label, suffix: str, height_label: str = "") -> str:
    base = f"bench_{range_label}"
    name = f"{base}_{suffix}" if suffix else base
    if height_label:
        name = f"{name}_{height_label}"
    return name


def save_figure(fig, out_dir: Path, filename: str):
    """Create out_dir if needed and save a PNG inside it."""
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / filename
    fig.savefig(out_path, dpi=150, bbox_inches="tight", facecolor=BG)
    print(f"{out_path} saved")


def select_input_files(base_dir: Path) -> list:
    candidates = sorted(
        p for p in base_dir.iterdir()
        if p.is_file()
        and p.stem.startswith(INPUT_PREFIX)
        and p.suffix.lower() == ".txt"
    )

    if not candidates:
        print(f"ERROR: no {INPUT_PREFIX}*.txt file found under '{base_dir}'.")
        sys.exit(1)

    if len(candidates) == 1:
        return candidates

    print("Select input file(s):")
    print("  0. All")
    for idx, p in enumerate(candidates, 1):
        print(f"  {idx}. {p.name}")
    print("  (multiple selection: comma or space separated, e.g. 1,3 or 1 3)")

    while True:
        try:
            raw = input(f"Enter number(s) (0-{len(candidates)}): ").strip()
        except EOFError:
            print("\nERROR: input canceled.")
            sys.exit(1)

        tokens = [t for t in re.split(r"[,\s]+", raw) if t]
        if not tokens:
            print(f"Please enter a number between 0 and {len(candidates)}.")
            continue

        if tokens == ["0"]:
            return candidates

        if all(t.isdigit() for t in tokens):
            nums = [int(t) for t in tokens]
            if all(1 <= n <= len(candidates) for n in nums):
                seen, result = set(), []
                for n in nums:
                    if n not in seen:
                        seen.add(n)
                        result.append(candidates[n - 1])
                return result

        print(f"Please enter numbers between 0 and {len(candidates)}.")


# ── Parsing ───────────────────────────────────────────────────────────────────

def read_lines(path: Path):
    raw = path.read_bytes()

    # PowerShell redirection often creates UTF-16 text; detect it early.
    encoding_candidates = []
    if raw.startswith((b"\xff\xfe", b"\xfe\xff")):
        encoding_candidates.extend(["utf-16", "utf-16-le", "utf-16-be"])

    encoding_candidates.extend(["utf-8-sig", "utf-8", "cp949"])

    # If null bytes are present, UTF-16 is still likely even without BOM.
    if b"\x00" in raw:
        encoding_candidates.extend(["utf-16", "utf-16-le", "utf-16-be"])

    encoding_candidates.append("latin-1")

    tried = set()
    for enc in encoding_candidates:
        if enc in tried:
            continue
        tried.add(enc)
        try:
            return raw.decode(enc).splitlines(keepends=True)
        except UnicodeDecodeError:
            continue
    return None


def parse_functions_from_table_header(line: str):
    cols = [c.strip() for c in line.split("|")]
    if len(cols) < 5:
        return []
    if cols[0].lower() != "length" or cols[1].lower() != "files" or cols[2].lower() != "iter":
        return []
    if cols[-1].lower() != "best":
        return []
    return [c for c in cols[3:-1] if c]


def average_by_length(x: np.ndarray, data: dict) -> tuple:
    unique_lengths = np.unique(x)
    if len(unique_lengths) == len(x):
        return x, data

    averaged = {}
    for col, vals in data.items():
        avg_vals = np.array(
            [np.nanmedian(vals[x == length]) for length in unique_lengths],
            dtype=float,
        )
        averaged[col] = avg_vals

    n_merged = len(x) - len(unique_lengths)
    print(f"  (merged {n_merged} duplicate lengths via median -> {len(unique_lengths)} points)")
    return unique_lengths, averaged


def detect_format(lines) -> str:
    for line in lines:
        stripped = line.strip()
        if SINGLE_TIME_HEADER_PATTERN.match(stripped):
            return "single"
        if MULTI_COL_HEADER_PATTERN.match(stripped):
            return "multi_col"
        if TABLE_HEADER_PATTERN.match(stripped):
            return "multi"
        if FUNCTIONS_LINE_PATTERN.match(stripped):
            return "multi"
    return "multi"


def _to_us(value: float, unit: str) -> float:
    return value * 1000.0 if unit.lower() == "ms" else value


def parse_benchmark_data(lines):
    fmt = detect_format(lines)
    if fmt == "single":
        return _parse_single_func(lines)
    if fmt == "multi_col":
        return _parse_multi_col(lines)
    return _parse_multi_func(lines)


def _parse_single_func(lines):
    func_name = "unknown"
    raw_lengths: list = []
    raw_values: list  = []

    for line in lines:
        stripped = line.strip()
        vf_match = VALIDATION_FUNC_PATTERN.match(stripped)
        if vf_match:
            func_name = vf_match.group(1)
            continue
        row_match = SINGLE_TIME_ROW_PATTERN.match(line)
        if row_match:
            raw_lengths.append(float(row_match.group(1)))
            raw_values.append(_to_us(float(row_match.group(2)), row_match.group(3)))

    if not raw_lengths:
        raise ValueError("No benchmark rows found in the file.")

    x    = np.array(raw_lengths, dtype=float)
    vals = np.array(raw_values,  dtype=float)
    order = np.argsort(x)
    x, data = average_by_length(x[order], {func_name: vals[order]})

    print(f"Parsed {len(x)} data points ({int(x.min())} ~ {int(x.max())} chars)")
    print(f"Functions: {func_name}  [JsonValidator format]")
    return x, data


def _parse_multi_col(lines):
    func_names: list = []
    raw_lengths: list = []
    raw_values: dict = {}

    def ensure_funcs(names):
        for n in names:
            if n not in raw_values:
                func_names.append(n)
                raw_values[n] = []

    for line in lines:
        stripped = line.strip()
        vf_match = VALIDATION_FUNC_PATTERN.match(stripped)
        if vf_match:
            names = [n.strip() for n in vf_match.group(1).split(",") if n.strip()]
            ensure_funcs(names)
            continue
        if MULTI_COL_HEADER_PATTERN.match(stripped):
            cols = [c.strip() for c in stripped.split("|")]
            ensure_funcs([c for c in cols[1:-1] if c])
            continue
        if not MULTI_COL_ROW_PATTERN.match(line):
            continue
        cols = [c.strip() for c in line.split("|")]
        if len(cols) < 3:
            continue
        try:
            length = int(cols[0].strip())
        except ValueError:
            continue
        value_cols = cols[1:-1]
        parsed_vals = []
        for col_str in value_cols:
            m = TIME_VALUE_PATTERN.search(col_str)
            parsed_vals.append(_to_us(float(m.group(1)), m.group(2)) if m else None)
        if not any(v is not None for v in parsed_vals):
            continue
        if func_names and len(parsed_vals) == len(func_names):
            raw_lengths.append(float(length))
            for fn, val in zip(func_names, parsed_vals):
                raw_values[fn].append(val if val is not None else float("nan"))

    if not raw_lengths:
        raise ValueError("No benchmark rows found in the file.")

    x    = np.array(raw_lengths, dtype=float)
    data = {fn: np.array(raw_values[fn], dtype=float) for fn in func_names}
    order = np.argsort(x)
    x     = x[order]
    data  = {fn: v[order] for fn, v in data.items()}
    data  = {fn: v for fn, v in data.items() if np.isfinite(v).any()}
    x, data = average_by_length(x, data)

    print(f"Parsed {len(x)} data points ({int(x.min())} ~ {int(x.max())} chars)")
    print(f"Functions: {', '.join(data.keys())}  [JsonValidator multi-col format]")
    return x, data


def _parse_multi_func(lines):
    raw_lengths = []
    raw_values  = {}
    columns     = []
    current_functions = []
    i = 0

    def ensure_columns(names):
        for n in names:
            if n not in columns:
                columns.append(n)
                raw_values[n] = []

    while i < len(lines):
        raw      = lines[i].rstrip("\n")
        stripped = raw.strip()

        func_match = FUNCTIONS_LINE_PATTERN.match(stripped)
        if func_match:
            current_functions = [n.strip() for n in func_match.group(1).split(",") if n.strip()]
            ensure_columns(current_functions)
            i += 1
            continue

        if TABLE_HEADER_PATTERN.match(stripped):
            hf = parse_functions_from_table_header(stripped)
            if hf:
                current_functions = hf
                ensure_columns(current_functions)
            i += 1
            continue

        row_match = ROW_START_PATTERN.match(raw)
        if not row_match:
            i += 1
            continue

        length   = int(row_match.group(1))
        merged   = raw
        expected = len(current_functions)
        pairs    = TIME_VALUE_PATTERN.findall(merged)

        if expected > 0:
            while len(pairs) < expected and i + 1 < len(lines):
                nxt         = lines[i + 1].rstrip("\n")
                nxt_stripped = nxt.strip()
                if not nxt_stripped:
                    break
                if (ROW_START_PATTERN.match(nxt)
                        or TABLE_HEADER_PATTERN.match(nxt_stripped)
                        or SEPARATOR_PATTERN.match(nxt_stripped)):
                    break
                merged = f"{merged} {nxt_stripped}"
                i += 1
                pairs = TIME_VALUE_PATTERN.findall(merged)

            if len(pairs) < expected:
                i += 1
                continue

            pairs     = pairs[:expected]
            row_funcs = current_functions
        else:
            pairs = TIME_VALUE_PATTERN.findall(merged)
            if not pairs:
                i += 1
                continue
            row_funcs = [f"value{j + 1}" for j in range(len(pairs))]
            ensure_columns(row_funcs)

        raw_lengths.append(length)
        for col in columns:
            if col in row_funcs:
                idx_in_row = row_funcs.index(col)
                val_str, unit = pairs[idx_in_row]
                raw_values[col].append(_to_us(float(val_str), unit))
            else:
                raw_values[col].append(float("nan"))
        i += 1

    if not raw_lengths:
        raise ValueError("No benchmark rows found in the file.")

    x    = np.array(raw_lengths, dtype=float)
    data = {col: np.array(raw_values[col], dtype=float) for col in columns}
    order = np.argsort(x)
    x     = x[order]
    data  = {col: v[order] for col, v in data.items()}
    data  = {col: v for col, v in data.items() if np.isfinite(v).any()}

    if not data:
        raise ValueError("No valid numeric function data found.")

    x, data = average_by_length(x, data)

    print(f"Parsed {len(x)} data points ({int(x.min())} ~ {int(x.max())} chars)")
    print(f"Functions: {', '.join(data.keys())}")
    return x, data


# ── Plotting utilities ────────────────────────────────────────────────────────

def build_colors(names):
    colors, idx = {}, 0
    for name in names:
        normalized = re.sub(r"[^a-z0-9]", "", name.lower())
        if normalized in FIXED_TARGET_COLORS:
            colors[name] = FIXED_TARGET_COLORS[normalized]
        elif name in BASE_COLORS:
            colors[name] = BASE_COLORS[name]
        else:
            colors[name] = FALLBACK_COLORS[idx % len(FALLBACK_COLORS)]
            idx += 1
    return colors


def style_ax(ax, title, xlabel, ylabel):
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


def resolve_line_style(n):
    if n >= 3000: return 0.35, 0.70
    if n >= 1500: return 0.50, 0.75
    if n >= 800:  return 0.70, 0.80
    if n >= 300:  return 0.90, 0.90
    return 1.5, 1.0


def plot_lines(ax, x, data_dict, colors, marker=None, ms=3):
    lw, la = resolve_line_style(len(x))
    step    = max(1, len(x) // 150)
    for name, vals in data_dict.items():
        if not np.isfinite(vals).any():
            continue
        kw = dict(color=colors[name], linewidth=lw, alpha=la, label=name, zorder=3)
        if marker:
            kw.update(marker=marker, markersize=ms,
                      markerfacecolor=colors[name], markeredgewidth=0, markevery=step)
        ax.plot(x, vals, **kw)


def add_endpoint_labels(ax, x, data_dict, colors):
    for name, vals in data_dict.items():
        fi = np.where(np.isfinite(vals))[0]
        if not len(fi):
            continue
        idx = int(fi[-1])
        ax.annotate(
            f"{vals[idx]:.1f}\u00b5s",
            xy=(x[idx], vals[idx]), xytext=(5, 0),
            textcoords="offset points",
            color=colors[name], fontsize=7, va="center",
        )


def _make_figure(x, data_dict, colors, title, xlabel, ylabel, xlim_right, y_max, xticks_data):
    fig, ax = plt.subplots(figsize=(13, 6))
    fig.patch.set_facecolor(BG)
    plot_lines(ax, x, data_dict, colors, marker="o", ms=3)
    add_endpoint_labels(ax, x, data_dict, colors)
    style_ax(ax, title, xlabel, ylabel)
    ax.set_xlim(left=0, right=xlim_right)
    if y_max is not None:
        ax.set_ylim(bottom=0, top=y_max)
    ax.xaxis.set_major_formatter(ticker.FuncFormatter(lambda v, _: f"{int(v):,}"))
    if len(xticks_data) <= 30:
        ax.set_xticks(xticks_data)
        ax.tick_params(axis="x", rotation=90)
    ax.legend(facecolor="#313244", edgecolor=GRID, labelcolor=TEXT, fontsize=9, loc="upper left")
    plt.tight_layout()
    return fig


def _save_combined_and_singles(
        x, data_dict, colors,
        title_template: str,
        xlabel, ylabel,
        xlim_right, y_max,
        xticks_data,
        base_name: str,
        base_dir: Path,
):
    out_dir = base_dir / base_name
    all_names = ", ".join(data_dict.keys())
    title     = title_template.format(funcs=all_names)
    fig = _make_figure(x, data_dict, colors, title, xlabel, ylabel,
                       xlim_right, y_max, xticks_data)
    save_figure(fig, out_dir, f"{base_name}.png")
    plt.close(fig)

    if len(data_dict) >= 2:
        for func_name, vals in data_dict.items():
            single_data = {func_name: vals}
            single_title = title_template.format(funcs=func_name)
            fig = _make_figure(x, single_data, colors, single_title, xlabel, ylabel,
                               xlim_right, y_max, xticks_data)
            safe_name = re.sub(r'[\\/:*?"<>|]', "_", func_name)
            save_figure(fig, out_dir, f"{base_name}_{safe_name}.png")
            plt.close(fig)


# ── Plot generation ───────────────────────────────────────────────────────────

def plot_full(lengths, funcs, colors, suffix="", y_max=None, height_label="",
              base_dir: Path = Path(".")):
    max_chars = int(lengths.max())
    base_name = make_output_name(max_chars, suffix, height_label)
    title_tpl = (
        f"Valid JSON Benchmark - Full Range ({int(lengths.min()):,}-{max_chars:,} chars)"
        f" (Linear Scale) — {{funcs}}"
    )
    _save_combined_and_singles(
        lengths, funcs, colors,
        title_tpl,
        "JSON Length (chars)", "Time (\u00b5s)",
        max_chars, y_max,
        lengths,
        base_name, base_dir,
    )


def plot_upto(lengths, funcs, colors, max_chars, suffix="", y_max=None, height_label="",
              base_dir: Path = Path(".")):
    mask = lengths <= max_chars
    base_name = make_output_name(max_chars, suffix, height_label)

    if not np.any(mask):
        print(f"Skipped {base_name}: no data points <= {max_chars}")
        return

    x    = lengths[mask]
    data = {k: v[mask] for k, v in funcs.items()}
    if not any(np.isfinite(v).any() for v in data.values()):
        print(f"Skipped {base_name}: no valid values <= {max_chars}")
        return

    title_tpl = (
        f"Valid JSON Benchmark - Up to {max_chars:,} chars (Linear Scale) — {{funcs}}"
    )
    _save_combined_and_singles(
        x, data, colors,
        title_tpl,
        "JSON Length (chars)", "Time (\u00b5s)",
        max_chars, y_max,
        x,
        base_name, base_dir,
    )


# ── Range argument handling ───────────────────────────────────────────────────

def parse_requested_ranges(raw_values):
    parsed = []
    for raw in raw_values:
        token = raw.strip().lower()
        if not token:
            continue
        if token == "full":
            parsed.append("full")
            continue
        try:
            value = int(token)
        except ValueError as exc:
            raise ValueError(f"Invalid range '{raw}'. Use positive integers or 'full'.") from exc
        if value <= 0:
            raise ValueError(f"Range must be > 0: {value}")
        parsed.append(value)
    return list(dict.fromkeys(parsed))


def parse_cli_args():
    parser = argparse.ArgumentParser(description="Generate benchmark plots.")
    parser.add_argument("ranges", nargs="*", help="e.g. full 1000 130")
    parser.add_argument(
        "--height",
        type=float,
        default=None,
        metavar="MS",
        help="Fix the y-axis ceiling to this value in ms (e.g. --height=1000).",
    )
    parser.add_argument(
        "--input",
        action="append",
        default=[],
        help="Input benchmark file path (repeatable).",
    )
    parser.add_argument(
        "--inputs",
        default="",
        help="Comma-separated input benchmark file paths.",
    )
    parser.add_argument(
        "--non-interactive",
        action="store_true",
        help="Disable all interactive prompts. Requires --input/--inputs or defaults ranges to 'full'.",
    )
    parser.add_argument(
        "--suffix",
        default="",
        help="Override output suffix used in generated folder/file names.",
    )
    return parser.parse_args()


def resolve_input_paths(args, base_dir: Path):
    raw_paths = list(args.input or [])
    if args.inputs:
        raw_paths.extend([p.strip() for p in args.inputs.split(",") if p.strip()])

    if raw_paths:
        resolved = []
        seen = set()
        for raw in raw_paths:
            cand = Path(raw)
            candidates = [cand]
            if not cand.is_absolute():
                candidates.append(base_dir / cand)
            picked = None
            for c in candidates:
                if c.is_file():
                    picked = c.resolve()
                    break
            if picked is None:
                print(f"ERROR: input file not found: {raw}")
                sys.exit(1)
            key = str(picked)
            if key not in seen:
                seen.add(key)
                resolved.append(picked)
        return resolved

    if args.non_interactive:
        print("ERROR: --non-interactive requires --input/--inputs.")
        sys.exit(1)

    return select_input_files(base_dir)


def collect_requested_ranges(args):
    y_max_us = args.height * 1000.0 if args.height is not None else None

    if args.ranges:
        return parse_requested_ranges(args.ranges), y_max_us

    if args.non_interactive:
        return ["full"], y_max_us

    user_input = input("Enter ranges (example: full,1000,130): ").strip()
    if not user_input:
        raise ValueError("No ranges provided.")
    return parse_requested_ranges(user_input.replace(",", " ").split()), y_max_us


# ── Entry point ───────────────────────────────────────────────────────────────

def load_file(input_path: Path):
    lines = read_lines(input_path)
    if lines is None:
        print(f"ERROR: failed to read file: {input_path}")
        return None
    suffix         = extract_suffix(input_path)
    lengths, funcs = parse_benchmark_data(lines)
    return suffix, lengths, funcs


def merge_datasets(datasets: list) -> tuple:
    from collections import defaultdict

    accumulator = defaultdict(lambda: defaultdict(list))
    for lengths, funcs in datasets:
        for func_name, vals in funcs.items():
            for length, val in zip(lengths, vals):
                if np.isfinite(val):
                    accumulator[func_name][float(length)].append(val)

    all_lengths_set: set = set()
    for length_map in accumulator.values():
        all_lengths_set.update(length_map.keys())
    all_lengths = np.array(sorted(all_lengths_set), dtype=float)

    merged_funcs: dict = {}
    for func_name, length_map in accumulator.items():
        vals = np.array(
            [np.median(length_map[l]) if l in length_map else np.nan for l in all_lengths],
            dtype=float,
        )
        merged_funcs[func_name] = vals

    merged_funcs = {k: v for k, v in merged_funcs.items() if np.isfinite(v).any()}
    return all_lengths, merged_funcs


def process_files(input_paths: list, ranges: list, y_max=None, height_label="", suffix_override=""):
    base_dir = Path(__file__).resolve().parent / "bench_result"

    datasets = []
    suffixes = []

    for input_path in input_paths:
        result = load_file(input_path)
        if result is None:
            continue
        suffix, lengths, funcs = result
        datasets.append((lengths, funcs))
        suffixes.append(suffix)
        print(f"  Loaded: {input_path.name}")

    if not datasets:
        print("ERROR: no valid data loaded.")
        return

    if len(datasets) == 1:
        lengths, funcs = datasets[0]
        suffix = suffixes[0]
    else:
        print(f"\nMerging {len(datasets)} files...")
        lengths, funcs = merge_datasets(datasets)
        suffix = "+".join(s for s in suffixes if s) or "merged"
        print(f"  Merged: {len(lengths)} unique lengths, "
              f"functions: {', '.join(funcs.keys())}")

    if suffix_override:
        suffix = suffix_override

    colors = build_colors(list(funcs.keys()))

    for requested in ranges:
        if requested == "full":
            plot_full(lengths, funcs, colors, suffix, y_max=y_max,
                      height_label=height_label, base_dir=base_dir)
        else:
            plot_upto(lengths, funcs, colors, requested, suffix, y_max=y_max,
                      height_label=height_label, base_dir=base_dir)


def main():
    args = parse_cli_args()
    base_dir    = Path(__file__).resolve().parent / "bench_result"
    input_paths = resolve_input_paths(args, base_dir)
    ranges, y_max = collect_requested_ranges(args)
    height_label = f"h={int(y_max / 1000)}" if y_max is not None else ""
    process_files(
        input_paths,
        ranges,
        y_max=y_max,
        height_label=height_label,
        suffix_override=args.suffix.strip(),
    )


if __name__ == "__main__":
    main()
