# Orbion VPN — v6 (Flutter)

App Android de privacidade da **Fortaleza Digital Security**: todo o tráfego do
celular passa pela rede Tor, com Kill Switch, bloqueio de rastreadores, DNS
pelo Tor, escolha de apps fora do túnel e contorno automático de bloqueio.

## Como está montado

```
Interface (Flutter/Dart)  ──canal "orbion/motor"──▶  Motor nativo (Kotlin)
   lib/                                               android/app/src/main/kotlin/com/orbion/vpn/
   ├─ main.dart        (4 abas)                       ├─ MainActivity.kt   (ponte com o Flutter)
   ├─ controlador.dart (estado da tela)               └─ motor/
   ├─ motor.dart       (chamadas ao nativo)              ├─ OrbionVpnService.kt  (VPN, Kill Switch, vigilância)
   └─ telas/                                             ├─ TorManager.kt        (Tor + escada de contorno)
      ├─ painel.dart                                     ├─ Transportes.kt       (direto → Snowflake → obfs4)
      ├─ apps.dart                                       ├─ FiltroSocks.kt       (rastreadores / desvio / Tor)
      ├─ registros.dart                                  ├─ TunelHev.kt          (pacotes → SOCKS)
      └─ config.dart                                     ├─ ResolvedorDoH.kt     (DNS criptografado do desvio)
                                                         ├─ Diagnostico.kt       (o que pode derrubar o app)
                                                         ├─ AppsProblematicos.kt (catálogo + detector)
                                                         ├─ Preferencias.kt / Painel.kt / BootReceiver.kt
```

Caminho de um pacote: app → interface da VPN → **hev-socks5-tunnel** →
**FiltroSocks** (127.0.0.1:19055) → **Tor** (127.0.0.1:19050) → internet.

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
para `main`. Saem três APKs (arm64-v8a, armeabi-v7a, x86_64) na Release
`apk-mais-recente` quando os segredos `ORBION_KEYSTORE_BASE64` e
`ORBION_KEYSTORE_SENHA` (alias `orbion`) existem. Sem eles, os APKs vão para
`compilacao-interna` com chave de depuração e recebem a assinatura oficial
fora da esteira.
