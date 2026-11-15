#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="/home/antigravity"
E2E_SRC_DIR="${ROOT_DIR}/e2e-tests/src"

if [[ -f "${ROOT_DIR}/scripts/env.sh" ]]; then
  # shellcheck disable=SC1091
  source "${ROOT_DIR}/scripts/env.sh"
fi

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/home/antigravity/.android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_HOME}"
export TMPDIR="${TMPDIR:-/home/antigravity/.tmp}"
mkdir -p "${TMPDIR}"

_TRUST_FLAG="-Djavax.net.ssl.trustStore=/home/antigravity/.android-sdk/cacerts.p12 -Djavax.net.ssl.trustStorePassword=changeit"
case "${JAVA_TOOL_OPTIONS:-}" in
  *"javax.net.ssl.trustStore="*) ;;
  "") export JAVA_TOOL_OPTIONS="${_TRUST_FLAG} -Djava.io.tmpdir=${TMPDIR}" ;;
  *)  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} ${_TRUST_FLAG} -Djava.io.tmpdir=${TMPDIR}" ;;
esac

# Re-entrant flock wrapper for 2-vCPU / 0-swap VM concurrency safety (never passes --no-daemon)
run_gradlew() {
  if [[ "${WIFIRADAR_GRADLE_LOCK_HELD:-0}" == "1" ]]; then
    "${ROOT_DIR}/gradlew" -p "${ROOT_DIR}" "$@"
  else
    WIFIRADAR_GRADLE_LOCK_HELD=1 flock -w 600 "${TMPDIR}/gradle.lock" \
      "${ROOT_DIR}/gradlew" -p "${ROOT_DIR}" "$@"
  fi
}

usage() {
  cat <<'EOF'
Usage: ./e2e-tests/run_e2e.sh [OPTIONS]

Options:
  --tier <1|2|3|4|5|all>   Execute the specified E2E test tier (default: all)
  --feature <F01..F36>     Filter execution to a specific feature ID across Tiers 1-3
  --compile-only           Verify zero internal imports + compile :e2e-tests Kotlin only
  --help, -h               Show this usage guide
EOF
}

TIER="all"
FEATURE=""
COMPILE_ONLY="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tier)
      [[ $# -ge 2 ]] || { echo "ERROR: --tier requires an argument" >&2; exit 2; }
      TIER="$2"
      shift 2
      ;;
    --tier=*)
      TIER="${1#*=}"
      shift
      ;;
    --feature)
      [[ $# -ge 2 ]] || { echo "ERROR: --feature requires an argument (F01..F36)" >&2; exit 2; }
      FEATURE="$2"
      shift 2
      ;;
    --feature=*)
      FEATURE="${1#*=}"
      shift
      ;;
    --compile-only)
      COMPILE_ONLY="true"
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: Unknown option '$1'" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -n "${FEATURE}" ]]; then
  if [[ ! "${FEATURE}" =~ ^[Ff](0[1-9]|[12][0-9]|3[0-6])$ ]]; then
    echo "ERROR: Invalid --feature '${FEATURE}'. Expected F01..F36." >&2
    exit 2
  fi
fi

echo "[e2e] Checking strict opaque-box zero-import boundary..."
if [[ -d "${E2E_SRC_DIR}" ]]; then
  if grep -rnE "^\s*import\s+com\.a55\.wifiradar\.(core|feature|cli|MainActivity)" "${E2E_SRC_DIR}"; then
    echo "FATAL: Forbidden internal import detected in :e2e-tests!" >&2
    exit 2
  fi
fi
echo "[e2e] Opaque-box zero-import check PASSED."

if [[ "${COMPILE_ONLY}" == "true" ]]; then
  echo "[e2e] Compiling :e2e-tests in strict isolation (:e2e-tests:compileDebugUnitTestKotlin)..."
  run_gradlew :e2e-tests:compileDebugUnitTestKotlin --max-workers=2
  echo "[e2e] --compile-only SUCCEEDED."
  exit 0
fi

echo "[e2e] Preparing production artifacts (:cli-runner:installDist, :app:assembleDebug, :app:assembleRelease)..."
run_gradlew \
  :cli-runner:installDist \
  :app:assembleDebug \
  :app:assembleRelease \
  --max-workers=2

if [[ -n "${FEATURE}" ]]; then
  f_lower="$(printf '%s' "${FEATURE}" | tr '[:upper:]' '[:lower:]')"
  f_upper="$(printf '%s' "${FEATURE}" | tr '[:lower:]' '[:upper:]')"
  echo "[e2e] Running feature ${f_upper} across Tier 1, Tier 2, and Tier 3..."
  run_gradlew :e2e-tests:testDebugUnitTest \
    --tests "com.a55.wifiradar.e2e.tier1.*.${f_lower}_*" \
    --tests "com.a55.wifiradar.e2e.tier2.*.${f_lower}_*" \
    --tests "com.a55.wifiradar.e2e.tier3.*.*_${f_lower}_*" \
    --rerun-tasks \
    --max-workers=2
  echo "[e2e] Completed --feature ${f_upper} successfully."
  exit 0
fi

run_tier_filter() {
  local label="$1"
  local pattern="$2"
  echo "[e2e] Running ${label} (${pattern})..."
  run_gradlew :e2e-tests:testDebugUnitTest \
    --tests "${pattern}" \
    --rerun-tasks \
    --max-workers=2
}

case "${TIER}" in
  1)
    run_tier_filter "Tier 1 (Core Happy-Path F01-F36)" "com.a55.wifiradar.e2e.tier1.*"
    ;;
  2)
    run_tier_filter "Tier 2 (Boundary, Corner & Negative F01-F36)" "com.a55.wifiradar.e2e.tier2.*"
    ;;
  3)
    run_tier_filter "Tier 3 (Cross-Feature Pairwise Matrix)" "com.a55.wifiradar.e2e.tier3.*"
    ;;
  4)
    run_tier_filter "Tier 4 (Galaxy A55 5G Real-World Journeys)" "com.a55.wifiradar.e2e.tier4.*"
    ;;
  5)
    if compgen -G "${E2E_SRC_DIR}/test/kotlin/com/a55/wifiradar/e2e/tier5/*.kt" > /dev/null; then
      run_tier_filter "Tier 5 (M7 Adversarial Coverage Hardening)" "com.a55.wifiradar.e2e.tier5.*"
    else
      echo "[e2e] Tier 5 directory has no tests yet (reserved for M7 Phase 2)."
    fi
    ;;
  all)
    run_tier_filter "Tier 1 (Core Happy-Path F01-F36)" "com.a55.wifiradar.e2e.tier1.*"
    run_tier_filter "Tier 2 (Boundary, Corner & Negative F01-F36)" "com.a55.wifiradar.e2e.tier2.*"
    run_tier_filter "Tier 3 (Cross-Feature Pairwise Matrix)" "com.a55.wifiradar.e2e.tier3.*"
    run_tier_filter "Tier 4 (Galaxy A55 5G Real-World Journeys)" "com.a55.wifiradar.e2e.tier4.*"
    if compgen -G "${E2E_SRC_DIR}/test/kotlin/com/a55/wifiradar/e2e/tier5/*.kt" > /dev/null; then
      run_tier_filter "Tier 5 (M7 Adversarial Coverage Hardening)" "com.a55.wifiradar.e2e.tier5.*"
    fi
    ;;
  *)
    echo "ERROR: Invalid --tier '${TIER}'. Expected 1, 2, 3, 4, 5, or all." >&2
    exit 2
    ;;
esac

echo "[e2e] Completed --tier ${TIER} successfully."
