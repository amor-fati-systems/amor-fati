#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

usage() {
  cat <<'EOF'
Usage:
  bash scripts/profile-jvm-process.sh [options] -- <main-class> [args...]
  bash scripts/profile-jvm-process.sh [options] --raw-java -- <java-args...>

Profiles an assembled-jar JVM process with JFR and GC logging, then renders
standard JFR views into the run directory.

Options:
  --out DIR             Output root. Default: target/jvm-profiles
  --run-id ID           Run id. Default: profile-<utc timestamp>
  --jar PATH            Jar used with -cp. Default: target/scala-3.8.2/amor-fati.jar
  --jfr-settings NAME   JFR settings profile. Default: profile
  --java-cmd COMMAND    Java launcher. Default: amor-fati-java when available, else java
  --jvm-opt OPTION      Extra JVM option. May be repeated.
  --build               Run sbt assembly before profiling.
  --raw-java            Do not add -cp <jar>; pass <java-args...> directly.
  --help                Show this help.

Examples:
  nix develop --command bash scripts/profile-jvm-process.sh --build --run-id bank-ablations-1x60 -- \
    com.boombustgroup.amorfati.diagnostics.BankFailureAblationExport \
    --seeds 1 --months 60 --parallelism 1 --out target/bank-failure-ablations --run-id bank-ablations-1x60

  nix develop --command bash scripts/profile-jvm-process.sh --build --run-id main-1x12 -- \
    com.boombustgroup.amorfati.Main 1 profile-main --duration 12 --run-id profile-main
EOF
}

out_root="target/jvm-profiles"
run_id=""
jar_path="target/scala-3.8.2/amor-fati.jar"
jfr_settings="profile"
java_cmd=""
build=false
raw_java=false
jvm_opts=()
target_args=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --out)
      [[ $# -ge 2 ]] || { echo "Missing value for --out" >&2; exit 2; }
      out_root="$2"
      shift 2
      ;;
    --run-id)
      [[ $# -ge 2 ]] || { echo "Missing value for --run-id" >&2; exit 2; }
      run_id="$2"
      shift 2
      ;;
    --jar)
      [[ $# -ge 2 ]] || { echo "Missing value for --jar" >&2; exit 2; }
      jar_path="$2"
      shift 2
      ;;
    --jfr-settings)
      [[ $# -ge 2 ]] || { echo "Missing value for --jfr-settings" >&2; exit 2; }
      jfr_settings="$2"
      shift 2
      ;;
    --java-cmd)
      [[ $# -ge 2 ]] || { echo "Missing value for --java-cmd" >&2; exit 2; }
      java_cmd="$2"
      shift 2
      ;;
    --jvm-opt)
      [[ $# -ge 2 ]] || { echo "Missing value for --jvm-opt" >&2; exit 2; }
      jvm_opts+=("$2")
      shift 2
      ;;
    --build)
      build=true
      shift
      ;;
    --raw-java)
      raw_java=true
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    --)
      shift
      target_args=("$@")
      break
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ ${#target_args[@]} -eq 0 ]]; then
  echo "Missing profiled process command after --" >&2
  usage >&2
  exit 2
fi

if [[ -z "$run_id" ]]; then
  run_id="profile-$(date -u +"%Y%m%d-%H%M%S")"
fi

if [[ -z "$java_cmd" ]]; then
  if command -v amor-fati-java >/dev/null 2>&1; then
    java_cmd="amor-fati-java"
  else
    java_cmd="java"
  fi
fi

run_dir="${out_root%/}/${run_id}"
profiling_dir="${run_dir}/profiling"
mkdir -p "$profiling_dir"

jfr_path="${profiling_dir}/amor-fati-${run_id}.jfr"
gc_log_path="${profiling_dir}/gc.log"
process_log_path="${profiling_dir}/process.log"
build_log_path="${profiling_dir}/build.log"
metadata_path="${profiling_dir}/profile-metadata.txt"
command_args_path="${profiling_dir}/command.args"

if [[ "$build" == "true" ]]; then
  echo "==> Building assembled jar"
  set +e
  sbt assembly 2>&1 | tee "$build_log_path"
  build_exit_code="${PIPESTATUS[0]}"
  set -e
  if [[ "$build_exit_code" -ne 0 ]]; then
    echo "sbt assembly failed with exit code ${build_exit_code}" >&2
    exit "$build_exit_code"
  fi
fi

if [[ "$raw_java" != "true" && ! -f "$jar_path" ]]; then
  echo "Jar not found: ${jar_path}" >&2
  echo "Run with --build, or pass --jar PATH / --raw-java." >&2
  exit 2
fi

jfr_option="-XX:StartFlightRecording=filename=${jfr_path},settings=${jfr_settings},dumponexit=true,disk=true"
gc_option="-Xlog:gc*:file=${gc_log_path}:time,uptime,level,tags"

if [[ "$raw_java" == "true" ]]; then
  profiled_cmd=("$java_cmd" "${jvm_opts[@]}" "$jfr_option" "$gc_option" "${target_args[@]}")
else
  profiled_cmd=("$java_cmd" "${jvm_opts[@]}" "$jfr_option" "$gc_option" -cp "$jar_path" "${target_args[@]}")
fi

{
  echo "run_id=${run_id}"
  echo "created_at_utc=$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  echo "repo_root=${repo_root}"
  echo "out_root=${out_root}"
  echo "run_dir=${run_dir}"
  echo "java_cmd=${java_cmd}"
  echo "jar_path=${jar_path}"
  echo "raw_java=${raw_java}"
  echo "jfr_settings=${jfr_settings}"
  echo "jfr_path=${jfr_path}"
  echo "gc_log_path=${gc_log_path}"
  echo "process_log_path=${process_log_path}"
  echo "build=${build}"
} > "$metadata_path"
printf '%s\n' "${profiled_cmd[@]}" > "$command_args_path"

echo "==> Profiling JVM process"
echo "Run directory: ${run_dir}"
echo "JFR: ${jfr_path}"
echo "GC log: ${gc_log_path}"

set +e
"${profiled_cmd[@]}" 2>&1 | tee "$process_log_path"
process_exit_code="${PIPESTATUS[0]}"
set -e

render_jfr() {
  local jfr_cmd=""
  if command -v jfr >/dev/null 2>&1; then
    jfr_cmd="jfr"
  elif [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/jfr" ]]; then
    jfr_cmd="${JAVA_HOME}/bin/jfr"
  fi

  if [[ -z "$jfr_cmd" ]]; then
    echo "jfr command not found; raw JFR is still available at ${jfr_path}" | tee "${profiling_dir}/jfr-render-error.txt"
    return 0
  fi

  "$jfr_cmd" summary "$jfr_path" > "${profiling_dir}/jfr-summary.txt" || true

  for view in \
    recording \
    jvm-flags \
    hot-methods \
    allocation-by-class \
    allocation-by-site \
    gc \
    gc-pauses \
    thread-cpu-load \
    file-reads-by-path \
    file-writes-by-path
  do
    "$jfr_cmd" view --width 180 "$view" "$jfr_path" > "${profiling_dir}/jfr-${view}.txt" || true
  done
}

if [[ -f "$jfr_path" ]]; then
  echo "==> Rendering JFR summaries"
  render_jfr
else
  echo "JFR recording was not produced: ${jfr_path}" | tee "${profiling_dir}/jfr-render-error.txt"
fi

echo "==> Profiling artifacts written to ${profiling_dir}"
exit "$process_exit_code"
