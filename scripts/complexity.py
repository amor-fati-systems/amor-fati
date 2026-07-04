#!/usr/bin/env python3
"""Cyclomatic complexity estimator for Scala 3 source files.

Heuristic: counts branching keywords (if, case, match, while, for, &&, ||, catch)
per method/def block.  Not a full AST parse, but gives a useful ranking of the most
complex methods in the codebase.

Usage:
    python3 scripts/complexity.py [src_dir] [--threshold N] [--top N]
"""

import argparse
import re
from pathlib import Path
from dataclasses import dataclass

BRANCH_PATTERN = re.compile(
    r"""
      \bif\b          # if expression
    | \bcase\b        # pattern match arm
    | \bmatch\b       # match expression
    | \bwhile\b       # while loop
    | \bfor\b         # for comprehension
    | \bcatch\b       # exception handler
    | &&              # logical AND
    | \|\|            # logical OR
    """,
    re.VERBOSE,
)

# Match def/val/var declarations that start a method/function
DEF_PATTERN = re.compile(r"^\s*((?:override\s+)?(?:private\s+|protected\s+)?(?:lazy\s+)?(?:def|val|var))\s+(\w+)")

# Strings and comments to strip before counting
STRING_PATTERN = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|//.*$', re.MULTILINE)


@dataclass
class MethodComplexity:
    file: str
    line: int
    name: str
    kind: str  # def/val/var
    complexity: int
    loc: int


def strip_strings_and_comments(source: str) -> str:
    """Remove string literals and line comments to avoid false positives."""
    return STRING_PATTERN.sub("", source)


def analyze_file(path: Path) -> list[MethodComplexity]:
    """Analyze a single Scala file and return per-method complexity."""
    try:
        raw = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return []

    cleaned = strip_strings_and_comments(raw)
    lines = cleaned.split("\n")

    methods: list[MethodComplexity] = []
    current_method = None
    current_start = 0
    current_kind = ""
    brace_depth = 0
    method_brace_depth = 0
    branch_count = 0
    method_loc = 0

    for i, line in enumerate(lines, start=1):
        # Track brace depth
        brace_depth += line.count("{") + line.count(":")  # Scala 3 braceless: approximate
        brace_depth -= line.count("}")

        # Look for new method definition
        m = DEF_PATTERN.match(line)
        if m:
            # Save previous method
            if current_method:
                methods.append(MethodComplexity(
                    file=str(path),
                    line=current_start,
                    name=current_method,
                    kind=current_kind,
                    complexity=branch_count + 1,  # +1 for the method itself
                    loc=method_loc,
                ))

            current_kind = m.group(1).split()[-1]  # def/val/var
            current_method = m.group(2)
            current_start = i
            method_brace_depth = brace_depth
            branch_count = 0
            method_loc = 0

        if current_method:
            method_loc += 1
            branch_count += len(BRANCH_PATTERN.findall(line))

    # Don't forget the last method
    if current_method:
        methods.append(MethodComplexity(
            file=str(path),
            line=current_start,
            name=current_method,
            kind=current_kind,
            complexity=branch_count + 1,
            loc=method_loc,
        ))

    return methods


def main():
    parser = argparse.ArgumentParser(description="Scala cyclomatic complexity estimator")
    parser.add_argument("src_dir", nargs="?", default="modules",
                        help="Source directory to scan (default: modules)")
    parser.add_argument("--threshold", type=int, default=5,
                        help="Only show methods with complexity >= threshold (default: 5)")
    parser.add_argument("--top", type=int, default=40,
                        help="Show top N most complex methods (default: 40)")
    parser.add_argument("--all", action="store_true",
                        help="Show all methods (ignore threshold)")
    parser.add_argument("--summary", action="store_true",
                        help="Show per-file summary instead of per-method")
    args = parser.parse_args()

    src = Path(args.src_dir)
    if not src.exists():
        print(f"Error: {src} does not exist")
        return

    all_methods: list[MethodComplexity] = []
    for f in sorted(src.rglob("*.scala")):
        all_methods.extend(analyze_file(f))

    if not all_methods:
        print("No Scala methods found.")
        return

    if args.summary:
        # Per-file summary
        from collections import defaultdict
        file_stats: dict[str, list[MethodComplexity]] = defaultdict(list)
        for m in all_methods:
            file_stats[m.file].append(m)

        print(f"\n{'File':<65} {'Methods':>7} {'MaxCC':>6} {'AvgCC':>6} {'LOC':>6}")
        print("=" * 95)

        rows = []
        for f, methods in file_stats.items():
            max_cc = max(m.complexity for m in methods)
            avg_cc = sum(m.complexity for m in methods) / len(methods)
            total_loc = sum(m.loc for m in methods)
            short = str(Path(f).relative_to(Path.cwd())) if Path(f).is_relative_to(Path.cwd()) else f
            rows.append((short, len(methods), max_cc, avg_cc, total_loc))

        rows.sort(key=lambda r: r[2], reverse=True)
        for short, n, max_cc, avg_cc, loc in rows:
            flag = " ⚠" if max_cc >= 15 else ""
            print(f"{short:<65} {n:>7} {max_cc:>6} {avg_cc:>6.1f} {loc:>6}{flag}")

        total = len(all_methods)
        overall_avg = sum(m.complexity for m in all_methods) / total
        over_10 = sum(1 for m in all_methods if m.complexity > 10)
        over_20 = sum(1 for m in all_methods if m.complexity > 20)
        max_m = max(all_methods, key=lambda m: m.complexity)

        print(f"\n--- Summary ---")
        print(f"Total methods:    {total}")
        print(f"Avg complexity:   {overall_avg:.1f}")
        print(f"Max complexity:   {max_m.complexity} ({max_m.name} in {Path(max_m.file).name}:{max_m.line})")
        print(f"CC > 10:          {over_10}")
        print(f"CC > 20:          {over_20}")
    else:
        # Per-method ranking
        threshold = 0 if args.all else args.threshold
        filtered = [m for m in all_methods if m.complexity >= threshold and m.kind == "def"]
        filtered.sort(key=lambda m: m.complexity, reverse=True)
        filtered = filtered[:args.top]

        if not filtered:
            print(f"No methods with complexity >= {threshold}")
            return

        print(f"\n{'CC':>4} {'LOC':>5} {'Method':<40} {'File':>50}:Line")
        print("=" * 110)
        for m in filtered:
            short = str(Path(m.file).relative_to(Path.cwd())) if Path(m.file).is_relative_to(Path.cwd()) else m.file
            risk = "🔴" if m.complexity > 20 else "🟡" if m.complexity > 10 else "  "
            print(f"{m.complexity:>4} {m.loc:>5} {risk} {m.name:<38} {short}:{m.line}")

        total_defs = sum(1 for m in all_methods if m.kind == "def")
        avg = sum(m.complexity for m in all_methods if m.kind == "def") / max(total_defs, 1)
        print(f"\n--- {total_defs} def methods, avg CC = {avg:.1f}, showing top {len(filtered)} with CC >= {threshold} ---")


if __name__ == "__main__":
    main()
