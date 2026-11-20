#!/bin/sh
# Write a local CA and per-service certificates for the mTLS overlay.
set -eu
out="${1:-certs/dev}"
mkdir -p "$out"
if [ ! -f "$out/ca.crt" ]; then
  openssl req -x509 -newkey rsa:2048 -nodes -keyout "$out/ca.key" -out "$out/ca.crt" -days 365 -subj "/CN=banking-dev-ca"
fi
for name in kyc-service cards-service fraud-service frontend; do
  openssl req -newkey rsa:2048 -nodes -keyout "$out/$name.key" -out "$out/$name.csr" -subj "/CN=$name"
  cat > "$out/$name.ext" <<EOF
subjectAltName=DNS:$name,DNS:localhost,IP:127.0.0.1
extendedKeyUsage=serverAuth,clientAuth
EOF
  openssl x509 -req -in "$out/$name.csr" -CA "$out/ca.crt" -CAkey "$out/ca.key" -CAcreateserial -out "$out/$name.crt" -days 365 -extfile "$out/$name.ext"
  rm "$out/$name.csr" "$out/$name.ext"
done
echo "Certificates are in $out"
