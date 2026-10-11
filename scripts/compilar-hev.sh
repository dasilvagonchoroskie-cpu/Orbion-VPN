#!/usr/bin/env bash
# Compila a biblioteca do túnel (hev-socks5-tunnel) para cada arquitetura,
# já com o nome de classe que o app espera (hev/sockstun/TProxyService).
#
# Uso: scripts/compilar-hev.sh <pasta-jniLibs>
set -euo pipefail

DESTINO="$(realpath -m "${1:?informe a pasta jniLibs}")"
LICENCAS_DIR="$(realpath -m "${LICENCAS:-assets/licencas}")"
VERSAO_HEV="${HEV_VERSION:-2.18.0}"
NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}}"
ABIS="arm64-v8a armeabi-v7a x86_64"

if [ -z "$NDK" ] || [ ! -x "$NDK/ndk-build" ]; then
  echo "::error::NDK não encontrado (ANDROID_NDK_LATEST_HOME / ANDROID_NDK_HOME)."
  exit 1
fi
echo "NDK: $NDK"

TRAB="$(mktemp -d)"
git clone --quiet --depth 1 --branch "$VERSAO_HEV" --recursive \
  https://github.com/heiher/hev-socks5-tunnel "$TRAB/jni"

# Licenças do túnel e das peças que ele embute (lwIP, libyaml, hev-task-system).
mkdir -p "$LICENCAS_DIR"
cp -f "$TRAB/jni/LICENSE" "$LICENCAS_DIR/hev-socks5-tunnel.txt"
for parte in "$TRAB"/jni/third-part/*/; do
  nome="$(basename "$parte")"
  [ "$nome" = "wintun" ] && continue   # só do Windows, não entra no app
  arq="$(find "$parte" -maxdepth 1 -type f \( -iname 'licen*' -o -iname 'copying*' \) | sort | head -1 || true)"
  if [ -n "$arq" ]; then cp -f "$arq" "$LICENCAS_DIR/$nome.txt"; fi
done

compilar() {
  local plataforma="$1"
  "$NDK/ndk-build" -C "$TRAB" \
    NDK_PROJECT_PATH="$TRAB" \
    APP_BUILD_SCRIPT="$TRAB/jni/Android.mk" \
    NDK_APPLICATION_MK="$TRAB/jni/Application.mk" \
    APP_ABI="$ABIS" \
    APP_PLATFORM="$plataforma" \
    APP_CFLAGS="-O3 -DPKGNAME=hev/sockstun -DCLSNAME=TProxyService" \
    -j"$(nproc)"
}

# android-26 = o mínimo do Orbion. Se a versão do hev exigir mais, cai para o
# padrão do próprio projeto.
if ! compilar android-26; then
  echo "::warning::hev não compilou para android-26; tentando android-29."
  rm -rf "$TRAB/obj" "$TRAB/libs"
  compilar android-29
fi

for abi in $ABIS; do
  mkdir -p "$DESTINO/$abi"
  cp "$TRAB/libs/$abi/libhev-socks5-tunnel.so" "$DESTINO/$abi/"
  # Confere que a biblioteca registra a classe certa.
  if ! strings "$DESTINO/$abi/libhev-socks5-tunnel.so" | grep -q "hev/sockstun/TProxyService"; then
    echo "::error::libhev-socks5-tunnel.so ($abi) não registra hev/sockstun/TProxyService"
    exit 1
  fi
  echo "hev $VERSAO_HEV ok: $abi ($(stat -c %s "$DESTINO/$abi/libhev-socks5-tunnel.so") bytes)"
done
