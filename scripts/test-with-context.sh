#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTEXT_FILE="$ROOT_DIR/docs/AI_DEVELOPMENT_CONTEXT.md"
TESTING_CONTEXT_FILE="$ROOT_DIR/docs/TESTING_CONTEXT.md"
ANDROID_STUDIO_JDK="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

if [[ ! -s "$CONTEXT_FILE" ]]; then
  echo "Missing or empty: $CONTEXT_FILE" >&2
  exit 2
fi

if [[ ! -s "$TESTING_CONTEXT_FILE" ]]; then
  echo "Missing or empty: $TESTING_CONTEXT_FILE" >&2
  exit 2
fi

cd "$ROOT_DIR"

# Project tests must use Android Studio's bundled JDK 17. Do not inherit a
# machine-level JDK 8/23 setting when the required JDK is installed.
if [[ -d "$ANDROID_STUDIO_JDK" ]]; then
  export JAVA_HOME="$ANDROID_STUDIO_JDK"
fi

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "Android Studio JDK 17 is required. Set JAVA_HOME before testing." >&2
  exit 2
fi

JAVA_VERSION="$("$JAVA_HOME/bin/java" -version 2>&1 | sed -n '1p')"
if [[ "$JAVA_VERSION" != *'version "17'* ]]; then
  echo "Expected Java 17 but found: $JAVA_VERSION" >&2
  exit 2
fi

echo "Testing context checked: docs/AI_DEVELOPMENT_CONTEXT.md"
echo "Testing context checked: docs/TESTING_CONTEXT.md"
echo "JAVA_HOME: $JAVA_HOME"
echo "Workspace: $ROOT_DIR"
echo "Working tree:"
git status --short

if (( $# == 0 )); then
  set -- \
    :core:common:testDebugUnitTest \
    :core:network:testDebugUnitTest \
    :core:database:testDebugUnitTest \
    :app:testDebugUnitTest \
    --console=plain
fi

echo "Running: ./gradlew $*"
exec "$ROOT_DIR/gradlew" "$@"
