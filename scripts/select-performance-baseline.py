#!/usr/bin/env python3
"""Select the newest successful GitHub Actions artifact for performance baselines.

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


def run_id_for_artifact(artifact: dict) -> str:
    workflow_run = artifact.get("workflow_run") or {}
    value = workflow_run.get("id")
    return "" if value is None else str(value)


def artifact_matches(artifact: dict, prefix: str, branch: str) -> bool:
    workflow_run = artifact.get("workflow_run") or {}
    if artifact.get("expired") is True:
        return False
    if not str(artifact.get("name", "")).startswith(prefix):
        return False
    if workflow_run.get("head_branch") != branch:
        return False
    return bool(run_id_for_artifact(artifact))


def successful_workflow_run(repo: str, run_id: str, token: str, run_cache: dict[str, dict]) -> dict | None:
    if run_id not in run_cache:
        run_cache[run_id] = request_json(
            f"https://api.github.com/repos/{repo}/actions/runs/{run_id}",
            token,
        )
    run = run_cache[run_id]
    if run.get("status") == "completed" and run.get("conclusion") == "success":
        return run
    return None


def select_artifact(artifacts: list[dict], prefix: str, branch: str, repo: str, token: str, run_cache: dict[str, dict]) -> tuple[dict, dict] | None:
    for artifact in artifacts:
        if not artifact_matches(artifact, prefix, branch):
            continue
        run = successful_workflow_run(repo, run_id_for_artifact(artifact), token, run_cache)
        if run is not None:
            return artifact, run
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
        "run_status": "",
        "run_conclusion": "",
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
        selection = None
        run_cache: dict[str, dict] = {}
        page = 1
        while selection is None:
            payload = request_json(
                f"https://api.github.com/repos/{args.repo}/actions/artifacts?per_page=100&page={page}",
                token,
            )
            artifacts = payload.get("artifacts", [])
            selection = select_artifact(artifacts, args.artifact_prefix, args.branch, args.repo, token, run_cache)
            if selection is not None or len(artifacts) < 100:
                break
            page += 1
        if selection is None:
            print(f"No successful baseline artifact found for prefix {args.artifact_prefix!r} on branch {args.branch!r}.")
            outputs = empty_outputs | {"status": "not-found"}
            write_selection(args.out_json, outputs)
            write_outputs(args.github_output, outputs)
            return 0

        artifact, run = selection
        workflow_run = artifact.get("workflow_run") or {}
        outputs = {
            "artifact_id": str(artifact.get("id", "")),
            "artifact_name": str(artifact.get("name", "")),
            "run_id": str(run.get("id", workflow_run.get("id", ""))),
            "head_sha": str(run.get("head_sha", workflow_run.get("head_sha", ""))),
            "run_status": str(run.get("status", "")),
            "run_conclusion": str(run.get("conclusion", "")),
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
