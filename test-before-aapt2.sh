#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE_EOF'
Usage: ./test-before-aapt2.sh [module] [--pre-only|--from-aapt2|--full] [--apk-debug|--aab-debug|--aab-release] [--clean]
  module         Android module name (default: app)
  --pre-only     Run only pre-AAPT2 compile-oriented checks
  --from-aapt2   Run AAPT2 and later tasks only
  --full         Run both phases (default)
  --apk-debug    Build debug APK (default)
  --aab-debug    Build debug AAB locally
  --aab-release  Build release AAB locally (requires signing if the project config does)
  --clean        Clean build artifacts before and after the run; restore generated local.properties on exit
USAGE_EOF
}

REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$REPO_ROOT"
PROJECT_NAME="$(basename "$REPO_ROOT")"

MODULE="app"
MODE="full"
ARTIFACT_KIND="apk"
ARTIFACT_VARIANT="debug"
CLEAN_ON_EXIT=0

while [ $# -gt 0 ]; do
  case "$1" in
    --pre-only)
      MODE="pre"
      ;;
    --from-aapt2|--post-only)
      MODE="post"
      ;;
    --full)
      MODE="full"
      ;;
    --apk-debug)
      ARTIFACT_KIND="apk"
      ARTIFACT_VARIANT="debug"
      ;;
    --aab-debug)
      ARTIFACT_KIND="aab"
      ARTIFACT_VARIANT="debug"
      ;;
    --aab-release)
      ARTIFACT_KIND="aab"
      ARTIFACT_VARIANT="release"
      ;;
    --clean)
      CLEAN_ON_EXIT=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      MODULE="$1"
      ;;
  esac
  shift
done

detect_java_home() {
  local candidate
  for candidate in \
    "${JDK_DIR:-}" \
    "${JAVA_HOME:-}" \
    "$HOME/.local/jdk-17" \
    "/usr/lib/jvm/java-17-openjdk-amd64" \
    "/usr/lib/jvm/java-17-openjdk" \
    "/usr/lib/jvm/default-java"
  do
    if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ]; then
      printf '%s' "$candidate"
      return 0
    fi
  done
  return 1
}

JAVA_HOME_CANDIDATE="$(detect_java_home || true)"
if [ -n "$JAVA_HOME_CANDIDATE" ]; then
  export JAVA_HOME="$JAVA_HOME_CANDIDATE"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

detect_sdk_root() {
  local candidate
  for candidate in \
    "${ANDROID_SDK_ROOT:-}" \
    "${ANDROID_HOME:-}" \
    "$HOME/android-sdk" \
    "/opt/android-sdk" \
    "$HOME/Android/Sdk"
  do
    if [ -n "$candidate" ] && [ -d "$candidate" ]; then
      printf '%s' "$candidate"
      return 0
    fi
  done
  return 1
}

ANDROID_SDK_DIR="$(detect_sdk_root || true)"
if [ -z "$ANDROID_SDK_DIR" ]; then
  echo "Android SDK not found. Set ANDROID_SDK_ROOT or install it under /opt/android-sdk or ~/android-sdk." >&2
  exit 2
fi
export ANDROID_SDK_ROOT="$ANDROID_SDK_DIR"
export ANDROID_HOME="$ANDROID_SDK_DIR"

if [ -f "./gradlew" ]; then
  chmod +x ./gradlew || true
  GRADLE_RUNNER="./gradlew"
else
  GRADLE_RUNNER="$(command -v gradle || true)"
fi

if [ -z "$GRADLE_RUNNER" ]; then
  echo "No Gradle runner found (expected ./gradlew or gradle in PATH)." >&2
  exit 127
fi

LOG_DIR="$REPO_ROOT/test_logs_$(date +%Y%m%d%H%M%S)"
mkdir -p "$LOG_DIR"
DOWNLOADS_DIR="${APK_OUTPUT_DIR:-$HOME/downloads}"
mkdir -p "$DOWNLOADS_DIR"

MODULE_BUILD_FILE=""
if [ -f "$REPO_ROOT/$MODULE/build.gradle" ]; then
  MODULE_BUILD_FILE="$REPO_ROOT/$MODULE/build.gradle"
elif [ -f "$REPO_ROOT/$MODULE/build.gradle.kts" ]; then
  MODULE_BUILD_FILE="$REPO_ROOT/$MODULE/build.gradle.kts"
fi

REQUESTED_NDK_VERSION=""
if [ -n "$MODULE_BUILD_FILE" ]; then
  REQUESTED_NDK_VERSION="$(sed -nE "s/.*ndkVersion[[:space:]=]+['\"]([^'\"]+)['\"].*/\1/p" "$MODULE_BUILD_FILE" | head -n 1)"
fi

if [ -n "$REQUESTED_NDK_VERSION" ]; then
  if [ -d "$ANDROID_SDK_ROOT/ndk/$REQUESTED_NDK_VERSION" ]; then
    echo "NDK: requested $REQUESTED_NDK_VERSION (available)"
  else
    echo "NDK: requested $REQUESTED_NDK_VERSION but not installed under $ANDROID_SDK_ROOT/ndk" >&2
  fi
elif [ -d "$ANDROID_SDK_ROOT/ndk" ]; then
  INSTALLED_NDK="$(find "$ANDROID_SDK_ROOT/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1 | xargs -r basename)"
  if [ -n "$INSTALLED_NDK" ]; then
    echo "NDK: using installed $INSTALLED_NDK when the project does not pin one"
  fi
fi

GENERATED_LOCAL_PROPERTIES=0
RESTORE_LOCAL_PROPERTIES=0
ORIGINAL_LOCAL_PROPERTIES=""
EXPECTED_SDK_LINE="sdk.dir=$ANDROID_SDK_ROOT"
if [ -f "$REPO_ROOT/local.properties" ]; then
  CURRENT_SDK_LINE="$(sed -n 's/^sdk\.dir=//p' "$REPO_ROOT/local.properties" | head -n 1 || true)"
  if [ "$CURRENT_SDK_LINE" != "$ANDROID_SDK_ROOT" ]; then
    ORIGINAL_LOCAL_PROPERTIES="$LOG_DIR/local.properties.before"
    cp "$REPO_ROOT/local.properties" "$ORIGINAL_LOCAL_PROPERTIES"
    RESTORE_LOCAL_PROPERTIES=1
    printf '%s\n' "$EXPECTED_SDK_LINE" > "$REPO_ROOT/local.properties"
    GENERATED_LOCAL_PROPERTIES=1
  fi
else
  printf '%s\n' "$EXPECTED_SDK_LINE" > "$REPO_ROOT/local.properties"
  GENERATED_LOCAL_PROPERTIES=1
fi

resolve_aapt2_bin() {
  if [ -n "${AAPT2_BIN:-}" ] && [ -x "$AAPT2_BIN" ]; then
    printf '%s' "$AAPT2_BIN"
    return 0
  fi
  if [ -d "$ANDROID_SDK_ROOT/build-tools" ]; then
    find "$ANDROID_SDK_ROOT/build-tools" -mindepth 2 -maxdepth 2 -type f -name aapt2 2>/dev/null | sort -V | tail -n 1
  fi
}

AAPT2_OVERRIDE="$(resolve_aapt2_bin || true)"
if [ -n "$AAPT2_OVERRIDE" ] && ! "$AAPT2_OVERRIDE" version >/dev/null 2>&1; then
  AAPT2_OVERRIDE=""
fi

if [ -n "$MODULE_BUILD_FILE" ] && grep -q 'externalNativeBuild' "$MODULE_BUILD_FILE"; then
  CMAKE_BIN="$(find "$ANDROID_SDK_ROOT/cmake" -mindepth 3 -maxdepth 3 -type f -path '*/bin/cmake' 2>/dev/null | sort -V | tail -n 1 || true)"
  if [ -n "$CMAKE_BIN" ] && ! "$CMAKE_BIN" --version >/dev/null 2>&1; then
    echo "Warning: SDK CMake binary is not executable on this host: $CMAKE_BIN" >&2
    echo "         Native builds may still fail even if AAPT2 is fixed." >&2
  fi
fi

echo "Repository: $REPO_ROOT"
echo "Module: $MODULE"
echo "Mode: $MODE"
echo "Artifact: $ARTIFACT_KIND/$ARTIFACT_VARIANT"
echo "Gradle runner: $GRADLE_RUNNER"
echo "SDK: $ANDROID_SDK_ROOT"
if [ -n "${JAVA_HOME:-}" ]; then
  echo "JAVA_HOME: $JAVA_HOME"
fi
if [ -n "$AAPT2_OVERRIDE" ]; then
  echo "AAPT2 override: $AAPT2_OVERRIDE"
else
  echo "AAPT2 override: unavailable; using AGP default"
fi
java -version 2>&1 | sed -n '1,2p' || true

GRADLE_COMMON_ARGS=(--no-daemon --console=plain)
GRADLE_PROP_ARGS=()
if [ -n "$AAPT2_OVERRIDE" ]; then
  GRADLE_PROP_ARGS+=("-Pandroid.aapt2FromMavenOverride=$AAPT2_OVERRIDE")
fi

PRE_TASKS=(":$MODULE:compileDebugJavaWithJavac" ":$MODULE:compileDebugUnitTestJavaWithJavac")
POST_TASKS=(":$MODULE:processDebugResources")
ARTIFACT_TASK=":$MODULE:assembleDebug"
ARTIFACT_GLOB="$REPO_ROOT/$MODULE/build/outputs/apk/debug/*.apk"

if [ "$ARTIFACT_KIND" = "aab" ] && [ "$ARTIFACT_VARIANT" = "debug" ]; then
  ARTIFACT_TASK=":$MODULE:bundleDebug"
  ARTIFACT_GLOB="$REPO_ROOT/$MODULE/build/outputs/bundle/debug/*.aab"
elif [ "$ARTIFACT_KIND" = "aab" ] && [ "$ARTIFACT_VARIANT" = "release" ]; then
  ARTIFACT_TASK=":$MODULE:bundleRelease"
  ARTIFACT_GLOB="$REPO_ROOT/$MODULE/build/outputs/bundle/release/*.aab"
fi
POST_TASKS+=("$ARTIFACT_TASK" ":$MODULE:testDebugUnitTest")

run_gradle_tasks() {
  local logfile="$1"
  shift
  local cmd=("$GRADLE_RUNNER" "${GRADLE_PROP_ARGS[@]}" "$@" "${GRADLE_COMMON_ARGS[@]}")
  echo "Running: ${cmd[*]}"
  "${cmd[@]}" >"$logfile" 2>&1
}

cleanup() {
  if [ "$CLEAN_ON_EXIT" -eq 1 ]; then
    rm -rf "$REPO_ROOT/$MODULE/build" "$REPO_ROOT/build" "$REPO_ROOT/.gradle" || true
    if [ "$GENERATED_LOCAL_PROPERTIES" -eq 1 ]; then
      if [ "$RESTORE_LOCAL_PROPERTIES" -eq 1 ] && [ -f "$ORIGINAL_LOCAL_PROPERTIES" ]; then
        cp "$ORIGINAL_LOCAL_PROPERTIES" "$REPO_ROOT/local.properties"
      else
        rm -f "$REPO_ROOT/local.properties" || true
      fi
    fi
  fi
}
trap cleanup EXIT

if [ "$CLEAN_ON_EXIT" -eq 1 ]; then
  rm -rf "$REPO_ROOT/$MODULE/build" "$REPO_ROOT/build" "$REPO_ROOT/.gradle" || true
fi

RC=0
if [ "$MODE" = "pre" ] || [ "$MODE" = "full" ]; then
  if run_gradle_tasks "$LOG_DIR/pre-aapt2.log" "${PRE_TASKS[@]}"; then
    :
  else
    RC=$?
  fi
fi

if [ "$RC" -eq 0 ] && { [ "$MODE" = "post" ] || [ "$MODE" = "full" ]; }; then
  if run_gradle_tasks "$LOG_DIR/post-aapt2.log" "${POST_TASKS[@]}"; then
    :
  else
    RC=$?
  fi
fi

ARTIFACT_DIR="${ARTIFACT_GLOB%/*}"
ARTIFACT_PATTERN="${ARTIFACT_GLOB##*/}"
ARTIFACT_PATH="$(find "$ARTIFACT_DIR" -maxdepth 1 -type f -name "$ARTIFACT_PATTERN" 2>/dev/null | sort | tail -n 1 || true)"
if [ "$RC" -eq 0 ] && [ -n "$ARTIFACT_PATH" ]; then
  DEST="$DOWNLOADS_DIR/$PROJECT_NAME-$(basename "$ARTIFACT_PATH")"
  cp -f "$ARTIFACT_PATH" "$DEST"
  echo "Artifact copied to: $DEST"
fi

echo "Logs saved to: $LOG_DIR"
if [ "$RC" -ne 0 ]; then
  echo "Build/test tasks failed. Inspect logs in $LOG_DIR." >&2
fi

exit "$RC"
