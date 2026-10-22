#!/usr/bin/env bash
# Isolated launcher/certificate smoke tests; never read the real .env.local.
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
test_root="$(mktemp -d)"
trap 'rm -rf "$test_root"' EXIT
project="$test_root/project with spaces"
mkdir -p "$project/scripts" "$test_root/shims" "$test_root/JDK with spaces/bin"
cp "$repo_dir/run-local.sh" "$project/"
cp "$repo_dir/scripts/generate-local-cert.sh" "$project/scripts/"
printf 'TEST_ENV_LOADED=yes\n' > "$project/.env.local"
cat > "$project/mvnw" <<'WRAPPER'
#!/usr/bin/env bash
set -eu
[[ "${TEST_ENV_LOADED:-}" == yes ]]
[[ "$1" == spring-boot:run ]]
printf '%s\n' "$JAVA_HOME" > selected-java-home
WRAPPER
chmod +x "$project/mvnw"
cat > "$test_root/JDK with spaces/bin/java" <<'JAVA'
#!/usr/bin/env bash
if [[ "${1:-}" == -XshowSettings:properties ]]; then
  printf '    java.home = %s\n' "$TEST_JDK_HOME" >&2
fi
printf 'openjdk version "%s.0.1"\n' "${TEST_JAVA_VERSION:-17}" >&2
JAVA
printf '#!/usr/bin/env bash\nexit 0\n' > "$test_root/JDK with spaces/bin/javac"
chmod +x "$test_root/JDK with spaces/bin/"*
export TEST_JDK_HOME="$test_root/JDK with spaces"
# A shim is deliberately outside the JDK. Inferring its parent is incorrect.
cp "$TEST_JDK_HOME/bin/java" "$test_root/shims/java"
printf '#!/usr/bin/env bash\nprintf "Linux\\n"\n' > "$test_root/shims/uname"
chmod +x "$test_root/shims/"*
(
  unset JAVA_HOME JAVA_17_HOME
  export PATH="$test_root/shims:$PATH"
  bash "$project/run-local.sh"
)
[[ "$(cat "$project/selected-java-home")" == "$TEST_JDK_HOME" ]] || {
  echo "FAIL: PATH shim was mistaken for the JDK home" >&2; exit 1;
}
echo 'PASS: resolve the JDK behind a PATH shim'

for version in 17 21; do
  (unset JAVA_17_HOME; JAVA_HOME="$TEST_JDK_HOME" TEST_JAVA_VERSION="$version" bash "$project/run-local.sh")
  [[ "$(cat "$project/selected-java-home")" == "$TEST_JDK_HOME" ]]
done
echo 'PASS: explicit Java 17/21 home with spaces'
JAVA_17_HOME="$TEST_JDK_HOME" JAVA_HOME="$test_root/missing" bash "$project/run-local.sh"
[[ "$(cat "$project/selected-java-home")" == "$TEST_JDK_HOME" ]]
echo 'PASS: JAVA_17_HOME takes precedence'

expect_failure() {
  if "$@" > "$test_root/error.log" 2>&1; then
    echo 'FAIL: invalid Java configuration was accepted' >&2; exit 1
  fi
}
expect_failure env JAVA_17_HOME= JAVA_HOME="$test_root/missing" bash "$project/run-local.sh"
expect_failure env JAVA_17_HOME= JAVA_HOME="$TEST_JDK_HOME" TEST_JAVA_VERSION=25 bash "$project/run-local.sh"
mv "$TEST_JDK_HOME/bin/javac" "$TEST_JDK_HOME/bin/javac.disabled"
expect_failure env JAVA_17_HOME= JAVA_HOME="$TEST_JDK_HOME" bash "$project/run-local.sh"
mv "$TEST_JDK_HOME/bin/javac.disabled" "$TEST_JDK_HOME/bin/javac"
echo 'PASS: reject invalid home, unsupported Java, and a JRE without javac'

# Check actual platform discovery with the host JDK, only replacing Maven.
(unset JAVA_HOME JAVA_17_HOME; bash "$project/run-local.sh")
actual_home="$(cat "$project/selected-java-home")"
[[ -x "$actual_home/bin/java" && -x "$actual_home/bin/javac" && "$actual_home" != /usr ]]
echo 'PASS: actual host JDK discovery'
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    for style in -m -w; do
      (unset JAVA_17_HOME; JAVA_HOME="$(cygpath "$style" "$actual_home")" bash "$project/run-local.sh")
      [[ "$(cat "$project/selected-java-home")" == "$actual_home" ]]
    done
    echo 'PASS: native Windows JAVA_HOME with forward/back slashes'
    ;;
esac

# Execute directly so a lost Unix executable bit fails this test.
(unset JAVA_17_HOME; JAVA_HOME="$TEST_JDK_HOME" "$project/run-local.sh")
"$project/scripts/generate-local-cert.sh"
cert_dir="$project/.certs"
openssl verify -CAfile "$cert_dir/local-ca.crt" -verify_hostname localhost "$cert_dir/localhost.crt"
openssl verify -CAfile "$cert_dir/local-ca.crt" -verify_ip 127.0.0.1 "$cert_dir/localhost.crt"
openssl verify -CAfile "$cert_dir/local-ca.crt" -verify_ip ::1 "$cert_dir/localhost.crt"
[[ "$(openssl x509 -in "$cert_dir/localhost.crt" -noout -subject -nameopt RFC2253)" == 'subject=CN=localhost' ]]
[[ "$(openssl x509 -in "$cert_dir/local-ca.crt" -noout -subject -nameopt RFC2253)" == 'subject=CN=mcp21-java-demo Local Development CA' ]]
cp "$cert_dir/local-ca.crt" "$test_root/original-ca.crt"
"$project/scripts/generate-local-cert.sh"
cmp "$cert_dir/local-ca.crt" "$test_root/original-ca.crt"
openssl verify -CAfile "$cert_dir/local-ca.crt" "$cert_dir/localhost.crt"
echo 'PASS: direct execution, certificate subjects/SANs, and CA reuse in a path with spaces'
