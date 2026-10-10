#!/usr/bin/env bash
# Compila o cliente Snowflake (Tor Project) para Android, a partir do
# código-fonte oficial. O pacote do Tor para Android não traz mais o
# Snowflake como programa separado, por isso ele é montado aqui.
#
# CGO ligado e NDK do Android: assim o programa usa o resolvedor de nomes do
# próprio Android (sem isso, programa em Go no Android não acha DNS).
#
# Uso: scripts/compilar-snowflake.sh <pasta-jniLibs>
set -euo pipefail

DESTINO="$(realpath -m "${1:?informe a pasta jniLibs}")"
NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}}"
REPO="https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake.git"
API=26

TAG="${SNOWFLAKE_VERSION:-}"
if [ -z "$TAG" ]; then
  TAG="$(git ls-remote --tags "$REPO" | grep -oE 'refs/tags/v2\.[0-9]+\.[0-9]+$' | sed 's#refs/tags/##' | sort -V | tail -1)"
fi
[ -n "$TAG" ] || { echo "::error::Não achei a versão do Snowflake."; exit 1; }
echo "Snowflake $TAG"

TRAB="$(mktemp -d)"
git clone --quiet --depth 1 --branch "$TAG" "$REPO" "$TRAB/snowflake"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

declare -A GOARCH=( [arm64-v8a]=arm64 [armeabi-v7a]=arm [x86_64]=amd64 )
declare -A CLANG=( [arm64-v8a]=aarch64-linux-android$API-clang [armeabi-v7a]=armv7a-linux-androideabi$API-clang [x86_64]=x86_64-linux-android$API-clang )

cd "$TRAB/snowflake/client"
for abi in arm64-v8a armeabi-v7a x86_64; do
  [ -d "$DESTINO/$abi" ] || continue
  extra=""
  [ "$abi" = "armeabi-v7a" ] && extra="GOARM=7"
  env GOOS=android GOARCH="${GOARCH[$abi]}" $extra CGO_ENABLED=1 CC="$TC/${CLANG[$abi]}" \
    go build -trimpath -buildvcs=false -ldflags="-s -w" -o "$DESTINO/$abi/libsnowflake.so" .
  chmod 0755 "$DESTINO/$abi/libsnowflake.so"
  echo "snowflake $TAG ok: $abi — $(file -b "$DESTINO/$abi/libsnowflake.so" | cut -c1-90)"
done
echo "SNOWFLAKE_VERSION=$TAG" >> "${GITHUB_ENV:-/dev/null}"
