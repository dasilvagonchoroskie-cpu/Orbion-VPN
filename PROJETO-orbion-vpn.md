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
- v6.2.0 (11/10/2026): relatório das 15:12 depois de trocar Wi-Fi → dados
  móveis (rede de casa fraca): "loop de erros" — crítico (seção 5.5). E o
  pedido do Evandro: deixar 100% pronto para vender na Play Store, com
  Política de Privacidade, Termos de Uso e a parte de "não coletamos dados"
  (seção 8).

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
- Privacidade: o Orbion NÃO coleta dados. Sem cadastro, anúncios, rastreadores
  ou estatísticas; sem servidores próprios no caminho do tráfego; tudo fica no
  aparelho. Só recebemos o relatório que a pessoa decidir mandar (guardado até
  90 dias depois do atendimento).
- Política de Privacidade e Termos de Uso (LGPD, CDC, Marco Civil): fonte
  única em `assets/legal/privacidade.md` e `termos.md`. O app mostra esses
  arquivos (sem internet) e o site é gerado deles por
  `scripts/gerar-paginas-legais.py` → https://fortalezadigitalsecurity.com.br/orbion/
  (privacidade.html e termos.html, no repositório público
  `fortaleza-digital-site`, pasta `orbion/`). Mudou o texto? Editar o .md,
  subir `versaoDosTermos` (lib/util.dart) se a mudança for importante,
  regerar as páginas e publicar no site. Contato usado nos textos:
  fortalezadigitalsecurity@gmail.com (o do rodapé do site).
- Primeiro uso: boas-vindas com aceite dos Termos + Política, e depois o aviso
  em destaque de uso da VPN (VpnService) com "Concordo" — exigência da Play
  Store, separado de outros avisos. Instalação nova só liga a VPN depois do
  aceite (o motor confere `aviso_vpn_aceito`; quem já usava antes continua
  protegido até abrir o app). Versões: `versaoDosTermos` e `versaoDoAvisoVpn`
  (Dart) = `Preferencias.VERSAO_AVISO_VPN` (Kotlin).
- Licenças de código aberto: a esteira copia os textos (Tor, libevent,
  OpenSSL, zlib, zstd, lyrebird, conjure, hev-socks5-tunnel, hev-task-system,
  lwIP, libyaml, Snowflake) para `assets/licencas/` e o app mostra em
  Config → Sobre → Licenças de código aberto (obrigatório por distribuir
  esses programas). A esteira falha se faltar a do Tor ou a do túnel.
- Erro não tratado numa linha de fundo do processo da VPN NÃO derruba a VPN
  (Falhas.kt): o rastro vai para `falhas.log` / relatório e a VPN segue.

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

Saúde da rede Tor (desde a 6.2.0): uma falha só pesa contra o destino (pausa
e desvio automático) se o Tor funcionou para outro destino há menos de 60 s E
acertou pelo menos 70% das conexões dos outros nos últimos 2 min. Quando 4+
destinos falham sem nada funcionar desde que começaram, ou menos de 30% dá
certo em 2 min, é "rede instável": ninguém é pausado nem desviado e o Painel
mostra "Conexão instável". A troca de rede limpa as pausas.

Limites (desde a 6.2.0): filtro com no máximo 480 linhas (2 por conexão) e
96 conexões esperando a resposta do Tor (as demais falham na hora); túnel
(hev) com no máximo 224 conexões simultâneas (fecha as mais paradas primeiro).
O DNS do túnel usa uma faixa interna nova a cada início (100.64/12 →
100.80/12 → 100.96/12 → 100.112/12).

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
- `Vigia`, `BootReceiver`, `Preferencias` (versão 2 da configuração; aceites `termos_aceitos` e `aviso_vpn_aceito`), `Painel`
- `Falhas` — guarda o rastro de erros não tratados (`falhas.log`) nos dois processos; no da VPN, erro em linha de fundo é contido

Interface (Flutter, `lib/`): `main.dart` (carregando → boas-vindas/aceite →
4 abas: Painel, Apps, Registros, Config), `controlador.dart` (religa sozinho
ao abrir se o sistema derrubou; não liga nada antes do aceite), `motor.dart`
(modelos + chamadas), `legal.dart` (boas-vindas, aviso da VPN, leitor da
política e dos termos, licenças), `telas/painel|apps|registros|config.dart`,
`widgets/comuns.dart`, `tema.dart` (neon escuro), `util.dart` (+ testes).
- Apps: cada app com a rota (pela rede Tor / por fora e por quê); a chave muda
  na hora com "Desfazer"; "Voltar ao automático" apaga as escolhas manuais.
- Registros: "Enviar relatório" no topo; Decisões (tocar num site → sempre por
  fora / sempre pelo Tor / automático), Eventos (quedas + diário) e Técnico
  (copiar = últimas 150 linhas). Só consulta o motor com a aba à vista.
- Config: diagnóstico, Rota inteligente (categorias, desvio automático,
  desviados na sessão, em pausa, sites por sua conta), criptografia,
  segurança, automático, rede Tor, DNS, sobre (enviar relatório) e
  Privacidade e termos (política, termos, como usamos a VPN, licenças,
  falar com o suporte).

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

### 5.5 A troca de Wi-Fi para dados móveis (corrigido na 6.2.0)

Relatório de 11/10/2026 15:12 (6.1.2), com dados móveis fracos em casa:
1. Na 6.1.1 o processo da VPN caiu 2× (05:29 e 05:30, "erro interno") logo
   depois de trocar de rede e de reconectar: todos os apps reconectavam ao
   mesmo tempo, cada conexão usa 2 linhas no filtro (teto era 900) e o
   Android recusava criar mais linhas — o OutOfMemoryError de
   pthread_create não era capturado e derrubava a VPN, que só voltou quando o
   Evandro abriu o app. Corrigido: tetos menores (seção 4), nenhuma falha de
   conexão derruba o processo, erros de linha de fundo contidos e com rastro
   no relatório.
2. "Loop de erros" na 6.1.2 com dados móveis: o Tor demorava, cada site que
   falhava 3× era pausado (238 PAUSADO na sessão) e alguns foram desviados
   para fora do Tor (4) como se recusassem o Tor — culpa da rede, não dos
   sites. Corrigido com a saúde da rede Tor (seção 4) e o aviso "Conexão
   instável" no Painel e em Registros.
3. 3 destinos continuavam em pausa 4 min depois de voltar ao Wi-Fi: a troca
   de rede agora limpa as pausas.
4. Aviso interno do Tor "Bug: Tried to open a socket with DisableNetwork set"
   na troca de rede (defeito conhecido e inofensivo do Tor): fora do registro,
   só contado no relatório.
5. Teste na JVM (`teste-filtro`): 40 conexões com 256 KB voltam inteiras;
   180 conexões com o Tor mudo usam ~110 linhas (as que passam do limite
   falham na hora); ninguém é pausado; um site que recusa o Tor enquanto os
   outros funcionam é pausado depois de 3 falhas.

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
  (execução 12); a 6.1.2, 2073 (execução 13); a 6.2.0 entregue é 2074
  (execução 14).
- APK para celular: `Orbion-VPN-v6.2.0-arm64-v8a.apk`, SHA-256
  ce66ab36453e448b93434272ea69b4ee56e9ace93611e199e287093672edcb0f.
  Instala por cima da 6.1.x sem desinstalar (mesma chave).
- A esteira mostra no resumo os avisos do `flutter analyze` e o total de
  testes (a sessão do Claude não consegue baixar os registros completos).

## 7. Status

- v6.1.0 testada pelo Evandro no celular (11/10/2026): não travou nem fechou.
- v6.1.1 instalada pelo Evandro em 11/10/2026 05:26; relatório 1 min depois
  (seção 5.4). v6.1.2 instalada às 05:46; relatório às 15:12 (seção 5.5).
- v6.2.0 compilada (esteira execução 14: análise sem avisos, 18 testes do
  Flutter, 13 licenças, manifesto e 16 KB ok) e assinada em 11/10/2026. Tor
  Browser 15.0.24, hev-socks5-tunnel 2.18.0, 7 bridges obfs4 + 2 snowflake
  embutidas. Conferido localmente: o motor Kotlin compila, 79 testes (rota
  inteligente, disjuntor, faixas do túnel, saúde da rede Tor, arquivo de
  falhas) e o teste de carga do filtro passam. Ainda não testada no aparelho.
- Ao abrir a 6.2.0 pela primeira vez aparecem as boas-vindas: aceitar os
  termos e o aviso de uso da VPN (a VPN do Evandro continua ligada enquanto
  isso).
- O que testar no celular: (1) trocar Wi-Fi ↔ dados móveis algumas vezes e
  usar os apps: a VPN não pode cair; com a rede fraca o Painel mostra
  "Conexão instável" e Registros não enche de PAUSADO; depois mandar o
  relatório (seções "ERROS INTERNOS" e "Rede Tor instável"); (2) ficar sem
  sinal: "Sem internet no aparelho" e volta sozinho; (3) Config → Sobre →
  Privacidade e termos: abrir política, termos, licenças e "Falar com o
  suporte"; (4) bancos e gov.br abrindo com a VPN ligada.
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

Pesquisado em 11/10/2026 nas páginas de ajuda da Play Console (VpnService,
requisitos da conta, Segurança dos dados, teste fechado).

### 8.1 Bloqueio: conta de desenvolvedor do tipo Organização

A Google exige que apps aprovados para usar a classe VpnService sejam
publicados por conta do tipo **Organização** (a conta Pessoal não serve).
A conta de Organização pede o número **D-U-N-S** (gratuito, da Dun &
Bradstreet), que é tirado com um **CNPJ** — os dados da conta têm de bater
com o cadastro D-U-N-S. Sem CNPJ não dá para publicar o Orbion na Play Store.
(Quando houver CNPJ, colocar o número na seção 1 da Política de Privacidade
e nos Termos, junto com o nome empresarial.) A exigência de teste fechado com
12 testadores por 14 dias é das contas pessoais novas.

### 8.2 Já pronto no app e no site

- Política de Privacidade: https://fortalezadigitalsecurity.com.br/orbion/privacidade.html
  (a Play Console exige o link mesmo para app que não coleta dados; ela
  também está dentro do app).
- Termos de Uso: https://fortalezadigitalsecurity.com.br/orbion/termos.html
- Aviso em destaque de uso da VPN dentro do app, com concordância ("Concordo
  e continuar"), separado de outros avisos.
- Licenças de código aberto no app.
- Manifesto sem QUERY_ALL_PACKAGES; páginas de 16 KB; serviço em primeiro
  plano `systemExempted|specialUse` (subtipo "vpn").

### 8.3 Respostas para a Play Console

- Declaração de VpnService: VPN é a função principal. Texto: "O Orbion cria
  um túnel VPN no aparelho e encaminha o tráfego dos aplicativos,
  criptografado, pela rede Tor (rede pública de retransmissores voluntários),
  com rota inteligente para os serviços que recusam o Tor. Não usamos
  servidores próprios e não coletamos dados do tráfego." O formulário pede um
  vídeo: gravar a tela abrindo o app pela primeira vez (boas-vindas → aviso de
  uso da VPN → Concordo), tocando em Conectar (janela de permissão do
  Android) e o Painel conectado.
- Segurança dos dados (Data safety): recomendado responder que coleta só
  "Histórico de navegação na Web" — processado de forma efêmera (os nomes dos
  sites que vão por fora do Tor são consultados no DNS criptografado da
  Cloudflare/Google, sem guardar nada), não compartilhado, opcional (some ao
  desligar a rota inteligente), finalidade "Funcionalidade do app"; dados
  criptografados em trânsito: sim; pedido de exclusão: sim (e-mail). Dado
  efêmero não aparece no selo público, que fica "Nenhum dado coletado". O
  relatório de suporte é enviado pela própria pessoa, por outro app, e está
  descrito na política.
- Serviço em primeiro plano (Android 14+): tipo specialUse/systemExempted —
  "Serviço da VPN (VpnService) que mantém o túnel ativo enquanto a pessoa
  quer a proteção". Também pede vídeo (o mesmo serve).
- Público-alvo: 18 anos ou mais (fica fora das regras de apps para crianças).
- Anúncios: não. Acesso ao app: tudo disponível sem login.
- Classificação de conteúdo: ferramenta/utilitário, sem conteúdo próprio.
- Ficha da loja — descrição curta (até 80 caracteres): "VPN pela rede Tor
  com rota inteligente e Kill Switch. Não coletamos seus dados."
- Ficha da loja — a descrição completa precisa citar o uso do VpnService.
  Sugestão: "O Orbion VPN usa o recurso de VPN do Android (VpnService) para
  encaminhar o tráfego dos seus aplicativos pela rede Tor, escondendo o seu
  endereço de internet (IP) dos sites e serviços. Rota inteligente: bancos,
  governo, mensageiros e outros serviços que recusam o Tor vão direto, às
  claras, e você pode mudar o caminho de qualquer app ou site. Kill Switch:
  se a rede Tor cair, o tráfego que depende dela fica bloqueado em vez de
  sair sem proteção. Bloqueio de rastreadores e contorno automático de
  bloqueios (pontes obfs4 e Snowflake). Não coletamos seus dados: sem
  cadastro, sem anúncios, sem rastreadores e sem servidores próprios — as
  configurações e os registros ficam só no seu aparelho."
- Enviar como Android App Bundle (.aab) com a Play App Signing (a chave do
  Orbion vira a chave de envio). A esteira hoje gera APKs; gerar o .aab
  (`flutter build appbundle`) quando a conta estiver pronta.
- Se a revisão recusar REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: apagar a linha do
  manifesto (o app já se adapta).
- Recomendado: um advogado revisar a Política e os Termos antes de publicar,
  principalmente depois de ter o CNPJ e o modelo de cobrança (compra única ou
  assinatura).
