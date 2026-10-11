# PROJETO — Orbion VPN

Arquivo mestre do projeto. Colando este arquivo num chat novo, dá para continuar
o trabalho sem reexplicar nada.

## 1. O que é

App Android de privacidade da Fortaleza Digital Security, para vender na Play
Store (não é só para Xiaomi: precisa funcionar bem em qualquer marca). O
tráfego do celular passa pela rede Tor (anonimato), com **rota inteligente**:
os apps e sites que recusam o Tor ou que a pessoa usa com o próprio nome
(banco, governo, WhatsApp, redes sociais, Google, streaming, compras, IA) vão
por fora do Tor, e todo o resto vai pelo Tor. Tem também Kill Switch,
bloqueio de rastreadores, DNS pelo Tor, contorno de bloqueio (Snowflake/obfs4)
e religamento automático se o sistema derrubar a VPN.

- Pacote: `com.orbion.vpn` (o mesmo do Orbion antigo)
- Repositório: `dasilvagonchoroskie-cpu/Orbion-VPN` (público só enquanto compila; depois privado — a sessão do Claude não consegue mudar a visibilidade, o Evandro muda em Settings → General → Danger Zone → Change visibility)
- Projetos separados: Orbion = anonimato via Tor. Blindagem de Rede = contagem
  de dados + proteção sem Tor. Não misturar.

## 2. Histórico

- v5.0 (25/07/2026): Kotlin + Jetpack Compose, feito no notebook (perdido).
  Dois APKs recuperados do pendrive (celular arm64 = base; computador x86_64 =
  tinha a escada com Snowflake). Código recuperado por descompilação.
- v6.0.0 (10/10/2026): interface em Flutter/Dart; motor nativo reescrito.
  Chave de assinatura NOVA (a antiga ficou no notebook): foi preciso
  desinstalar o Orbion antigo uma vez.
- v6.0.1 (10/10/2026): botão de bateria funcionando na Xiaomi; avisos somem
  quando resolvidos ("Já fiz" para o que o app não consegue conferir).
- v6.1.0 (11/10/2026): pedido do Evandro depois de testar a 6.0.1 — "não pode
  desconectar ao fechar o app ou as abas", "não pode aparecer Fechar app /
  Aguardar", "tem que servir para todas as marcas (Play Store)" e "a VPN
  inteligente tem que funcionar de verdade, mantendo a segurança". Detalhes
  na seção 5.

## 3. Decisões travadas

- Apps só em Flutter/Dart (regra geral desde 24/09/2026). A interface é 100%
  Flutter; o motor que conversa com o Android (VpnService, Tor, túnel) fica
  nativo em Kotlin, chamado pelo canal `orbion/motor`.
- O app NUNCA tenta esconder do Android ou de bancos que é uma VPN. A solução
  para quem recusa o Tor é a rota inteligente, às claras: apps fora do túnel
  pelo próprio Android (addDisallowedApplication) e sites desviados por nome.
  Limite honesto: um banco que recusa QUALQUER VPN ligada no aparelho (mesmo
  com o app dele fora do túnel) não tem solução sem desligar a VPN.
- Rota inteligente nasce LIGADA, com todas as categorias ligadas (bancos,
  governo, mensageiros, redes sociais, Google, streaming, compras, IA). A
  pessoa desliga categorias, muda qualquer app e manda qualquer site sempre
  por fora ou sempre pelo Tor.
- Segurança do que vai por fora: só sai direto conexão cifrada (porta 80
  nunca sai por fora, vai pelo Tor); DNS dos sites desviados por HTTPS
  (1.1.1.1, 1.0.0.1, 8.8.8.8; DNS do aparelho só se a rede bloquear esses);
  rastreadores continuam bloqueados nos sites desviados.
- Motor da VPN em processo próprio (`:vpn`); a tela nunca abre as
  configurações do motor (só o processo `:vpn` mexe em `orbion_settings`).
- Nada que possa demorar roda na linha principal (nem na tela, nem no motor).
- Play Store: sem QUERY_ALL_PACKAGES (VPN não é uso permitido); a lista de
  apps vem do `<queries>` de apps com ícone + pacotes explícitos.
  REQUEST_IGNORE_BATTERY_OPTIMIZATIONS mantida (o NetGuard, VPN da Play Store,
  usa); se a revisão recusar, basta apagar a linha do manifesto — o app
  percebe e passa a abrir as telas de bateria.
- Sem pacotes Flutter de terceiros (menos coisa para quebrar).
- Binários do Tor vêm do pacote oficial do Tor Project (SHA-256 + GPG
  conferidos) — não compilar Tor por conta própria.

## 4. Arquitetura

Processos:
- Principal: a tela Flutter (`MainActivity`). Pode fechar, travar ou ser
  encerrada sem afetar a VPN.
- `:vpn`: o motor — `OrbionVpnService`, `Ponte` (ContentProvider interno),
  `Vigia` (JobService de 15 min) e `BootReceiver`. O Tor roda como processo
  filho dele.
- A tela fala com o motor pela `Ponte`: `call(método, json)` em linha de fundo,
  ligação "instável" (se o motor morrer, a tela recebe erro e tenta de novo, em
  vez de morrer junto). Ligar a VPN (permissão + startForegroundService) é
  feito pela tela; o resto passa pela ponte.

Pacote de rede: app → interface da VPN (10.8.0.2, DNS 198.18.0.2) →
hev-socks5-tunnel (o DNS 198.18.0.2 responde na hora com um endereço interno
e manda o NOME do site adiante, como SOCKS4a) → FiltroSocks 127.0.0.1:19055 →
Tor SOCKS 127.0.0.1:19050 → internet. Porta de controle do Tor 19051 (cookie).
Portas fora do padrão para não brigar com o Orbot. Apps fora do túnel nem
passam pelo Orbion.

Decisão do FiltroSocks, nesta ordem: rastreador → recusado; site da lista da
pessoa / de categoria ligada / desviado sozinho nesta sessão → direto (só
cifrado); endereço em pausa pelo disjuntor → desvia (site, com desvio
automático ligado) ou recusa na hora; rede local/endereços internos/reservados
→ recusa na hora; resto → Tor. Disjuntor: 3 falhas seguidas pelo Tor (Tor
responde erro, não responde em 25 s, ou o servidor fecha sem mandar nada) →
pausa de 30 s; depois disso 1 falha já pausa de novo e a pausa dobra (1, 2, 4,
8 min, máx. 10 min); um sucesso zera.

Motor (Kotlin, `android/app/src/main/kotlin/com/orbion/vpn/motor/`):
- `OrbionVpnService` — ciclo da VPN, fila de controle, Kill Switch, religamento, rotas de apps ao vivo (recria a interface + hev sem mexer no Tor), app novo instalado, notificações
- `Filas` — fila de controle (uma tarefa por vez), agenda e avulsos; nenhuma tarefa derruba o processo
- `Ponte` (+ `Atendente`) / `PonteCliente` / `Json` — ponte tela ↔ motor
- `RotaInteligente` — categorias, catálogo de apps (pacote exato → prefixo → palavras no pacote/nome), domínios por categoria, regras do filtro, lista da aba Apps
- `FiltroSocks` (+ `Disjuntor`) — decisões por conexão, desvio automático, registros
- `TorManager` — processo do Tor (parada sem bloquear), escada de contorno, porta de controle (nova identidade, rotação ao vivo, aviso de rede nova)
- `Transportes` — direto → Snowflake → obfs4; lembra o degrau que funcionou
- `TunelHev` + `hev/sockstun/TProxyService.java` — ponte JNI com a libhev-socks5-tunnel.so
- `ResolvedorDoH` — DNS por HTTPS para os desvios
- `Fabricantes` — marca do celular (Xiaomi, Samsung, Motorola, Huawei, Honor, OnePlus, realme, OPPO, vivo, ASUS, Nokia, Sony, Pixel, Tecno/Infinix/itel, LG, outras), passo a passo e telas de bateria/início automático de cada uma
- `Diagnostico` — bateria, ajustes da marca, quedas da VPN em 7 dias, VPN sempre ativa, processos filhos, notificações, DNS privado, resolução de nomes, resumo da rota inteligente; "Já fiz" com prefixo `diag:`
- `Eventos` (+ `Inicio`) — diário da VPN (`eventos.log`) e motivo de cada fechamento (ApplicationExitInfo, com rastro da linha principal em travamentos)
- `Vigia`, `BootReceiver`, `Preferencias` (versão 2 da configuração), `Painel`

Interface (Flutter, `lib/`): `main.dart` (4 abas: Painel, Apps, Registros,
Config), `controlador.dart` (religa sozinho ao abrir se o sistema derrubou),
`motor.dart` (modelos + chamadas), `telas/painel|apps|registros|config.dart`,
`widgets/comuns.dart`, `tema.dart` (neon escuro), `util.dart` (+ testes).
- Apps: cada app com a rota (pela rede Tor / por fora e por quê); a chave muda.
- Registros: Decisões (tocar num site → sempre por fora / sempre pelo Tor /
  automático), Eventos (quedas + diário, botão copiar relatório) e Técnico.
- Config: diagnóstico, Rota inteligente (categorias, desvio automático,
  desviados na sessão, em pausa, sites por sua conta), criptografia,
  segurança, automático, rede Tor, DNS, sobre.

## 5. O que foi corrigido

### 5.1 Por que o Android "matava" o Orbion v5 (corrigido na 6.0)

1. Primeiro plano `systemExempted` sem proteção → agora systemExempted → specialUse → segue.
2. Interface da VPN subia só depois do Tor (vazamento e sem vínculo de VPN) → com Kill Switch sobe antes.
3. Tor (processo filho) encerrado no Android 12+ → Kill Switch religa só o Tor (3 s, 10 s, 20 s, 40 s, 90 s, 3 min, 5 min).
4. Log do túnel sem limite → corta em 1,5 MB e lê só o fim.
5. Filtro sem limite de threads → pilha de 128 KB, máx. 900, ociosas fecham em 5 min.
6. Troca de rede deixava o Tor esperando → aviso na hora (DisableNetwork 1→0).
7. Sem "VPN sempre ativa" nem orientação por marca → agora tem.
8. Snowflake com linhas antigas → bridges atuais do pacote oficial.
9. libhev do APK x86_64 com classe errada → compilada na esteira.

### 5.2 Por que a 6.0 desconectava e travava (corrigido na 6.1)

1. Tela e VPN no mesmo processo: limpar os recentes (MIUI e outras marcas
   encerram o processo) ou tocar em "Fechar app" no aviso de travamento
   derrubava a VPN junto. Agora a VPN fica no processo `:vpn`.
2. "Fechar app / Aguardar" (ANR): a linha principal fazia chamadas ao sistema
   que podem esperar travas internas do Android (permissão de VPN, VPN sempre
   ativa, rede) a cada 5 s, desligava o Tor/túnel com esperas e gravava
   configurações em disco toda hora. Agora tudo isso roda em segundo plano,
   na fila de controle, e as configurações só são gravadas quando mudam.
3. Ao reabrir aparecia "Conectando" porque o processo tinha morrido. Agora:
   o Android religa o serviço (START_STICKY / VPN sempre ativa), o vigia de
   15 min religa, a tela religa ao abrir, e se o Android não deixar religar
   em segundo plano aparece a notificação "toque para religar". O motivo de
   cada queda fica em Registros → Eventos.
4. Orientação só da Xiaomi → tabela de todas as marcas.
5. "VPN inteligente" incompleta: bancos e governo não saíam do Tor sozinhos
   (só por domínio e sem domínios de banco). Agora: categorias de apps e
   sites, desvio automático de quem recusa o Tor, disjuntor por destino e
   listas da pessoa.

## 6. Compilação e entrega

- Esteira: `.github/workflows/compilar-apk.yml` a cada envio para `main`:
  flutter analyze → flutter test (primeiro, para falhar rápido) → libhev (NDK,
  16 KB) → tor-expert-bundle Android (SHA-256 + GPG) → Snowflake (Go 1.26 +
  NDK, CGO, -checklinkname=0, 16 KB) → APKs por arquitetura → conferência de
  conteúdo e assinatura → conferência do manifesto (motor no `:vpn`, sem
  QUERY_ALL_PACKAGES) → conferência de páginas de 16 KB (64 bits).
- Assinatura: a sessão do Claude não consegue gravar segredos do GitHub, então
  a esteira publica com chave de depuração na release `compilacao-interna`
  (NÃO instalar). A assinatura oficial é feita fora da esteira: zipalign -p 4
  e apksigner (v2+v3) com a chave abaixo; o APK assinado é entregue no chat.
- Opcional: segredos `ORBION_KEYSTORE_BASE64` e `ORBION_KEYSTORE_SENHA`
  (alias `orbion`) fazem a esteira assinar e publicar em `apk-mais-recente`.
- Chave: `orbion-vpn-release.jks` (PKCS12, RSA 4096, válida até 2056), DN
  "CN=Fortaleza Digital Security, OU=Desenvolvimento, O=Fortaleza Digital
  Security, L=Goiatuba, ST=Goias, C=BR", SHA-256
  B6:40:27:BE:5A:75:5E:1A:30:CC:CF:A8:C2:56:B8:EC:D5:3A:AC:4F:BF:8F:4B:06:7F:B2:EC:5F:BD:B5:2C:3B.
  Guardar a chave e a senha: sem elas não dá para atualizar o app por cima.
- versionCode = 1000 × arquitetura + (60 + número da execução da esteira)
  (arm64-v8a = 2xxx). A 6.1.0 entregue é 2068 (execução 8).
- APK para celular: `Orbion-VPN-v6.1.0-arm64-v8a.apk`, SHA-256
  af7252312294d82b5390e207ab15055e5977f6c1a00b6e519b3853b5c8df55b2.
  Instala por cima da 6.0.1 sem desinstalar.

## 7. Status

- v6.1.0 compilada (esteira execução 8: análise, testes, manifesto e 16 KB ok)
  e assinada em 11/10/2026. Tor Browser 15.0.24, hev-socks5-tunnel 2.18.0,
  7 bridges obfs4 + 2 snowflake embutidas. Conferido localmente: o motor
  Kotlin compila e 56 testes da rota inteligente e do disjuntor passam.
  Ainda não testada no aparelho.
- O que testar no celular: (1) ligar, ir para a tela inicial, limpar os
  recentes e voltar — tem que continuar conectado; (2) usar o app por um tempo
  sem aparecer "não está respondendo"; (3) app de banco e gov.br abrindo com a
  VPN ligada; (4) WhatsApp com chamada de voz; (5) Registros → Eventos para
  ver se o Android derrubou a VPN e por quê (botão copiar relatório).
- Pendente: versão para notebook/PC (o Orbion de computador) — fica para depois
  do celular, decisão do Evandro em 10/10/2026.
- Ideias futuras: mostrar qual app abriu cada conexão (exige modificar o hev
  para passar a porta de origem); tradução para outros idiomas antes de
  publicar fora do Brasil.

## 8. Play Store (antes de publicar)

- Formulário de declaração de VpnService (VPN é a função principal; dados
  cifrados até a saída do túnel; sem coleta de dados; sem anúncios).
- Declaração do tipo de serviço em primeiro plano `specialUse` (subtipo
  "vpn": é o reserva quando o Android recusa `systemExempted`).
- Página da loja dizendo que o app usa VpnService e o que a rota inteligente
  manda por fora do Tor.
- Segurança dos dados (Data safety): não coleta nem compartilha dados; o
  diário e os registros ficam só no aparelho.
- Política de privacidade publicada (pode ficar no site da Fortaleza).
- Conta de desenvolvedor, ícones e capturas de tela, Android App Bundle
  (.aab) assinado pela Play App Signing (a chave do Orbion vira a chave de
  envio).
- Se a revisão recusar REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: apagar a linha do
  manifesto (o app já se adapta).
