#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd)"
env_file="$project_dir/.env.local"

# 1. 优先使用传入的 JAVA_17_HOME 或已有的 JAVA_HOME
java_bin=""
if [[ -n "${JAVA_17_HOME:-}" && -x "$JAVA_17_HOME/bin/java" ]]; then
  java_bin="$JAVA_17_HOME/bin/java"
  export JAVA_HOME="$JAVA_17_HOME"
elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  java_bin="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  java_bin="$(command -v java)"
elif [[ -x /opt/homebrew/opt/openjdk@17/bin/java ]]; then
  export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
  java_bin="/opt/homebrew/opt/openjdk@17/bin/java"
elif [[ -x /usr/local/opt/openjdk@17/bin/java ]]; then
  export JAVA_HOME="/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
  java_bin="/usr/local/opt/openjdk@17/bin/java"
elif [[ -x /usr/libexec/java_home ]] && java17_home_detected="$(/usr/libexec/java_home -v 17 2>/dev/null)"; then
  export JAVA_HOME="$java17_home_detected"
  java_bin="$JAVA_HOME/bin/java"
fi

if [[ -z "$java_bin" ]]; then
  echo "Java 17 or 21 was not found. Please install JDK 17/21 and set JAVA_HOME." >&2
  exit 1
fi

java_version="$("$java_bin" -version 2>&1 | head -n 1)"
if [[ "$java_version" != *'version "17.'* && "$java_version" != *'version "21.'* ]]; then
  echo "Java version must be 17 or 21, but found: $java_version" >&2
  exit 1
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
  # 尝试推导 JAVA_HOME
  export JAVA_HOME="$(cd "$(dirname "$java_bin")/.." && pwd)"
fi
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
