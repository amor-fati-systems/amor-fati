#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

generated_paths=(
  "docs/calibration-register.md"
  "docs/sfc-matrix-artifacts"
)

run_grouped() {
  local label="$1"
  shift

  if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
    echo "::group::$label"
  else
    echo "==> $label"
  fi

  "$@"

  if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
    echo "::endgroup::"
  fi
}

run_grouped "Regenerate committed repository outputs" \
  sbt \
  "calibrationRegister --out docs/calibration-register.md" \
  "sfcMatrices --seed 1 --months 12 --out docs/sfc-matrix-artifacts --format md --commit committed-snapshot"

untracked_files="$(git ls-files --others --exclude-standard -- "${generated_paths[@]}")"

if git diff --quiet -- "${generated_paths[@]}" &&
  git diff --cached --quiet -- "${generated_paths[@]}" &&
  [[ -z "$untracked_files" ]]; then
  echo "Generated repository outputs are up to date."
  exit 0
fi

if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
  echo "::error::Generated repository outputs are stale. Regenerate them and commit the resulting files."
else
  echo "ERROR: Generated repository outputs are stale. Regenerate them and commit the resulting files."
fi
echo
echo "Run locally:"
echo "  nix develop --command bash scripts/check-generated-outputs.sh"
echo

changed_files="$(
  {
    git diff --name-only -- "${generated_paths[@]}"
    git diff --cached --name-only -- "${generated_paths[@]}"
    if [[ -n "$untracked_files" ]]; then
      printf '%s\n' "$untracked_files"
    fi
  } | sort -u
)"

echo "Changed generated files:"
printf '%s\n' "$changed_files"
echo

echo "Generated output diff:"
git diff -- "${generated_paths[@]}" || true
git diff --cached -- "${generated_paths[@]}" || true

exit 1
