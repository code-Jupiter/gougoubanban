#!/usr/bin/env bash
set -euo pipefail

# Portable Phase 0 build entry.
# This script only exports JAVA_HOME / ANDROID_HOME for the current process.
# It never modifies the user's shell profile or global JDK environment.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="${LITTLEKITTY_JAVA_HOME:-}"
fi

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  :
else
  echo "JAVA_HOME is not set to a JDK 17 install." >&2
  echo "Run with: JAVA_HOME=/path/to/jdk17 ./scripts/build-phase0.sh ..." >&2
  echo "Or set LITTLEKITTY_JAVA_HOME. The script does not modify existing JDK installs." >&2
  exit 1
fi
export JAVA_HOME

if [ -z "${ANDROID_HOME:-}" ] && [ -d "$HOME/Library/Android/sdk" ]; then
  export ANDROID_HOME="$HOME/Library/Android/sdk"
fi

# On machines with a read-only ~/.gradle, use a project-local Gradle user home.
# This is detected at runtime and requires no hard-coded absolute paths.
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
PROBE="$GRADLE_USER_HOME/.write-probe-$$"
if (umask 077 && mkdir -p "$GRADLE_USER_HOME" && touch "$PROBE") 2>/dev/null; then
  rm -f "$PROBE"
else
  GRADLE_USER_HOME="$ROOT_DIR/.gradle-home"
  mkdir -p "$GRADLE_USER_HOME"
fi
export GRADLE_USER_HOME

cd "$ROOT_DIR"
exec "$ROOT_DIR/gradlew" "$@"
