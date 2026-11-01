#!/bin/bash
# Signs the three downloads with the project's release key, for the release and beta jobs alike
# (code/shared/docs/releases.md). Run from the folder that holds them:
#   RELEASE_SIGNING_KEY=<PEM> sign-release.sh <version or label> <path to release-key.pem>
# <file>.sig is the Ed25519 signature of the file's raw bytes, and <file>.vsig the signature of its version (or label),
# name and SHA-256. Both apps refuse an update without them, so this stops on anything missing or wrong.
set -euo pipefail
VERSION=${1:-}
PUB=${2:-}
FILES="Huginn-Windows.zip Huginn.flatpak Muninn.apk"
fail() { rm -f "${k:-}"; echo "::error title=$1::$2 Nothing was published."; exit 1; }

echo "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+( beta [0-9]+)?$' \
  || fail "Version" "\"$VERSION\" isn't a version like 1.0 or a label like 1.1 beta 12."
[ -f "$PUB" ] || fail "Release key" "The public key $PUB isn't there."
for f in $FILES; do
  [ -f "$f" ] || fail "Download missing" "$f wasn't built or downloaded."
done
if [ -z "${RELEASE_SIGNING_KEY:-}" ]; then
  fail "Release signing key missing" "Add the secret RELEASE_SIGNING_KEY (Settings > Secrets and variables > Actions), then re-run this job."
fi

k="$(mktemp)"
printf '%s\n' "$RELEASE_SIGNING_KEY" | tr -d '\r' > "$k"
if ! openssl pkey -in "$k" -pubout -outform DER > "$k.pub" 2>/dev/null; then
  rm -f "$k.pub"
  fail "Release signing key unreadable" "RELEASE_SIGNING_KEY isn't a whole Ed25519 private key (PEM). Paste it again, BEGIN and END lines included."
fi
# It must be the private half of the key both apps carry.
if ! openssl pkey -pubin -in "$PUB" -outform DER | cmp -s - "$k.pub"; then
  rm -f "$k.pub"
  fail "Wrong release signing key" "RELEASE_SIGNING_KEY doesn't match $PUB, so the apps would refuse these files."
fi
rm -f "$k.pub"

for f in $FILES; do
  openssl pkeyutl -sign -inkey "$k" -rawin -in "$f" -out "$f.sig"
  openssl pkeyutl -verify -pubin -inkey "$PUB" -rawin -in "$f" -sigfile "$f.sig" > /dev/null \
    || fail "Signing failed" "$f.sig doesn't check out."
  # The version, the file's name and its SHA-256, one per line with no newline at the end.
  m="$(mktemp)"
  printf 'huginn-muninn release v1\n%s\n%s\n%s' "$VERSION" "$f" "$(sha256sum < "$f" | cut -d' ' -f1)" > "$m"
  openssl pkeyutl -sign -inkey "$k" -rawin -in "$m" -out "$f.vsig"
  openssl pkeyutl -verify -pubin -inkey "$PUB" -rawin -in "$m" -sigfile "$f.vsig" > /dev/null \
    || { rm -f "$m"; fail "Signing failed" "$f.vsig doesn't check out."; }
  rm -f "$m"
  [ "$(stat -c %s "$f.vsig")" = 64 ] || fail "Signing failed" "$f.vsig isn't 64 bytes."
done
rm -f "$k"
sha256sum $FILES
echo "Signed $FILES for $VERSION."
