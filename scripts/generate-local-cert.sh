#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cert_dir="$project_dir/.certs"

mkdir -p "$cert_dir"
chmod 700 "$cert_dir" 2>/dev/null || true
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

# Exclude only certificate subjects from MSYS conversion. File paths such as
# /c/... still need conversion when passed to Windows-native OpenSSL.

if [[ ! -s "$cert_dir/local-ca.key" || ! -s "$cert_dir/local-ca.crt" ]]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
    -out "$cert_dir/local-ca.key" >/dev/null 2>&1
  MSYS2_ARG_CONV_EXCL='/CN=' openssl req -x509 -new -sha256 -days 3650 \
    -key "$cert_dir/local-ca.key" \
    -out "$cert_dir/local-ca.crt" \
    -subj "/CN=mcp21-java-demo Local Development CA" \
    -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" >/dev/null 2>&1
fi

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out "$cert_dir/localhost.key" >/dev/null 2>&1
MSYS2_ARG_CONV_EXCL='/CN=' openssl req -new -sha256 \
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

chmod 600 "$cert_dir"/*.key 2>/dev/null || true
chmod 644 "$cert_dir"/*.crt 2>/dev/null || true

echo "Created local HTTPS certificate for localhost and loopback IPs in $cert_dir"
echo ""
echo "=== 证书信任指引（必须完成） ==="
echo "macOS: 在「钥匙串访问」中导入 .certs/local-ca.crt 并设置为「始终信任」"
echo "Windows: 双击 .certs/local-ca.crt -> 点击「安装证书」 -> 选择「当前用户」 -> 选择「将所有的证书都放入下列存储」 -> 浏览选择「受信任的根证书颁发机构」 -> 确认导入"
