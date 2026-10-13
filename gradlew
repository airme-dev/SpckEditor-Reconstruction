#!/usr/bin/env sh
# Checksum-pinned Gradle distribution bootstrap. This is not Gradle's official
# wrapper JAR launcher; it downloads the declared distribution and verifies SHA-256.
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROPERTIES="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"
if [ ! -f "$PROPERTIES" ]; then
  echo "ERROR: missing $PROPERTIES" >&2
  exit 1
fi

property() {
  key=$1
  sed -n "s/^${key}=//p" "$PROPERTIES" | head -n 1 | sed 's/\\:/:/g'
}
DIST_URL=$(property distributionUrl)
EXPECTED_SHA=$(property distributionSha256Sum | tr 'A-F' 'a-f')
case "$DIST_URL" in https://services.gradle.org/distributions/gradle-*-bin.zip) ;; *) echo "ERROR: unexpected Gradle distribution URL: $DIST_URL" >&2; exit 1 ;; esac
case "$EXPECTED_SHA" in *[!0-9a-f]*|'') echo "ERROR: invalid distribution SHA-256 in properties" >&2; exit 1 ;; esac
[ "${#EXPECTED_SHA}" -eq 64 ] || { echo "ERROR: distribution SHA-256 must contain 64 hex characters" >&2; exit 1; }
ARCHIVE=${DIST_URL##*/}
VERSION=${ARCHIVE#gradle-}
VERSION=${VERSION%-bin.zip}
GRADLE_USER_HOME=${GRADLE_USER_HOME:-"$HOME/.gradle"}
CACHE_ROOT="$GRADLE_USER_HOME/reconstruction-bootstrap"
GRADLE_HOME="$CACHE_ROOT/gradle-$VERSION"
GRADLE_BIN="$GRADLE_HOME/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$CACHE_ROOT"
  TMP_ROOT=$(mktemp -d "$CACHE_ROOT/.install-$VERSION.XXXXXX")
  cleanup() { rm -rf "$TMP_ROOT"; }
  trap cleanup EXIT HUP INT TERM
  ZIP_PATH="$TMP_ROOT/$ARCHIVE"
  echo "Bootstrapping Gradle $VERSION (SHA-256 verified) ..." >&2
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --silent --show-error --retry 2 --output "$ZIP_PATH" "$DIST_URL"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP_PATH" "$DIST_URL"
  else
    echo "ERROR: curl or wget is required to download Gradle $VERSION" >&2
    exit 1
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL_SHA=$(sha256sum "$ZIP_PATH" | awk '{print $1}')
  elif command -v shasum >/dev/null 2>&1; then
    ACTUAL_SHA=$(shasum -a 256 "$ZIP_PATH" | awk '{print $1}')
  elif command -v openssl >/dev/null 2>&1; then
    ACTUAL_SHA=$(openssl dgst -sha256 "$ZIP_PATH" | awk '{print $NF}')
  else
    echo "ERROR: sha256sum, shasum, or openssl is required to verify Gradle" >&2
    exit 1
  fi
  ACTUAL_SHA=$(printf '%s' "$ACTUAL_SHA" | tr 'A-F' 'a-f')
  if [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]; then
    echo "ERROR: Gradle distribution checksum mismatch" >&2
    echo "Expected: $EXPECTED_SHA" >&2
    echo "Actual:   $ACTUAL_SHA" >&2
    exit 1
  fi
  mkdir "$TMP_ROOT/unpacked"
  unzip -q "$ZIP_PATH" -d "$TMP_ROOT/unpacked"
  if [ ! -x "$TMP_ROOT/unpacked/gradle-$VERSION/bin/gradle" ]; then
    echo "ERROR: downloaded archive does not contain the expected Gradle executable" >&2
    exit 1
  fi
  if [ ! -x "$GRADLE_BIN" ]; then
    if ! mv "$TMP_ROOT/unpacked/gradle-$VERSION" "$GRADLE_HOME" 2>/dev/null; then
      # Another concurrent bootstrap may have populated the same cache path.
      [ -x "$GRADLE_BIN" ] || { echo "ERROR: could not install Gradle into $GRADLE_HOME" >&2; exit 1; }
    fi
  fi
  cleanup
  trap - EXIT HUP INT TERM
fi
exec "$GRADLE_BIN" "$@"
