#!/usr/bin/env python3
"""Generate valid random JSON samples by exact length.

This script is aligned with RandomValidJsonGenerator.cpp behavior.
"""

import argparse
import os
import random
import string
import sys
from pathlib import Path


STR_CHARS = string.ascii_letters + string.digits + " _-+!@#%^&*(),.;:?<>"
KEY_CHARS = string.ascii_lowercase + string.digits


class JsonValidator:
    MAX_DEPTH = 1024

    def validate(self, text: str) -> bool:
        ok, idx = self._parse_value(text, 0, 0)
        return ok and idx == len(text)

    def _parse_value(self, s: str, i: int, depth: int):
        if depth > self.MAX_DEPTH or i >= len(s):
            return False, i

        ch = s[i]
        if ch == "n":
            return self._consume_literal(s, i, "null")
        if ch == "t":
            return self._consume_literal(s, i, "true")
        if ch == "f":
            return self._consume_literal(s, i, "false")
        if ch == '"':
            return self._parse_string(s, i)
        if ch == "[":
            return self._parse_array(s, i, depth + 1)
        if ch == "{":
            return self._parse_object(s, i, depth + 1)
        return self._parse_number(s, i)

    @staticmethod
    def _consume_literal(s: str, i: int, literal: str):
        if s.startswith(literal, i):
            return True, i + len(literal)
        return False, i

    @staticmethod
    def _parse_string(s: str, i: int):
        if i >= len(s) or s[i] != '"':
            return False, i
        i += 1

        while i < len(s):
            ch = s[i]
            i += 1

            if ch == '"':
                return True, i
            if ord(ch) < 0x20:
                return False, i
            if ch == "\\":
                if i >= len(s):
                    return False, i
                esc = s[i]
                i += 1
                if esc in ['"', "\\", "/", "b", "f", "n", "r", "t"]:
                    continue
                if esc == "u":
                    for _ in range(4):
                        if i >= len(s):
                            return False, i
                        if s[i] not in "0123456789abcdefABCDEF":
                            return False, i
                        i += 1
                    continue
                return False, i

        return False, i

    @staticmethod
    def _parse_number(s: str, i: int):
        n = len(s)
        start = i

        if i < n and s[i] == "-":
            i += 1
        if i >= n:
            return False, i

        if s[i] == "0":
            i += 1
        else:
            if not s[i].isdigit():
                return False, i
            while i < n and s[i].isdigit():
                i += 1

        if i < n and s[i] == ".":
            i += 1
            if i >= n or not s[i].isdigit():
                return False, i
            while i < n and s[i].isdigit():
                i += 1

        if i < n and s[i] in ("e", "E"):
            i += 1
            if i < n and s[i] in ("+", "-"):
                i += 1
            if i >= n or not s[i].isdigit():
                return False, i
            while i < n and s[i].isdigit():
                i += 1

        return i > start, i

    def _parse_array(self, s: str, i: int, depth: int):
        if i >= len(s) or s[i] != "[":
            return False, i
        i += 1
        if i < len(s) and s[i] == "]":
            return True, i + 1

        while True:
            ok, i = self._parse_value(s, i, depth)
            if not ok:
                return False, i
            if i >= len(s):
                return False, i
            ch = s[i]
            i += 1
            if ch == "]":
                return True, i
            if ch != ",":
                return False, i

    def _parse_object(self, s: str, i: int, depth: int):
        if i >= len(s) or s[i] != "{":
            return False, i
        i += 1
        if i < len(s) and s[i] == "}":
            return True, i + 1

        while True:
            if i >= len(s) or s[i] != '"':
                return False, i
            ok, i = self._parse_string(s, i)
            if not ok:
                return False, i
            if i >= len(s) or s[i] != ":":
                return False, i
            i += 1

            ok, i = self._parse_value(s, i, depth)
            if not ok:
                return False, i
            if i >= len(s):
                return False, i

            ch = s[i]
            i += 1
            if ch == "}":
                return True, i
            if ch != ",":
                return False, i


class Generator:
    def __init__(self):
        self.rng = random.Random()
        self.validator = JsonValidator()

    def generate_value(self, target_len: int, depth: int = 0, max_depth: int = 8) -> str:
        candidates = []
        weights = []

        if target_len == 4:
            candidates += ["null", "true"]
            weights += [1, 1]
        if target_len == 5:
            candidates.append("false")
            weights.append(1)
        if target_len >= 1:
            candidates.append("number")
            weights.append(2)
        if target_len >= 2:
            candidates.append("string")
            weights.append(3 if target_len < 10 else 2)
        if depth < max_depth and (target_len == 2 or target_len >= 3):
            candidates.append("array")
            weights.append(6 if target_len >= 10 else 3)
        if depth < max_depth and (target_len == 2 or target_len >= 6):
            candidates.append("object")
            weights.append(6 if target_len >= 10 else 3)

        if not candidates:
            raise ValueError("target_len has no candidates")

        choice = self.rng.choices(candidates, weights=weights, k=1)[0]
        if choice == "null":
            return "null"
        if choice == "true":
            return "true"
        if choice == "false":
            return "false"
        if choice == "number":
            return self.generate_number(target_len)
        if choice == "string":
            return self.generate_string(target_len)
        if choice == "array":
            return self.generate_array(target_len, depth)
        if choice == "object":
            return self.generate_object(target_len, depth)
        raise AssertionError("unknown value choice")

    def generate_top_level(self, target_len: int) -> str:
        if self.rng.randint(1, 10) <= 7:
            return self.generate_object(target_len)
        return self.generate_array(target_len)

    def generate_object(self, target_len: int, depth: int = 0) -> str:
        if target_len == 2:
            return "{}"

        inner = target_len - 2
        max_n = (inner + 1) // 5
        if max_n < 1:
            return "{}"

        upper = min(max_n, max(1, inner // 10))
        n = self.rng.randint(1, upper)

        field_budget = inner - (n - 1)
        field_lengths = self.distribute(field_budget, n, 4)

        out = ["{"]
        used_keys = set()

        for idx, field_length in enumerate(field_lengths):
            max_key = min(field_length - 2, 10)
            key_len = self.rng.randint(2, max(2, max_key))
            val_len = field_length - 1 - key_len

            key = None
            for _ in range(20):
                candidate = self.generate_key_string(key_len)
                if candidate not in used_keys:
                    key = candidate
                    break
            if key is None:
                key = self.generate_key_string(key_len)
            used_keys.add(key)

            if idx > 0:
                out.append(",")
            out.append(key)
            out.append(":")
            out.append(self.generate_value(val_len, depth + 1))

        out.append("}")
        return "".join(out)

    def generate_number(self, target_len: int) -> str:
        if target_len == 1:
            return str(self.rng.randint(0, 9))

        options = ["int"]
        weights = [3]
        if target_len >= 2:
            options.append("neg_int")
            weights.append(1)
        if target_len >= 3:
            options.append("float")
            weights.append(2)
        if target_len >= 4:
            options.append("neg_float")
            weights.append(1)

        choice = self.rng.choices(options, weights=weights, k=1)[0]
        if choice == "int":
            return self.gen_pos_int(target_len)
        if choice == "neg_int":
            return "-" + self.gen_pos_int(target_len - 1)
        if choice == "float":
            int_len = self.rng.randint(1, target_len - 2)
            return self.gen_pos_int(int_len) + "." + self.gen_frac(target_len - 1 - int_len)
        if choice == "neg_float":
            inner = target_len - 1
            int_len = self.rng.randint(1, inner - 2)
            return "-" + self.gen_pos_int(int_len) + "." + self.gen_frac(inner - 1 - int_len)
        raise AssertionError("unknown number choice")

    def generate_string(self, target_len: int) -> str:
        if target_len < 2:
            raise ValueError("target_len must be >= 2 for string")
        return '"' + "".join(self.rng.choice(STR_CHARS) for _ in range(target_len - 2)) + '"'

    def generate_key_string(self, target_len: int) -> str:
        if target_len < 2:
            raise ValueError("target_len must be >= 2 for key string")
        return '"' + "".join(self.rng.choice(KEY_CHARS) for _ in range(target_len - 2)) + '"'

    def gen_pos_int(self, length: int) -> str:
        if length <= 0:
            raise ValueError("length must be positive")
        if length == 1:
            return str(self.rng.randint(0, 9))
        return str(self.rng.randint(1, 9)) + "".join(str(self.rng.randint(0, 9)) for _ in range(length - 1))

    def gen_frac(self, length: int) -> str:
        if length <= 0:
            raise ValueError("length must be positive")
        if length == 1:
            return str(self.rng.randint(1, 9))
        return "".join(str(self.rng.randint(0, 9)) for _ in range(length - 1)) + str(self.rng.randint(1, 9))

    def distribute(self, total: int, n: int, min_val: int):
        if n <= 0:
            raise ValueError("n must be positive")
        if total < n * min_val:
            raise ValueError("total is too small")
        result = [min_val] * n
        remaining = total - n * min_val
        for _ in range(remaining):
            result[self.rng.randint(0, n - 1)] += 1
        self.rng.shuffle(result)
        return result

    def generate_array(self, target_len: int, depth: int = 0) -> str:
        if target_len == 2:
            return "[]"

        inner = target_len - 2
        max_n = (inner + 1) // 2
        upper = max_n
        n = self.rng.randint(1, upper)

        elem_budget = inner - (n - 1)
        lengths = self.distribute(elem_budget, n, 1)

        out = ["["]
        for idx, length in enumerate(lengths):
            if idx > 0:
                out.append(",")
            out.append(self.generate_value(length, depth + 1))
        out.append("]")
        return "".join(out)

    def is_valid_json(self, text: str) -> bool:
        return self.validator.validate(text)


def parse_args():
    parser = argparse.ArgumentParser(
        description=(
            "Generate random valid JSON samples and place them under "
            "benchmarks/random-valid-json-<N>x<M>x<K>."
        )
    )
    parser.add_argument("--max-length", type=int, help="Maximum JSON length N (N >= 7).")
    parser.add_argument("--count-per-length", type=int, help="Number of samples per length M (M >= 1).")
    parser.add_argument("--length-step", type=int, help="Step K between generated lengths (K >= 1).")
    parser.add_argument("--max-attempts", type=int, default=50, help="Max retries per sample (default: 50).")
    parser.add_argument(
        "--output-root",
        type=str,
        default=None,
        help="Output root. Default: script_dir if name is benchmarks, else script_dir/benchmarks.",
    )
    return parser.parse_args()


def prompt_int(prompt: str) -> int:
    while True:
        raw = input(prompt).strip()
        try:
            return int(raw)
        except ValueError:
            print(f"Invalid integer: {raw}")


def resolve_default_output_root(script_dir: Path) -> Path:
    if script_dir.name.lower() == "benchmarks":
        return script_dir
    return script_dir / "benchmarks"


def build_target_lengths(max_length: int, length_step: int):
    lengths = []
    current = 7
    while current <= max_length:
        lengths.append(current)
        if current > max_length - length_step:
            break
        current += length_step
    return lengths


def main():
    args = parse_args()

    max_length = args.max_length if args.max_length is not None else prompt_int("N (max length, >= 7): ")
    count_per_length = (
        args.count_per_length if args.count_per_length is not None else prompt_int("M (files per length, >= 1): ")
    )
    length_step = args.length_step if args.length_step is not None else prompt_int("K (length step, >= 1): ")

    if max_length < 7:
        print("N must be >= 7.")
        return 1
    if count_per_length < 1:
        print("M must be >= 1.")
        return 1
    if length_step < 1:
        print("K must be >= 1.")
        return 1
    if args.max_attempts < 1:
        print("--max-attempts must be >= 1.")
        return 1

    script_dir = Path(__file__).resolve().parent
    output_root = Path(args.output_root) if args.output_root is not None else resolve_default_output_root(script_dir)
    folder_name = f"random-valid-json-{max_length}x{count_per_length}x{length_step}"
    output_dir = output_root / folder_name
    output_dir.mkdir(parents=True, exist_ok=True)

    target_lengths = build_target_lengths(max_length, length_step)
    total = len(target_lengths) * count_per_length
    count = 0
    errors = 0

    print(f"Output directory: {output_dir}")
    print(f"Length step (K): {length_step}")
    print(f"Total files to generate: {total}")

    generator = Generator()
    entries = []

    for length in target_lengths:
        for seq in range(1, count_per_length + 1):
            json_str = None
            for attempt in range(args.max_attempts):
                try:
                    result = generator.generate_top_level(length)
                    if len(result) != length:
                        raise ValueError(f"expected={length}, got={len(result)}")
                    if not generator.is_valid_json(result):
                        raise ValueError("generated JSON failed validation")
                    json_str = result
                    break
                except Exception as exc:
                    if attempt == args.max_attempts - 1:
                        print(
                            f"[ERROR] length={length}, seq={seq}: failed after {args.max_attempts} attempts ({exc})"
                        )
                        errors += 1

            if json_str is not None:
                entries.append(json_str)

            count += 1
            if count % 200 == 0 or count == total:
                print(f"Progress: {count}/{total}")

    jsonl_path = output_dir / "samples.jsonl"
    with jsonl_path.open("w", encoding="utf-8", newline="\n") as fh:
        for entry in entries:
            fh.write(entry + "\n")

    print(f"\nDone: {jsonl_path}")
    print(f"Generated: {count - errors}, Failed: {errors}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

