#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd)"
env_file="$project_dir/.env.local"

# Explicit configuration wins; reject a bad value rather than silently ignoring it.
jdk_home="${JAVA_17_HOME:-${JAVA_HOME:-}}"
platform="$(uname -s)"
if [[ -z "$jdk_home" && "$platform" == Darwin ]]; then
  # /usr/bin/java is a macOS launcher, not a JDK under /usr.
  for version in 17 21; do
    if detected_home="$(/usr/libexec/java_home -F -v "$version" 2>/dev/null)"; then
      jdk_home="$detected_home"
      break
    fi
  done
  # Homebrew JDKs need not be registered with java_home.
  if [[ -z "$jdk_home" ]]; then
    for version in 17 21; do
      for prefix in /opt/homebrew /usr/local; do
        candidate="$prefix/opt/openjdk@$version/libexec/openjdk.jdk/Contents/Home"
        if [[ -x "$candidate/bin/java" && -x "$candidate/bin/javac" ]]; then
          jdk_home="$candidate"
          break 2
        fi
      done
    done
  fi
fi

if [[ -z "$jdk_home" ]] && command -v java >/dev/null 2>&1; then
  # PATH can contain a symlink/shim. Ask Java for its actual runtime home.
  if java_settings="$(java -XshowSettings:properties -version 2>&1)"; then
    jdk_home="$(printf '%s\n' "$java_settings" | sed -n 's/^[[:space:]]*java\.home = //p' | tr -d '\r')"
  fi
fi

if [[ -z "$jdk_home" ]]; then
  echo "Java 17 or 21 was not found. Please install JDK 17/21 and set JAVA_HOME." >&2
  exit 1
fi

# Normalize C:\Program Files\... before adding it to Git Bash's colon-separated PATH.
case "$platform" in
  CYGWIN*|MINGW*|MSYS*) jdk_home="$(cygpath -u "$jdk_home")" ;;
esac
if [[ ! -x "$jdk_home/bin/java" || ! -x "$jdk_home/bin/javac" ]]; then
  echo "JAVA_17_HOME/JAVA_HOME must point to a JDK 17/21 containing bin/java and bin/javac: $jdk_home" >&2
  exit 1
fi

java_version="$("$jdk_home/bin/java" -version 2>&1)"
java_version="${java_version%%$'\n'*}"
if [[ "$java_version" != *'version "17.'* && "$java_version" != *'version "21.'* ]]; then
  echo "Java version must be 17 or 21, but found: $java_version" >&2
  exit 1
fi

export JAVA_HOME="$jdk_home"
export PATH="$JAVA_HOME/bin:$PATH"

if [[ ! -f "$env_file" ]]; then
  echo "Missing $env_file. Copy .env.local.example and fill in the Feishu credentials." >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$env_file"
set +a

cd "$project_dir"
exec ./mvnw spring-boot:run
