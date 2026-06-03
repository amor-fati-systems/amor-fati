#!/usr/bin/env python3
"""Render a soft performance-regression report from two run manifests."""

from __future__ import annotations

import argparse
import json
import math
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


BYTES_IN_MIB = 1024 * 1024


@dataclass(frozen=True)
class MetricPolicy:
    key: str
    label: str
    direction: str
    warn_ratio: float
    min_delta: float
    unit: str


@dataclass(frozen=True)
class StepMetrics:
    step_id: str
    values: dict[str, float]


POLICIES = [
    MetricPolicy("duration_ms", "Duration", "lower", 1.25, 30_000.0, "ms"),
    MetricPolicy("seed_months_per_second", "Seed-month throughput", "higher", 0.75, 0.0, "seed-months/s"),
    MetricPolicy("heap_used_bytes_after", "Heap used after step", "lower", 1.40, 64.0 * BYTES_IN_MIB, "bytes"),
    MetricPolicy("gc_time_millis_delta", "GC time", "lower", 1.50, 10_000.0, "ms"),
]


def read_json(path: Path | None) -> dict[str, Any] | None:
    if path is None or not path.is_file():
        return None
    try:
        loaded = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    return loaded if isinstance(loaded, dict) else None


def number(value: Any) -> float | None:
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, (int, float)):
        result = float(value)
        return result if math.isfinite(result) else None
    return None


def gc_time(telemetry: dict[str, Any]) -> float | None:
    total = 0.0
    seen = False
    for row in telemetry.get("gc", []) or []:
        value = number(row.get("collection_time_millis_delta"))
        if value is not None:
            total += value
            seen = True
    return total if seen else None


def extract_steps(manifest: dict[str, Any] | None) -> dict[str, StepMetrics]:
    if manifest is None:
        return {}
    records: dict[str, StepMetrics] = {}
    for step in manifest.get("steps", []) or []:
        step_id = step.get("id")
        telemetry = step.get("telemetry")
        if not isinstance(step_id, str) or not isinstance(telemetry, dict):
            continue
        after = ((telemetry.get("memory") or {}).get("after") or {})
        values = {
            "duration_ms": number(telemetry.get("duration_ms")),
            "seed_months_per_second": number(telemetry.get("seed_months_per_second")),
            "heap_used_bytes_after": number(after.get("heap_used_bytes")),
            "gc_time_millis_delta": gc_time(telemetry),
        }
        records[step_id] = StepMetrics(step_id=step_id, values={k: v for k, v in values.items() if v is not None})
    return records


def warn(policy: MetricPolicy, current: float, baseline: float) -> bool:
    if baseline <= 0.0:
        return False
    delta = current - baseline
    if policy.direction == "lower":
        return current > baseline * policy.warn_ratio and delta > policy.min_delta
    if policy.direction == "higher":
        return current < baseline * policy.warn_ratio
    raise ValueError(f"Unsupported policy direction: {policy.direction}")


def ratio(policy: MetricPolicy, current: float, baseline: float) -> float | None:
    if baseline <= 0.0:
        return None
    if policy.direction == "higher":
        return current / baseline
    return current / baseline


def format_value(value: float | None, unit: str) -> str:
    if value is None:
        return ""
    if unit == "bytes":
        return f"{value / BYTES_IN_MIB:.1f} MiB"
    if unit == "ms":
        return f"{value:.0f} ms"
    return f"{value:.3f}"


def manifest_ref(manifest: dict[str, Any] | None) -> dict[str, str]:
    if manifest is None:
        return {"run_id": "", "commit": "", "status": ""}
    git = manifest.get("git") or {}
    return {
        "run_id": str(manifest.get("run_id", "")),
        "commit": str(git.get("short_commit") or git.get("commit") or ""),
        "status": str(manifest.get("status", "")),
    }


def build_report(
    *,
    profile: str,
    source: str,
    current: dict[str, Any] | None,
    baseline: dict[str, Any] | None,
    baseline_artifact: str,
    skip_reason: str,
) -> dict[str, Any]:
    current_ref = manifest_ref(current)
    baseline_ref = manifest_ref(baseline)
    if skip_reason:
        return {
            "schema_version": 1,
            "status": "SKIPPED",
            "profile": profile,
            "source": source,
            "policy": "soft_warning",
            "skip_reason": skip_reason,
            "current": current_ref,
            "baseline": baseline_ref,
            "baseline_artifact": baseline_artifact,
            "comparisons": [],
            "warning_count": 0,
        }
    if current is None:
        return {
            "schema_version": 1,
            "status": "CURRENT_MISSING",
            "profile": profile,
            "source": source,
            "policy": "soft_warning",
            "skip_reason": "",
            "current": current_ref,
            "baseline": baseline_ref,
            "baseline_artifact": baseline_artifact,
            "comparisons": [],
            "warning_count": 0,
        }
    if baseline is None:
        return {
            "schema_version": 1,
            "status": "NO_BASELINE",
            "profile": profile,
            "source": source,
            "policy": "soft_warning",
            "skip_reason": "",
            "current": current_ref,
            "baseline": baseline_ref,
            "baseline_artifact": baseline_artifact,
            "comparisons": [],
            "warning_count": 0,
        }

    current_steps = extract_steps(current)
    baseline_steps = extract_steps(baseline)
    comparisons = []
    warning_count = 0
    for step_id in sorted(set(current_steps).intersection(baseline_steps)):
        current_step = current_steps[step_id]
        baseline_step = baseline_steps[step_id]
        for policy in POLICIES:
            current_value = current_step.values.get(policy.key)
            baseline_value = baseline_step.values.get(policy.key)
            if current_value is None or baseline_value is None:
                continue
            warning = warn(policy, current_value, baseline_value)
            if warning:
                warning_count += 1
            comparisons.append(
                {
                    "step": step_id,
                    "metric": policy.key,
                    "label": policy.label,
                    "direction": policy.direction,
                    "unit": policy.unit,
                    "baseline": baseline_value,
                    "current": current_value,
                    "ratio": ratio(policy, current_value, baseline_value),
                    "status": "WARN" if warning else "PASS",
                    "threshold": policy.warn_ratio,
                },
            )

    status = "WARN" if warning_count else "PASS"
    if not comparisons:
        status = "NO_SHARED_TELEMETRY"
    return {
        "schema_version": 1,
        "status": status,
        "profile": profile,
        "source": source,
        "policy": "soft_warning",
        "skip_reason": "",
        "current": current_ref,
        "baseline": baseline_ref,
        "baseline_artifact": baseline_artifact,
        "comparisons": comparisons,
        "warning_count": warning_count,
    }


def render_markdown(report: dict[str, Any]) -> str:
    lines = [
        "# Performance Regression Report",
        "",
        f"- Status: `{report['status']}`",
        f"- Profile: `{report['profile']}`",
        f"- Source: `{report['source']}`",
        f"- Policy: `{report['policy']}`",
        f"- Current run: `{report['current'].get('run_id', '')}` (`{report['current'].get('commit', '')}`)",
        f"- Current status: `{report['current'].get('status', '')}`",
        f"- Baseline run: `{report['baseline'].get('run_id', '')}` (`{report['baseline'].get('commit', '')}`)",
        f"- Baseline status: `{report['baseline'].get('status', '')}`",
        f"- Baseline artifact: `{report.get('baseline_artifact', '')}`",
        f"- Warning count: `{report['warning_count']}`",
    ]
    if report.get("skip_reason"):
        lines.append(f"- Skip reason: {report['skip_reason']}")
    lines.extend(["", "| Step | Metric | Baseline | Current | Ratio | Status |", "| --- | --- | --- | --- | --- | --- |"])
    for row in report.get("comparisons", []):
        ratio_value = row.get("ratio")
        ratio_text = "" if ratio_value is None else f"{ratio_value:.3f}x"
        lines.append(
            "| "
            + " | ".join(
                [
                    row["step"],
                    row["label"],
                    format_value(row["baseline"], row["unit"]),
                    format_value(row["current"], row["unit"]),
                    ratio_text,
                    row["status"],
                ],
            )
            + " |",
        )
    return "\n".join(lines) + "\n"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--current", type=Path, required=True)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--out-json", type=Path, required=True)
    parser.add_argument("--out-md", type=Path, required=True)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--baseline-artifact", default="")
    parser.add_argument("--skip-reason", default="")
    args = parser.parse_args(argv)

    current = read_json(args.current)
    baseline = read_json(args.baseline)
    report = build_report(
        profile=args.profile,
        source=args.source,
        current=current,
        baseline=baseline,
        baseline_artifact=args.baseline_artifact,
        skip_reason=args.skip_reason,
    )

    args.out_json.parent.mkdir(parents=True, exist_ok=True)
    args.out_md.parent.mkdir(parents=True, exist_ok=True)
    args.out_json.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    args.out_md.write_text(render_markdown(report), encoding="utf-8")
    print(f"Performance regression report status: {report['status']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
