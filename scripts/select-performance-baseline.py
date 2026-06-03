#!/usr/bin/env python3
"""Select the newest matching GitHub Actions artifact for performance baselines.

The selector is intentionally best-effort. Missing permissions or absent
artifacts are represented as empty outputs so workflow callers can produce a
soft "no baseline" report instead of failing diagnostics or profiling.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path


def request_json(url: str, token: str) -> dict:
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def write_outputs(github_output: Path | None, outputs: dict[str, str]) -> None:
    if github_output is None:
        return
    with github_output.open("a", encoding="utf-8") as handle:
        for key, value in outputs.items():
            handle.write(f"{key}={value}\n")


def write_selection(path: Path | None, outputs: dict[str, str]) -> None:
    if path is None:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(outputs, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def select_artifact(artifacts: list[dict], prefix: str, branch: str) -> dict | None:
    for artifact in artifacts:
        workflow_run = artifact.get("workflow_run") or {}
        if artifact.get("expired") is True:
            continue
        if not str(artifact.get("name", "")).startswith(prefix):
            continue
        if workflow_run.get("head_branch") != branch:
            continue
        return artifact
    return None


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, help="GitHub repository in owner/name form")
    parser.add_argument("--artifact-prefix", required=True, help="Artifact-name prefix to use as the baseline selector")
    parser.add_argument("--branch", default="main", help="Workflow-run branch to accept as a baseline")
    parser.add_argument("--github-output", type=Path, help="Optional GITHUB_OUTPUT file to populate")
    parser.add_argument("--out-json", type=Path, help="Optional JSON file with the selected artifact metadata")
    parser.add_argument("--token-env", default="GITHUB_TOKEN", help="Environment variable containing the GitHub token")
    args = parser.parse_args(argv)

    empty_outputs = {
        "artifact_id": "",
        "artifact_name": "",
        "run_id": "",
        "head_sha": "",
        "status": "missing",
    }
    token = os.environ.get(args.token_env, "")
    if not token:
        print("No GitHub token available; performance baseline unavailable.", file=sys.stderr)
        outputs = empty_outputs | {"status": "missing-token"}
        write_selection(args.out_json, outputs)
        write_outputs(args.github_output, outputs)
        return 0

    try:
        payload = request_json(f"https://api.github.com/repos/{args.repo}/actions/artifacts?per_page=100", token)
        artifact = select_artifact(payload.get("artifacts", []), args.artifact_prefix, args.branch)
        if artifact is None:
            print(f"No baseline artifact found for prefix {args.artifact_prefix!r} on branch {args.branch!r}.")
            outputs = empty_outputs | {"status": "not-found"}
            write_selection(args.out_json, outputs)
            write_outputs(args.github_output, outputs)
            return 0

        workflow_run = artifact.get("workflow_run") or {}
        outputs = {
            "artifact_id": str(artifact.get("id", "")),
            "artifact_name": str(artifact.get("name", "")),
            "run_id": str(workflow_run.get("id", "")),
            "head_sha": str(workflow_run.get("head_sha", "")),
            "status": "ok",
        }
        print(json.dumps(outputs, sort_keys=True))
        write_selection(args.out_json, outputs)
        write_outputs(args.github_output, outputs)
        return 0
    except (OSError, urllib.error.URLError, urllib.error.HTTPError, KeyError, json.JSONDecodeError) as exc:
        print(f"Performance baseline unavailable: {type(exc).__name__}: {exc}", file=sys.stderr)
        outputs = empty_outputs | {"status": "error"}
        write_selection(args.out_json, outputs)
        write_outputs(args.github_output, outputs)
        return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
