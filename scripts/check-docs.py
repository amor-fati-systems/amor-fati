#!/usr/bin/env python3
"""Lightweight documentation hygiene checks.

The checker deliberately avoids prose/style linting. It validates only cheap
structural contracts that should not require human interpretation:

- local Markdown links point to existing files/directories;
- local Markdown anchors point to existing headings;
- every committed docs/ artifact is listed in docs/README.md.
"""

from __future__ import annotations

import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import unquote, urlsplit


REPO_ROOT = Path(__file__).resolve().parents[1]
DOCS_DIR = REPO_ROOT / "docs"
README = REPO_ROOT / "README.md"
DOCUMENTATION_INDEX = DOCS_DIR / "README.md"

EXTERNAL_SCHEMES = {"http", "https", "mailto", "tel", "data"}


@dataclass(frozen=True)
class Link:
    source: Path
    line: int
    target: str


def main() -> int:
    markdown_files = [README] + sorted(DOCS_DIR.rglob("*.md"))
    anchors = {path: collect_anchors(path) for path in markdown_files}

    errors: list[str] = []
    for path in markdown_files:
        for link in extract_links(path):
            errors.extend(validate_link(link, anchors))

    errors.extend(validate_documentation_inventory())

    if errors:
        print("Documentation hygiene check failed:")
        for error in errors:
            print(f"- {error}")
        return 1

    print(f"Documentation hygiene check passed for {len(markdown_files)} Markdown files.")
    return 0


def collect_anchors(path: Path) -> set[str]:
    counts: dict[str, int] = {}
    anchors: set[str] = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        match = re.match(r"^(#{1,6})\s+(.+?)\s*$", line)
        if not match:
            continue
        base = github_heading_anchor(match.group(2))
        index = counts.get(base, 0)
        counts[base] = index + 1
        anchors.add(base if index == 0 else f"{base}-{index}")
    return anchors


def github_heading_anchor(heading: str) -> str:
    text = re.sub(r"<[^>]+>", "", heading)
    text = text.replace("`", "").replace("*", "").replace("_", "")
    text = text.lower()
    chars: list[str] = []
    previous_dash = False
    for char in text:
        if char.isalnum():
            chars.append(char)
            previous_dash = False
        elif char in {" ", "\t", "-"}:
            if not previous_dash:
                chars.append("-")
                previous_dash = True
    return "".join(chars).strip("-")


def extract_links(path: Path) -> list[Link]:
    links: list[Link] = []
    fenced = False
    lines = path.read_text(encoding="utf-8").splitlines()
    for line_number, line in enumerate(lines, start=1):
        stripped = line.lstrip()
        if stripped.startswith("```") or stripped.startswith("~~~"):
            fenced = not fenced
            continue
        if fenced:
            continue
        links.extend(Link(path, line_number, target) for target in inline_link_targets(line))
    return links


def inline_link_targets(line: str) -> list[str]:
    targets: list[str] = []
    index = 0
    while index < len(line):
        open_label = line.find("[", index)
        if open_label == -1:
            break
        close_label = line.find("](", open_label)
        if close_label == -1:
            break
        target_start = close_label + 2
        depth = 0
        target_end = target_start
        while target_end < len(line):
            char = line[target_end]
            if char == "(":
                depth += 1
            elif char == ")":
                if depth == 0:
                    break
                depth -= 1
            target_end += 1
        if target_end >= len(line):
            index = close_label + 2
            continue
        targets.append(line[target_start:target_end])
        index = target_end + 1
    return targets


def validate_link(link: Link, anchors: dict[Path, set[str]]) -> list[str]:
    target = normalize_link_target(link.target)
    if not target or is_external_link(target):
        return []

    path_part, fragment = split_fragment(target)
    if not path_part:
        destination = link.source
    else:
        destination = resolve_local_path(link.source.parent, path_part)

    errors: list[str] = []
    if destination is None:
        errors.append(format_error(link, f"link escapes repository root: {target}"))
        return errors
    if not destination.exists():
        errors.append(format_error(link, f"missing local target: {target}"))
        return errors

    if fragment and destination.suffix.lower() == ".md":
        expected_anchor = unquote(fragment)
        known_anchors = anchors.get(destination)
        if known_anchors is None:
            known_anchors = collect_anchors(destination)
        if expected_anchor not in known_anchors:
            errors.append(format_error(link, f"missing Markdown anchor: {target}"))

    return errors


def normalize_link_target(raw: str) -> str:
    target = raw.strip()
    if target.startswith("<") and target.endswith(">"):
        target = target[1:-1].strip()
    return target.split()[0] if target else ""


def is_external_link(target: str) -> bool:
    parsed = urlsplit(target)
    return parsed.scheme in EXTERNAL_SCHEMES or target.startswith("//")


def split_fragment(target: str) -> tuple[str, str]:
    if "#" not in target:
        return target, ""
    path_part, fragment = target.split("#", 1)
    return path_part, fragment


def resolve_local_path(base: Path, path_part: str) -> Path | None:
    without_query = path_part.split("?", 1)[0]
    decoded = unquote(without_query)
    if decoded.startswith("/"):
        candidate = REPO_ROOT / decoded.lstrip("/")
    else:
        candidate = base / decoded
    resolved = candidate.resolve()
    try:
        resolved.relative_to(REPO_ROOT)
    except ValueError:
        return None
    return resolved


def validate_documentation_inventory() -> list[str]:
    inventory = DOCUMENTATION_INDEX.read_text(encoding="utf-8")
    tracked = git_ls_files(["README.md", "docs"])
    errors: list[str] = []
    for relative_path in tracked:
        expected = "README.md" if relative_path == "README.md" else relative_path
        if expected not in inventory:
            errors.append(
                f"{relative_path}: missing documentation ownership entry in "
                "docs/README.md"
            )
    return errors


def git_ls_files(paths: list[str]) -> list[str]:
    result = subprocess.run(
        ["git", "ls-files", *paths],
        cwd=REPO_ROOT,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    )
    return [line for line in result.stdout.splitlines() if line]


def format_error(link: Link, message: str) -> str:
    relative = link.source.relative_to(REPO_ROOT)
    return f"{relative}:{link.line}: {message}"


if __name__ == "__main__":
    sys.exit(main())
