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
- v6.1.1 (11/10/2026): o Evandro testou a 6.1.0 ("não crashou mais e não
  fechou") e mandou os registros técnicos pedindo o polimento final para
  comercializar. Os registros mostraram o Tor sendo reiniciado a cada 2–8 s e
  um reinício falso do Tor; corrigido e polido (seção 5.3).
- v6.1.2 (11/10/2026): primeiro relatório enviado pelo botão "Enviar
  relatório" da 6.1.1 (5 min depois de instalar). Tudo saudável; ajustes finos
  na seção 5.4.

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
- Aba Apps: a chave muda na hora, com "Desfazer" no aviso. Só pergunta antes
  quando quebra algo sem a pessoa perceber: serviços do Google / Play Store
  (notificações de todos os apps) e mensageiros (chamadas).
- Relatório para o suporte nunca leva sites visitados, listas de sites da
  pessoa nem bridges — só contagens, estado, eventos e registros técnicos.
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
→ recusa na hora; sem internet no aparelho → "rede inalcançável" na hora;
resto → Tor. Disjuntor: 3 falhas seguidas pelo Tor (Tor responde erro, não
responde em 25 s, ou o servidor fecha sem mandar nada) → pausa de 30 s; depois
disso 1 falha já pausa de novo e a pausa dobra (1, 2, 4, 8 min, máx. 10 min);
um sucesso zera. Falhas durante queda ou troca de rede (45 s depois de uma
rede cair/perder internet, 30 s depois de voltar/trocar) NÃO contam.

Rede do aparelho (desde a 6.1.1): só redes físicas contam (NOT_VPN). O Tor
refaz as conexões só se a rede que ELE usava sumiu ou perdeu internet e há
outra boa (espera 3 s, no máximo 1 vez a cada 30 s). Sem rede nenhuma, o Tor
fica em espera (DisableNetwork=1, poupa bateria) e volta sozinho.

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
- `Relatorio` — relatório para o suporte (estado, configuração em contagens, diagnóstico, fechamentos, diário, filtro, 300 linhas do Tor, avisos do túnel); `Arquivos` (processo principal) entrega o .txt ao app escolhido no menu Compartilhar, só leitura
- `Vigia`, `BootReceiver`, `Preferencias` (versão 2 da configuração), `Painel`

Interface (Flutter, `lib/`): `main.dart` (4 abas: Painel, Apps, Registros,
Config), `controlador.dart` (religa sozinho ao abrir se o sistema derrubou),
`motor.dart` (modelos + chamadas), `telas/painel|apps|registros|config.dart`,
`widgets/comuns.dart`, `tema.dart` (neon escuro), `util.dart` (+ testes).
- Apps: cada app com a rota (pela rede Tor / por fora e por quê); a chave muda
  na hora com "Desfazer"; "Voltar ao automático" apaga as escolhas manuais.
- Registros: "Enviar relatório" no topo; Decisões (tocar num site → sempre por
  fora / sempre pelo Tor / automático), Eventos (quedas + diário) e Técnico
  (copiar = últimas 150 linhas). Só consulta o motor com a aba à vista.
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

### 5.3 O que os registros técnicos da 6.1.0 mostraram (corrigido na 6.1.1)

1. O Tor fechava e reabria tudo a cada 2–8 s ("DisableNetwork is set...
   Opening Socks listener" em sequência): qualquer troca da rede padrão —
   inclusive a própria VPN sendo refeita ao mexer nos apps — refazia a rede do
   Tor e derrubava todas as conexões. Agora só redes físicas contam, com espera
   e limite (seção 4). O aviso interno do Tor "Bug: Tried to open a socket
   with DisableNetwork set" vinha dessa enxurrada.
2. Reinício do Tor às 00:27:19: na 6.1.1 foi atribuído a uma corrida da
   conferência de saúde, mas o diário do relatório da 6.1.1 mostrou que foi o
   PRÓPRIO Android (onRevoke: "Desconectar" na janela de VPN, ajuste nas
   configurações de VPN, outra VPN ou permissão retirada) e a "VPN sempre
   ativa" religando 1 s depois — não é defeito. A conferência de saúde foi
   mesmo assim movida para a fila de controle (prevenção da corrida).
3. Quedas de rede contavam como "o site recusou o Tor": sites normais eram
   pausados e desviados para fora do Tor. Agora não contam (seção 4).
4. "Your application is giving Tor only an IP address": apps que conectam
   direto num número IP (WhatsApp na porta 5222, apps com DNS próprio). Não é
   vazamento de DNS. Aviso desligado (WarnUnsafeSocks 0). A porta de DNS do
   Tor (não usada) foi removida.
5. Copiar e colar os registros no chat cortava em 20 mil caracteres → botão
   "Enviar relatório" (arquivo completo pelo menu Compartilhar).
6. Bateria: a tela não consulta o motor com o app em segundo plano; Registros
   só com a aba à vista; o anel do Painel animava sem parar (desfoque 60×/s),
   agora só conectando/bloqueado e com a aba à vista; a notificação só se
   atualiza com a tela ligada; log do túnel só com avisos.
7. Avisos com botão ("Reconectar agora", "Desfazer") ficavam presos na tela
   (Flutter 3.47 deixa fixo quando há botão) → somem sozinhos.
8. No teste, o Evandro mandou serviços do Google e WhatsApp pelo Tor: as
   notificações podem atrasar e chamadas não completam pelo Tor — agora o app
   avisa antes de mudar esses dois casos.

### 5.4 O que o primeiro relatório da 6.1.1 mostrou (corrigido na 6.1.2)

Relatório de 11/10/2026 05:27 (Xiaomi 25028PC03G, Android 15): conectada,
0 quedas do Tor, 0 destinos em pausa, 0 sites desviados à toa; o Android não
derrubou a VPN; desde a 6.1.0 nenhum travamento (os "erro interno" listados
eram da 6.0.x, de 10/10). A tela foi encerrada 4× em segundo plano por
memória ("Watermark Pressure", limpeza normal da Xiaomi) e a VPN seguiu.
1. `WarnUnsafeSocks` não existe mais no Tor 0.4.9 ("Skipping obsolete
   configuration option" a cada início) e o aviso "only an IP address"
   continuava. Opção removida; o aviso agora é filtrado na leitura do
   registro e só contado (aparece no relatório).
2. Endereços internos do túnel: o hev numera os nomes do zero a cada início
   (troca de rotas, religamento). Um app com o endereço antigo guardado podia
   cair no mesmo número, agora de OUTRO site (erro de certificado no
   navegador). Agora cada início usa a faixa seguinte (100.64/12 → 100.80/12
   → 100.96/12 → 100.112/12): o endereço velho é recusado na hora e o app
   pergunta o nome de novo. O relatório separa "endereço antigo do túnel" e
   "rede local" dentro dos reservados recusados (eram 5 na primeira sessão).
3. Conexão em 15 s depois da atualização, ~9 s antes de o Tor começar: a
   classificação dos apps (ler o nome de cada um) era refeita a cada
   nascimento do processo, antes de a interface da VPN subir. Agora fica
   guardada em `classificacao.cache` (refeita só para app novo/atualizado e
   a cada versão do Orbion). O diário passou a mostrar os tempos de cada
   conexão (apps • Tor • resto) para o próximo relatório.
4. Registros → Eventos mostrava os travamentos da versão antiga como se
   fossem da atual. Agora só mostra os desta versão (os antigos ficam no
   relatório, marcados "antes desta versão"; a tela em segundo plano aparece
   como "normal").
5. Diagnóstico: dica "Notificações podem atrasar" quando a pessoa manda os
   serviços do Google pelo Tor (botão para a aba Apps, "Quero assim" para
   manter); o texto da rota inteligente não chama mais de "automático" o que
   a pessoa escolheu.
6. Texto do evento de VPN desligada pelo Android explica as causas possíveis.

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
  (arm64-v8a = 2xxx). A 6.1.0 entregue foi 2068 (execução 8); a 6.1.1, 2072
  (execução 12); a 6.1.2 entregue é 2073 (execução 13).
- APK para celular: `Orbion-VPN-v6.1.2-arm64-v8a.apk`, SHA-256
  ad471c647fea86b0c9706baacabe8f4fc1667d4e806999f59f26941565eadb76.
  Instala por cima da 6.1.x sem desinstalar (mesma chave).
- A esteira mostra no resumo os avisos do `flutter analyze` e o total de
  testes (a sessão do Claude não consegue baixar os registros completos).

## 7. Status

- v6.1.0 testada pelo Evandro no celular (11/10/2026): não travou nem fechou.
- v6.1.1 instalada pelo Evandro em 11/10/2026 05:26; relatório 1 min depois
  (seção 5.4).
- v6.1.2 compilada (esteira execução 13: análise sem avisos, 14
  testes do Flutter, manifesto e 16 KB ok) e assinada em 11/10/2026. Tor
  Browser 15.0.24, hev-socks5-tunnel 2.18.0, 7 bridges obfs4 + 2 snowflake
  embutidas. Conferido localmente: o motor Kotlin compila e 61 testes (rota
  inteligente, disjuntor e faixas do túnel) passam.
- O que testar no celular: (1) usar normal por algumas horas e mandar o
  "Enviar relatório" — o registro do Tor não pode ter a sequência
  "DisableNetwork is set / Opening Socks listener" repetindo, nem "Skipping
  obsolete" ou "only an IP address"; no diário, a linha "Tempos: apps ... •
  Tor ... • resto ..." de cada conexão; (2) trocar Wi-Fi ↔ dados móveis e
  ficar sem sinal: o Painel mostra "Sem internet no aparelho" e volta
  sozinho; (3) mexer em vários apps na aba Apps (chave + Desfazer) e abrir
  sites no navegador logo depois (não pode dar erro de certificado);
  (4) bancos e gov.br abrindo com a VPN ligada.
- Recomendado ao Evandro: deixar serviços do Google / Play Store e WhatsApp
  na rota automática (por fora): pelo Tor, notificações atrasam e chamadas
  não completam.
- Pendente: versão para notebook/PC (o Orbion de computador) — fica para depois
  do celular, decisão do Evandro em 10/10/2026.
- Ideias futuras: mostrar qual app abriu cada conexão (exige modificar o hev
  para passar a porta de origem); tradução para outros idiomas antes de
  publicar fora do Brasil.
- Endurecimento futuro: senha SOCKS entre o túnel e o filtro e o SOCKS do Tor
  num soquete Unix (hoje as portas 19055/19050 ficam em 127.0.0.1, que outros
  apps do aparelho também alcançam; a porta de controle já exige cookie).

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
