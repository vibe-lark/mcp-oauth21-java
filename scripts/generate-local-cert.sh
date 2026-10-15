#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cert_dir="$project_dir/.certs"

mkdir -p "$cert_dir"
chmod 700 "$cert_dir"
umask 077

rm -f "$cert_dir/local-ca.srl" "$cert_dir/localhost.crt" "$cert_dir/localhost.csr"

cat > "$cert_dir/localhost.ext" <<'EOF'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=@alt_names

[alt_names]
DNS.1=localhost
IP.1=127.0.0.1
IP.2=::1
EOF

if [[ ! -s "$cert_dir/local-ca.key" || ! -s "$cert_dir/local-ca.crt" ]]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
    -out "$cert_dir/local-ca.key" >/dev/null 2>&1
  openssl req -x509 -new -sha256 -days 3650 \
    -key "$cert_dir/local-ca.key" \
    -out "$cert_dir/local-ca.crt" \
    -subj "/CN=mcp21-java-demo Local Development CA" \
    -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" >/dev/null 2>&1
fi

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out "$cert_dir/localhost.key" >/dev/null 2>&1
openssl req -new -sha256 \
  -key "$cert_dir/localhost.key" \
  -out "$cert_dir/localhost.csr" \
  -subj "/CN=localhost" >/dev/null 2>&1
openssl x509 -req -sha256 -days 825 \
  -in "$cert_dir/localhost.csr" \
  -CA "$cert_dir/local-ca.crt" \
  -CAkey "$cert_dir/local-ca.key" \
  -CAcreateserial \
  -out "$cert_dir/localhost.crt" \
  -extfile "$cert_dir/localhost.ext" >/dev/null 2>&1

chmod 600 "$cert_dir"/*.key
chmod 644 "$cert_dir"/*.crt

echo "Created local HTTPS certificate for localhost and loopback IPs in $cert_dir"
