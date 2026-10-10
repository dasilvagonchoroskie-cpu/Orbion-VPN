#!/usr/bin/env bash
# Baixa o pacote oficial do Tor para Android (tor-expert-bundle), confere a
# integridade (SHA-256 e, quando possível, assinatura GPG do Tor Project) e
# coloca os programas na pasta jniLibs com os nomes que o Orbion usa:
#
#   tor        -> libtor.so
#   lyrebird   -> libobfs4proxy.so   (obfs4, meek, webtunnel)
#   snowflake  -> libsnowflake.so
#
# Também gera assets/bridges_embutidas.json com as bridges públicas atuais
# (tiradas do pt_config.json do próprio pacote).
#
# Uso: scripts/baixar-motor-tor.sh <pasta-jniLibs> <pasta-assets>
set -euo pipefail

DESTINO="$(realpath -m "${1:?informe a pasta jniLibs}")"
ASSETS="$(realpath -m "${2:?informe a pasta assets}")"
BASE="https://dist.torproject.org/torbrowser"
CHAVE_TOR_BROWSER="EF6E286DDA85EA2A4BA7DE684E2C6E8793298290"

TRAB="$(mktemp -d)"
cd "$TRAB"

# Versão estável mais recente (ignora alfas como 16.0a13).
VERSAO="${TOR_BROWSER_VERSION:-}"
if [ -z "$VERSAO" ]; then
  VERSAO="$(curl -fsSL "$BASE/" | grep -oE 'href="[0-9]+(\.[0-9]+)+/"' | grep -oE '[0-9]+(\.[0-9]+)+' | sort -V | tail -1)"
fi
[ -n "$VERSAO" ] || { echo "::error::Não consegui descobrir a versão do Tor Browser."; exit 1; }
echo "Pacote do Tor: Tor Browser $VERSAO"

curl -fsSLO "$BASE/$VERSAO/sha256sums-signed-build.txt"
curl -fsSLO "$BASE/$VERSAO/sha256sums-signed-build.txt.asc" || true

# Assinatura GPG: se a chave puder ser obtida, a verificação é OBRIGATÓRIA.
if gpg --batch --auto-key-locate nodefault,wkd --locate-keys torbrowser@torproject.org >/dev/null 2>&1 \
   || gpg --batch --keyserver hkps://keys.openpgp.org --recv-keys "$CHAVE_TOR_BROWSER" >/dev/null 2>&1; then
  if [ -f sha256sums-signed-build.txt.asc ]; then
    gpg --batch --status-fd 1 --verify sha256sums-signed-build.txt.asc sha256sums-signed-build.txt > gpg.txt 2>&1 || {
      cat gpg.txt; echo "::error::Assinatura do Tor Project NÃO confere. Abortando."; exit 1; }
    grep -q "VALIDSIG $CHAVE_TOR_BROWSER" gpg.txt || grep -q "VALIDSIG.*$CHAVE_TOR_BROWSER" gpg.txt || {
      cat gpg.txt; echo "::error::Assinatura válida, mas não é da chave do Tor Browser."; exit 1; }
    echo "Assinatura do Tor Project conferida (chave $CHAVE_TOR_BROWSER)."
  fi
else
  echo "::warning::Não foi possível obter a chave GPG do Tor Project; conferindo só o SHA-256."
fi

declare -A ABI=( [aarch64]=arm64-v8a [armv7]=armeabi-v7a [x86_64]=x86_64 )
declare -A ELF=( [aarch64]="ARM aarch64" [armv7]="ARM," [x86_64]="x86-64" )
PT_CONFIG=""

for arq in aarch64 armv7 x86_64; do
  nome="tor-expert-bundle-android-$arq-$VERSAO.tar.gz"
  curl -fsSLO "$BASE/$VERSAO/$nome"
  esperado="$(grep " $nome\$" sha256sums-signed-build.txt | awk '{print $1}' || true)"
  if [ -z "$esperado" ]; then
    curl -fsSLO "$BASE/$VERSAO/sha256sums-unsigned-build.txt"
    esperado="$(grep " $nome\$" sha256sums-unsigned-build.txt | awk '{print $1}' || true)"
  fi
  [ -n "$esperado" ] || { echo "::error::$nome não está na lista de SHA-256."; exit 1; }
  echo "$esperado  $nome" | sha256sum -c -

  mkdir -p "x-$arq" && tar -xzf "$nome" -C "x-$arq"
  echo "::notice title=pacote $arq::$(cd "x-$arq" && find . -type f | sed 's#^\./##' | sort | tr '\n' ' ' | cut -c1-900)"

  tor="$(find "x-$arq" -type f \( -iname 'libtor.so' -o -name 'tor' \) | head -1)"
  lyre="$(find "x-$arq" -type f \( -iname '*lyrebird*' -o -iname '*obfs4proxy*' \) ! -name '*.json' | head -1)"
  snow="$(find "x-$arq" -type f \( -iname 'snowflake-client*' -o -iname '*snowflake*.so' \) ! -name '*.json' | head -1)"
  [ -z "$PT_CONFIG" ] && PT_CONFIG="$(find "$TRAB/x-$arq" -name 'pt_config.json' | head -1)"

  [ -n "$tor" ] || { echo "::error::Programa do Tor não encontrado no pacote $arq."; exit 1; }
  d="$DESTINO/${ABI[$arq]}"; mkdir -p "$d"
  install -m 0755 "$tor" "$d/libtor.so"
  [ -n "$lyre" ] && install -m 0755 "$lyre" "$d/libobfs4proxy.so" || echo "::warning::lyrebird ausente ($arq)"
  [ -n "$snow" ] && install -m 0755 "$snow" "$d/libsnowflake.so" || echo "::warning::snowflake ausente ($arq)"

  for f in "$d"/libtor.so "$d"/libobfs4proxy.so "$d"/libsnowflake.so; do
    [ -f "$f" ] || continue
    info="$(file -b "$f")"
    echo "  $(basename "$f"): $info" | cut -c1-160
    echo "$info" | grep -q "${ELF[$arq]}" || { echo "::error::$f não é binário ${ELF[$arq]}"; exit 1; }
  done
done

mkdir -p "$ASSETS"
python3 - "$PT_CONFIG" "$ASSETS/bridges_embutidas.json" "$VERSAO" <<'PY'
import json, sys
origem, destino, versao = sys.argv[1], sys.argv[2], sys.argv[3]
saida = {"origem": f"Tor Browser {versao}", "snowflake": [], "obfs4": []}
try:
    cfg = json.load(open(origem)) if origem else {}
    pontes = cfg.get("bridges", {})
    saida["snowflake"] = [l for l in pontes.get("snowflake", []) if l.startswith("snowflake ")]
    saida["obfs4"] = [l for l in pontes.get("obfs4", []) if l.startswith("obfs4 ")]
except Exception as e:
    print(f"::warning::pt_config.json ilegível: {e}")
json.dump(saida, open(destino, "w"), indent=1)
print(f"Bridges embutidas: {len(saida['snowflake'])} snowflake, {len(saida['obfs4'])} obfs4 ({saida['origem']})")
PY
echo "TOR_BROWSER_VERSION=$VERSAO" >> "${GITHUB_ENV:-/dev/null}"
