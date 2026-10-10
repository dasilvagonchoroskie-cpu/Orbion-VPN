# PROJETO — Orbion VPN

Arquivo mestre do projeto. Colando este arquivo num chat novo, dá para continuar
o trabalho sem reexplicar nada.

## 1. O que é

App Android de privacidade da Fortaleza Digital Security. Todo o tráfego do
celular passa pela rede Tor (anonimato), com Kill Switch, bloqueio de
rastreadores, DNS pelo Tor, escolha de apps fora do túnel (split tunneling),
desvio automático de serviços que recusam o Tor e contorno de bloqueio.

- Pacote: `com.orbion.vpn` (o mesmo do Orbion antigo)
- Repositório: `dasilvagonchoroskie-cpu/orbion-vpn` (público só enquanto compila; depois privado)
- Projetos separados: Orbion = anonimato via Tor. Blindagem de Rede = contagem
  de dados + proteção sem Tor. Não misturar.

## 2. Histórico

- v5.0 (25/07/2026): Kotlin + Jetpack Compose, feito no notebook (perdido).
  Dois APKs recuperados do pendrive:
  - celular (arm64-v8a, assinatura de depuração) — base desta versão;
  - computador/emulador (x86_64, assinado Fortaleza) — tinha a "escada" de
    contorno com Snowflake, trazida para a v6.
  O código-fonte foi recuperado de dentro do APK (descompilação).
- v6.0 (10/10/2026): interface em Flutter/Dart; motor nativo reescrito com as
  correções abaixo. Chave de assinatura NOVA (a antiga ficou no notebook), por
  isso é preciso desinstalar o Orbion antigo antes de instalar a v6.

## 3. Decisões travadas

- Apps só em Flutter/Dart (regra geral desde 24/09/2026). A interface é 100%
  Flutter; o motor que conversa com o Android (VpnService, Tor, túnel) fica
  nativo em Kotlin, chamado pelo canal `orbion/motor`.
- O app NUNCA tenta esconder do Android ou de bancos que é uma VPN. A solução
  para apps que recusam Tor é o desvio por domínio e a escolha manual de apps
  fora do túnel. Assunto encerrado.
- Sem pacotes Flutter de terceiros (menos coisa para quebrar).
- Binários do Tor vêm do pacote oficial do Tor Project (conferidos por SHA-256
  e assinatura GPG) — não compilar Tor por conta própria.

## 4. Arquitetura

Pacote de rede: app → interface da VPN (10.8.0.2, DNS 198.18.0.2) →
hev-socks5-tunnel → FiltroSocks 127.0.0.1:19055 → Tor SOCKS 127.0.0.1:19050 →
internet. Porta de controle do Tor 19051 (cookie). Portas fora do padrão para
não brigar com o Orbot.

Motor (Kotlin, `android/app/src/main/kotlin/com/orbion/vpn/motor/`):
- `OrbionVpnService` — ciclo da VPN, Kill Switch, reconexão, notificação, troca de rede
- `TorManager` — processo do Tor, escada de contorno, porta de controle (nova identidade, aviso de rede nova)
- `Transportes` — direto → Snowflake → obfs4 (bridges públicas embutidas ou as da pessoa); lembra o degrau que funcionou
- `FiltroSocks` — rastreadores (assets/rastreadores.txt), desvio de serviços que barram Tor, contagem e registros
- `TunelHev` + `hev/sockstun/TProxyService.java` — ponte JNI com a libhev-socks5-tunnel.so
- `ResolvedorDoH` — DNS por HTTPS (1.1.1.1, 1.0.0.1, 8.8.8.8) só para o desvio direto
- `Diagnostico` — bateria, marca do celular, VPN sempre ativa, processos filhos, notificações, DNS privado, resolução de nomes, apps incompatíveis
- `AppsProblematicos` / `DetectorDeProblemas`, `Preferencias`, `Painel`, `BootReceiver`

Interface (Flutter, `lib/`): `main.dart` (4 abas), `controlador.dart`,
`motor.dart` (modelos + chamadas), `telas/painel|apps|registros|config.dart`,
`widgets/comuns.dart`, `tema.dart` (neon escuro), `util.dart` (+ testes).

## 5. Por que o Android "matava" o Orbion v5 (corrigido na v6)

1. Entrada em primeiro plano com tipo `systemExempted` sem proteção: quando o
   Android recusava (permissão de VPN revogada, outra VPN ligada, início pelo
   boot), o app fechava. Agora: systemExempted → specialUse → segue sem cair.
2. A interface da VPN só subia depois de até 2 min de conexão do Tor: o serviço
   ficava sem o vínculo de VPN que protege o app, e o tráfego vazava nesse
   intervalo. Agora, com Kill Switch, a interface sobe antes (fail-closed).
3. Tor roda como processo filho: no Android 12+ o sistema encerra processo
   filho "esquecido". Agora o Kill Switch religa só o Tor (túnel e filtro
   continuam), com intervalos 3s, 10s, 20s, 40s, 90s, 3min, 5min.
4. Log do túnel crescia sem limite e a tela lia o arquivo inteiro → memória e
   bateria. Agora corta em 1,5 MB e lê só o fim.
5. Filtro abria 2 threads por conexão sem limite e conexões mortas ficavam
   penduradas. Agora pilha de 128 KB, máximo 900 threads, ociosas fecham em 5 min.
6. Troca de rede (Wi-Fi ↔ 4G) deixava o Tor esperando tempo-limite. Agora o
   Tor é avisado na hora (DisableNetwork 1→0).
7. Faltava suporte a "VPN sempre ativa" e orientação por marca de celular.
8. Snowflake usava linhas antigas (Fastly, desativado). Agora usa as bridges
   atuais do pacote oficial.
9. O APK x86_64 trazia uma libhev compilada para outra classe Java — derrubava
   o app ao carregar. Agora a libhev é compilada na esteira com a classe certa.

## 6. Compilação e entrega

- Esteira: `.github/workflows/compilar-apk.yml` a cada envio para `main`:
  compila a libhev (NDK) → baixa o tor-expert-bundle Android (verificado) →
  flutter analyze → flutter test → APKs assinados por arquitetura → Release
  `apk-mais-recente`.
- Segredos: `ORBION_KEYSTORE_BASE64`, `ORBION_KEYSTORE_SENHA` (alias `orbion`).
- Chave: `orbion-vpn-release.jks` (PKCS12, RSA 4096, válida até 2056), DN
  "CN=Fortaleza Digital Security, OU=Desenvolvimento, O=Fortaleza Digital
  Security, L=Goiatuba, ST=Goias, C=BR", SHA-256
  B6:40:27:BE:5A:75:5E:1A:30:CC:CF:A8:C2:56:B8:EC:D5:3A:AC:4F:BF:8F:4B:06:7F:B2:EC:5F:BD:B5:2C:3B.
  Guardar a chave e a senha: sem elas não dá para atualizar o app por cima.
- APK para celular: `Orbion-VPN-v6.0.0-arm64-v8a.apk`.

## 7. Status

- v6.0 em compilação/entrega (10/10/2026).
- Próximos passos possíveis: testar no aparelho; se o Tor ainda cair sozinho
  em segundo plano, considerar rodar o Tor dentro do processo (tor-android).
