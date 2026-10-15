#!/usr/bin/env bash
set -euo pipefail

# Public-edition release-hygiene audit.
#
# It unpacks the real APK once, extracts every printable string into one text
# file, and runs all checks against that text.
#
# Extracting first matters. Running contextual regexes directly over
# multi-megabyte dex files is pathologically slow: a single
# `grep -o -E ".{0,40}pattern.{0,40}"` pass over a release dex measured >10s and
# looked like a hang, while every check below finishes in well under a second
# against the extracted text (extraction itself is ~1.4s for a 63 MB APK).
#
# Usage: scripts/verify-public-release.sh [path/to/app.apk] [release|debug]
#   default APK:    app/build/outputs/apk/release/app-release-unsigned.apk
#   default target: release
#
# The debug target additionally expects debug-only scaffolding to be present, so
# the same script can verify both artifacts instead of only the release APK.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="${1:-$ROOT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk}"
TARGET="${2:-release}"

if [ ! -f "$APK" ]; then
  echo "APK not found: $APK" >&2
  exit 1
fi

if [ "$TARGET" != "release" ] && [ "$TARGET" != "debug" ]; then
  echo "target must be 'release' or 'debug' (got '$TARGET')" >&2
  exit 2
fi

echo "Auditing: $APK (target=$TARGET)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

echo "Unpacking..."
unzip -oq "$APK" -d "$TMP_DIR"

STRINGS="$TMP_DIR/.audit-strings.txt"
echo "Extracting printable strings..."
find "$TMP_DIR" -type f ! -name ".audit-strings.txt" -print0 \
  | xargs -0 strings -a > "$STRINGS" 2>/dev/null || true
echo "Extracted $(wc -l < "$STRINGS" | tr -d ' ') strings"

FAIL=0

# Fixed-string containment with no context window: fast, and it cannot backtrack.
scan_count() {
  grep -c -F -e "$1" "$STRINGS" 2>/dev/null || true
}

check_absent() {
  local label="$1"
  local needle="$2"
  local count
  count="$(scan_count "$needle")"
  if [ "${count:-0}" -gt 0 ]; then
    echo "[FAIL] $label ($count hits for '$needle')" >&2
    grep -m 3 -F -e "$needle" "$STRINGS" 2>/dev/null | cut -c1-160 >&2 || true
    FAIL=1
  else
    echo "[ok] $label"
  fi
}

# One exemption only: the schema migration that removes the legacy table has to
# name it inside a DROP statement. Every other mention is a live reference.
check_absent_allowing_drop() {
  local label="$1"
  local needle="$2"
  local total
  total="$(scan_count "$needle")"
  if [ "${total:-0}" -eq 0 ]; then
    echo "[ok] $label"
    return
  fi
  local live
  live="$(grep -F -e "$needle" "$STRINGS" 2>/dev/null | grep -v -F "DROP TABLE IF EXISTS" | head -3 || true)"
  if [ -n "$live" ]; then
    echo "[FAIL] $label (live reference, $total hits)" >&2
    printf '%s\n' "$live" | cut -c1-160 >&2
    FAIL=1
  else
    echo "[ok] $label (migration drop statement only)"
  fi
}

check_present() {
  local label="$1"
  local needle="$2"
  if grep -q -F -e "$needle" "$STRINGS" 2>/dev/null; then
    echo "[ok] $label"
  else
    echo "[FAIL] $label" >&2
    FAIL=1
  fi
}

# --- debug scaffolding ---------------------------------------------------------
# A release artifact must contain none of it. A debug artifact is expected to,
# so for the debug target this block asserts presence instead: that is what makes
# the debug APK useful for acceptance while still proving the release is clean.
if [ "$TARGET" = "release" ]; then
  check_absent "FakeModelService" "FakeModelService"
  check_absent "FakeMemoryEvaluator" "FakeMemoryEvaluator"
  check_absent "FakeEmbeddingService" "FakeEmbeddingService"
  check_absent "DebugSceneModelService" "DebugSceneModelService"
  check_absent "DebugActivity" "DebugActivity"
  check_absent "DEBUG_FAKE" "DEBUG_FAKE"
  check_absent "debug_entry" "debug_entry"
else
  check_present "debug scaffolding present (debug target)" "DebugCenterScreen"
  check_present "debug fakes present (debug target)" "FakeModelService"
fi

# --- secrets and build-time credentials ----------------------------------------
check_absent "minimax key buildconfig" "MINIMAX_CHAT_API_KEY"
check_absent "minimax key property path" "minimax.chat.api_key"
check_absent "qweather key buildconfig" "QWEATHER_API_KEY"
check_absent "qweather host buildconfig" "QWEATHER_API_HOST"
check_absent "private-key block" "-----BEGIN "
check_absent "authorization header literal" "Authorization: Bearer"
check_absent "google-style api key" "AIza"

# --- removed shift / schedule / geofence feature -------------------------------
check_absent "shift schedule buildconfig" "SHIFT_STATIC_JSON_URL"
check_absent_allowing_drop "shift cache table" "shift_cache"
check_absent "shift cache entity" "ShiftCacheEntity"
check_absent "shift sync" "ShiftSync"
check_absent "schedule json sync" "ScheduleJsonSyncManager"
check_absent "schedule download planner" "ScheduleDownloadPlanner"
check_absent "schedule settings store" "ScheduleSettingsStore"
check_absent "schedule url policy" "ScheduleUrlPolicy"
check_absent "location geofence store" "LocationGeofence"
check_absent "location config parser" "LocationConfigParser"
check_absent "location state tracker" "LocationStateTracker"
check_absent "location window worker" "LocationWindowWorker"
check_absent "shift event type" "SHIFT_UPCOMING"
check_absent "location transition event" "LOCATION_TRANSITION"
check_absent "fixed place constant" "PLACE_HOSPITAL"

# --- fixed coordinates must not be compiled in --------------------------------
check_absent "home coordinate buildconfig" "LOCATION_HOME_LATITUDE"
check_absent "hospital coordinate buildconfig" "LOCATION_HOSPITAL_LATITUDE"
check_absent "location radius buildconfig" "LOCATION_GEOFENCE_RADIUS_METERS"
check_absent "known debug coordinate latitude" "31.2304"
check_absent "known debug coordinate longitude" "121.4737"

# --- personalisation must not ship ---------------------------------------------
check_absent "legacy companion name" "小可乐"
check_absent "legacy user nickname" "猫猫头"
check_absent "exclusive relationship copy" "专属小狗"
check_absent "adult intimacy default" "露骨的成人张力"
check_absent "intimacy escalation instruction" "调情"
check_absent "fixed appearance default" "长发"
check_absent "shift wording in user copy" "班次"
check_absent "schedule wording in user copy" "排班"
check_absent "geofence wording in user copy" "围栏"
check_absent "background location promise" "后台同步也需要"
check_absent "background location always-allow promise" "始终允许"

# --- public edition identity ----------------------------------------------------
check_present "versionName 1.0.0" "1.0.0"
check_present "applicationId com.dogcompanion.app" "com.dogcompanion.app"

entries="$(unzip -Z1 "$APK" || true)"
if [[ "$entries" == *"classes.dex"* ]]; then
  echo "[ok] dex entries present"
else
  echo "[FAIL] no dex entries found in APK" >&2
  FAIL=1
fi

# --- coordinate pairs ----------------------------------------------------------
# Scoped to compiled code and the resource table, which is where an embedded
# coordinate literal would actually ship. Scanning every unpacked file produces
# false positives from dependency graphics/vector geometry (e.g. Compose float
# constants such as "1.41409301758,1.41409301758"), which is not a location.
COORD_PATTERN='[0-9]+\.[0-9]{4,}[ ,，]+[0-9]+\.[0-9]{4,}'
coord_hits=""
while IFS= read -r artifact; do
  hits="$(strings -a "$artifact" 2>/dev/null | grep -E -o "$COORD_PATTERN" | head -3 || true)"
  if [ -n "$hits" ]; then
    coord_hits="${coord_hits}${artifact}: ${hits}"$'\n'
  fi
done < <(find "$TMP_DIR" -type f \( -name 'classes*.dex' -o -name 'resources.arsc' \))

if [ -n "$coord_hits" ]; then
  echo "[FAIL] coordinate pair in compiled code or resources" >&2
  printf '%s' "$coord_hits" | cut -c1-160 >&2
  FAIL=1
else
  echo "[ok] coordinate pair in compiled code or resources"
fi

if [ "$FAIL" -ne 0 ]; then
  echo "Public-edition release hygiene audit FAILED." >&2
  exit 1
fi
echo "Public-edition release hygiene audit passed."
