# Orbion VPN — v6.2.0 (Flutter)

App Android de privacidade da **Fortaleza Digital Security**: o tráfego do
celular passa pela rede Tor, com **rota inteligente** (bancos, governo,
mensageiros, redes sociais, Google, streaming, compras e IA vão por fora do
Tor, às claras e com a criptografia deles), Kill Switch, bloqueio de
rastreadores, DNS pelo Tor, contorno automático de bloqueio e religamento
automático se o sistema derrubar a VPN.

## Como está montado

Dois processos Android:

```
Processo principal (tela)                    Processo ":vpn" (motor)
lib/  (Flutter/Dart)                         android/app/src/main/kotlin/com/orbion/vpn/motor/
├─ main.dart        (4 abas)                 ├─ OrbionVpnService.kt (VPN, fila de controle, Kill Switch, religamento)
├─ controlador.dart (estado; religa ao abrir)├─ Ponte.kt            (ContentProvider: tela ↔ motor, em JSON)
├─ motor.dart       (chamadas ao nativo)     ├─ Filas.kt            (nada demora na linha principal)
├─ legal.dart       (aceite, termos, licenças)├─ Falhas.kt           (erro não derruba a VPN; rastro no relatório)
└─ telas/                                    ├─ RotaInteligente.kt  (categorias de apps e sites)
   ├─ painel.dart                            ├─ FiltroSocks.kt      (rastreadores / por fora / disjuntor / Tor)
   ├─ apps.dart     (rota de cada app)       ├─ TorManager.kt       (Tor + escada de contorno)
   ├─ registros.dart (decisões, eventos,     ├─ Transportes.kt      (direto → Snowflake → obfs4)
   │                  enviar relatório)       ├─ TunelHev.kt         (pacotes → SOCKS)
   └─ config.dart                            ├─ ResolvedorDoH.kt    (DNS criptografado do que vai por fora)
android/.../MainActivity.kt                  ├─ Fabricantes.kt      (ajustes de bateria de cada marca)
  (canal "orbion/motor" → PonteCliente.kt)   ├─ Diagnostico.kt      (o que pode derrubar o app)
android/.../Arquivos.kt                      ├─ Eventos.kt          (diário + por que o Android fechou o app)
  (entrega o relatório ao app escolhido)     ├─ Relatorio.kt        (relatório para o suporte, sem sites)
                                             ├─ Vigia.kt            (religa a cada 15 min se precisar)
                                             └─ Preferencias.kt / Painel.kt / BootReceiver.kt / Json.kt
```

Caminho de um pacote: app → interface da VPN → **hev-socks5-tunnel** →
**FiltroSocks** (127.0.0.1:19055) → **Tor** (127.0.0.1:19050) → internet.
Apps que vão por fora do Tor (rota inteligente) nem entram no túnel.

## Binários do motor (montados pela esteira, não ficam no repositório)

| Arquivo no APK | O que é | De onde vem |
|---|---|---|
| `libtor.so` | Tor | pacote oficial `tor-expert-bundle-android` do Tor Project (SHA-256 + assinatura GPG conferidos) |
| `libobfs4proxy.so` | lyrebird (obfs4/meek/webtunnel) | mesmo pacote |
| `libsnowflake.so` | Snowflake | compilado do código-fonte (Go + NDK), o pacote Android não traz mais |
| `libhev-socks5-tunnel.so` | túnel | compilado do código-fonte (heiher/hev-socks5-tunnel) com NDK |
| `assets/bridges_embutidas.json` | bridges públicas atuais | `pt_config.json` do pacote oficial |

Se o download do Tor Project falhar, entram os binários de reserva de
`nativo/reserva/` (só arm64).

## Compilação

Tudo pela esteira do GitHub (`.github/workflows/compilar-apk.yml`), a cada envio
para `main`: análise e testes do Flutter primeiro, depois o motor, os APKs
(arm64-v8a, armeabi-v7a, x86_64) e as conferências (conteúdo, assinatura,
manifesto e páginas de 16 KB). Com os segredos `ORBION_KEYSTORE_BASE64` e
`ORBION_KEYSTORE_SENHA` (alias `orbion`), os APKs saem assinados na Release
`apk-mais-recente`; sem eles, vão para `compilacao-interna` com chave de
depuração e recebem a assinatura oficial fora da esteira.

## Privacidade, termos e licenças

- `assets/legal/privacidade.md` e `assets/legal/termos.md`: o app mostra estes
  textos e o site é gerado deles (`python3 scripts/gerar-paginas-legais.py
  <pasta>` → https://fortalezadigitalsecurity.com.br/orbion/).
- `assets/licencas/`: preenchida pela esteira com as licenças do motor.

Detalhes, decisões e histórico: `PROJETO-orbion-vpn.md`.
