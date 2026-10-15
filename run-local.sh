#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd)"
env_file="$project_dir/.env.local"

java17_home="${JAVA_17_HOME:-}"
if [[ -z "$java17_home" ]]; then
  if [[ -x /opt/homebrew/opt/openjdk@17/bin/java ]]; then
    java17_home="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
  elif [[ -x /usr/local/opt/openjdk@17/bin/java ]]; then
    java17_home="/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
  elif java17_home_detected="$(/usr/libexec/java_home -v 17 2>/dev/null)"; then
    java17_home="$java17_home_detected"
  fi
fi

if [[ -z "$java17_home" || ! -x "$java17_home/bin/java" ]]; then
  echo "Java 17 was not found. Install it with: brew install openjdk@17" >&2
  exit 1
fi

java17_version="$($java17_home/bin/java -version 2>&1 | head -n 1)"
if [[ "$java17_version" != *'version "17.'* ]]; then
  echo "JAVA_17_HOME must point to Java 17, but found: $java17_version" >&2
  exit 1
fi

export JAVA_HOME="$java17_home"
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
