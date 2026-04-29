#!/usr/bin/env bash
# Generates disposable RSA-2048 development keys for every user and the
# sequencer, in the formats the Java code expects (PKCS#8 / X.509 DER).
# Keys are git-ignored. Never reuse them outside local development.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
USERS=(BC Alice Bob Carol Charlie David Emma Fred Ginger Henry Iris)
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mkdir -p "$ROOT/client/src/main/resources" "$ROOT/node/src/main/resources/seq" "$ROOT/sequencer/src/main/resources"

gen() { # name priv_out pub_out
  openssl genrsa -out "$TMP/k.pem" 2048 2>/dev/null
  openssl pkcs8 -topk8 -inform PEM -outform DER -in "$TMP/k.pem" -out "$2" -nocrypt
  openssl rsa -in "$TMP/k.pem" -pubout -outform DER -out "$3" 2>/dev/null
  rm -f "$TMP/k.pem"
  echo "generated $1"
}
for u in "${USERS[@]}"; do
  gen "$u" "$ROOT/client/src/main/resources/$u.priv" "$ROOT/node/src/main/resources/$u.pub"
done
gen Seq "$ROOT/sequencer/src/main/resources/Seq.priv" "$ROOT/node/src/main/resources/seq/Seq.pub"
