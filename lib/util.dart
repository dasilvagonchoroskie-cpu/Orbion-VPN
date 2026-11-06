/// Funções puras de formatação (testadas em test/util_test.dart).
library;

import 'motor.dart';

String formatarBytes(int bytes) {
  if (bytes < 1024) return '$bytes B';
  if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
  if (bytes < 1024 * 1024 * 1024) return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(2)} GB';
}

String formatarDuracao(Duration d) {
  if (d.isNegative) return '—';
  final h = d.inHours;
  final m = d.inMinutes % 60;
  final s = d.inSeconds % 60;
  if (h > 0) return '${h}h ${m.toString().padLeft(2, '0')}min';
  if (m > 0) return '${m}min ${s.toString().padLeft(2, '0')}s';
  return '${s}s';
}

String nomeDoCaminho(String caminho) {
  switch (caminho) {
    case 'SNOWFLAKE':
      return 'voluntários rotativos (Snowflake)';
    case 'OBFS4':
      return 'entrada disfarçada (obfs4)';
    default:
      return 'entrada normal';
  }
}

String horaCurta(int milissegundos) {
  final t = DateTime.fromMillisecondsSinceEpoch(milissegundos);
  String dois(int n) => n.toString().padLeft(2, '0');
  return '${dois(t.hour)}:${dois(t.minute)}:${dois(t.second)}';
}

String dataEHora(int milissegundos) {
  final t = DateTime.fromMillisecondsSinceEpoch(milissegundos);
  String dois(int n) => n.toString().padLeft(2, '0');
  return '${dois(t.day)}/${dois(t.month)} ${dois(t.hour)}:${dois(t.minute)}';
}

/// Linhas de bridge válidas digitadas pela pessoa (sem comentários e vazias).
List<String> linhasDeBridge(String texto) => texto
    .split('\n')
    .map((l) => l.trim())
    .where((l) => l.isNotEmpty && !l.startsWith('#'))
    .map((l) => l.toLowerCase().startsWith('bridge ') ? l.substring(7).trim() : l)
    .where((l) => l.isNotEmpty)
    .toList();

/// O que dizer depois de abrir uma tela do Android (null = nada a dizer).
String? mensagemDaAcao(String codigo) {
  switch (codigo) {
    case 'JA_LIBERADO':
      return 'O Orbion já está liberado na bateria.';
    case 'MARCA':
      return 'Na tela que abriu, deixe o Orbion VPN sem restrições de bateria e volte para cá.';
    case 'LISTA':
      return 'Mude a lista para "Todos os apps" (All apps), toque em Orbion VPN e escolha "Não otimizar" (Don\'t optimize).';
    case 'APP_INFO':
      return 'Nas informações do app, toque em Bateria (Battery) e escolha "Sem restrições" (Unrestricted).';
    case 'AUTOSTART':
      return 'Ligue o início automático (Autostart) do Orbion VPN e volte para cá.';
    case 'NADA':
    case 'FALHOU':
      return 'Não consegui abrir essa tela neste celular. Faça pelo caminho: Configurações (Settings) → Apps → Orbion VPN.';
    default:
      return null; // OK, PERGUNTA: a própria tela do Android já aparece.
  }
}

/// Como a rota de um app aparece na lista.
String textoDaRota(AppRota a) => switch (a.rota) {
      RotaApp.tor => 'Pela rede Tor',
      RotaApp.torManual => 'Pela rede Tor  •  você escolheu',
      RotaApp.foraManual => 'Por fora do Tor  •  você escolheu',
      RotaApp.foraAutomatico => 'Por fora do Tor  •  ${a.categoria ?? 'automático'}',
    };

/// Serviços do Google que entregam as notificações de todos os apps.
const Set<String> servicosDoGoogle = {
  'com.google.android.gms',
  'com.google.android.gsf',
  'com.android.vending',
};

/// Pergunta antes de mandar o app pela rede Tor? (null = muda direto)
/// Só nos casos em que algo importante quebra sem a pessoa perceber.
String? avisoAoMandarPeloTor(AppRota a) {
  if (servicosDoGoogle.contains(a.pacote)) {
    return 'Os serviços do Google entregam as notificações de quase todos os apps (WhatsApp, banco, e-mail...). '
        'Pela rede Tor elas podem atrasar ou deixar de chegar, e a Play Store pode falhar ao baixar e atualizar apps.\n\n'
        'O recomendado é deixar por fora: o Google já sabe quem é você pela conta do celular, então o Tor esconde pouco aqui.';
  }
  if (a.categoriaId == 'mensageiros') {
    return 'Chamadas de voz e de vídeo costumam não completar pela rede Tor, e as mensagens podem atrasar.\n\n'
        'O recomendado é deixar por fora: as conversas continuam protegidas pela criptografia do próprio app.';
  }
  return null;
}

/// O que dizer depois de mudar a rota de um app.
String textoDaMudancaDeRota(AppRota a) {
  if (!a.pelaTor) return '${a.nome} agora vai por fora do Tor.';
  if (a.categoriaId == 'bancos' || a.categoriaId == 'governo') {
    return '${a.nome} agora vai pela rede Tor. Se ele parar de abrir, é só desligar a chave de novo.';
  }
  return '${a.nome} agora vai pela rede Tor.';
}

final RegExp _dominioValido = RegExp(r'^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?)+$');

/// Limpa o endereço digitado ("https://www.Banco.com.br/x" → "banco.com.br").
/// Igual ao que o motor faz; null = não é um endereço de site.
String? normalizarDominio(String texto) {
  var t = texto.trim().toLowerCase();
  final i = t.indexOf('://');
  if (i >= 0) t = t.substring(i + 3);
  for (final corte in ['/', '?', '#', ':']) {
    final p = t.indexOf(corte);
    if (p >= 0) t = t.substring(0, p);
  }
  if (t.startsWith('*.')) t = t.substring(2);
  while (t.startsWith('.')) {
    t = t.substring(1);
  }
  while (t.endsWith('.')) {
    t = t.substring(0, t.length - 1);
  }
  if (t.startsWith('www.')) t = t.substring(4);
  if (t.length < 3 || t.length > 253 || !_dominioValido.hasMatch(t)) return null;
  return t;
}

// ---------------------------------------------------------------------------
// Termos, privacidade e aviso de uso da VPN
// ---------------------------------------------------------------------------

/// Versão dos Termos de Uso + Política de Privacidade. Mudou de forma
/// importante? Suba o número: o app pede o aceite de novo.
const int versaoDosTermos = 1;

/// Versão do aviso "como o Orbion usa a VPN do Android" (o motor usa o mesmo
/// número em Preferencias.VERSAO_AVISO_VPN).
const int versaoDoAvisoVpn = 1;

enum TipoDeBloco { titulo, secao, subsecao, paragrafo, item }

class BlocoDeTexto {
  final TipoDeBloco tipo;
  final String texto;
  const BlocoDeTexto(this.tipo, this.texto);
}

/// Lê os documentos de assets/legal ("# título", "## seção", "### subseção",
/// "- item" e parágrafos separados por linha em branco). O site usa o mesmo
/// formato (scripts/gerar-paginas-legais.py), então o texto é sempre igual.
List<BlocoDeTexto> lerDocumento(String texto) {
  final blocos = <BlocoDeTexto>[];
  final paragrafo = StringBuffer();
  void fechar() {
    if (paragrafo.isNotEmpty) {
      blocos.add(BlocoDeTexto(TipoDeBloco.paragrafo, paragrafo.toString().trim()));
      paragrafo.clear();
    }
  }

  for (final bruta in texto.split('\n')) {
    final linha = bruta.trimRight();
    if (linha.trim().isEmpty) {
      fechar();
    } else if (linha.startsWith('### ')) {
      fechar();
      blocos.add(BlocoDeTexto(TipoDeBloco.subsecao, linha.substring(4).trim()));
    } else if (linha.startsWith('## ')) {
      fechar();
      blocos.add(BlocoDeTexto(TipoDeBloco.secao, linha.substring(3).trim()));
    } else if (linha.startsWith('# ')) {
      fechar();
      blocos.add(BlocoDeTexto(TipoDeBloco.titulo, linha.substring(2).trim()));
    } else if (linha.startsWith('- ')) {
      fechar();
      blocos.add(BlocoDeTexto(TipoDeBloco.item, linha.substring(2).trim()));
    } else {
      if (paragrafo.isNotEmpty) paragrafo.write(' ');
      paragrafo.write(linha.trim());
    }
  }
  fechar();
  return blocos;
}

/// Nome legível de um componente a partir do arquivo de licença.
String nomeDoComponente(String caminho) {
  final arquivo = caminho.split('/').last.replaceAll(RegExp(r'\.txt$'), '');
  const nomes = {
    'tor': 'Tor',
    'libevent': 'libevent',
    'openssl': 'OpenSSL',
    'zlib': 'zlib',
    'zstd-bsd': 'Zstandard (zstd)',
    'zstd-gpl2': 'Zstandard (zstd), licença GPLv2',
    'lyrebird': 'lyrebird (obfs4)',
    'conjure': 'Conjure',
    'hev-socks5-tunnel': 'hev-socks5-tunnel',
    'hev-task-system': 'hev-task-system',
    'lwip': 'lwIP',
    'yaml': 'libyaml',
    'snowflake': 'Snowflake',
  };
  return nomes[arquivo] ?? arquivo;
}

// ---------------------------------------------------------------------------
// Registro técnico (Registros › Técnico)
// ---------------------------------------------------------------------------

/// Gravidade de uma linha do registro técnico.
enum NivelDaLinha { erro, aviso, progresso, normal }

/// Uma linha do registro técnico, já classificada.
class LinhaTecnica {
  final String texto;
  final NivelDaLinha nivel;

  /// Explicação em português das mensagens mais comuns do Tor (quase todas
  /// são do funcionamento normal). Nula quando a mensagem não é conhecida.
  final String? explicacao;

  const LinhaTecnica(this.texto, this.nivel, [this.explicacao]);
}

/// Etiqueta que o próprio Tor põe em cada linha: "[notice]", "[warn]", "[err]".
final RegExp _etiquetaDoTor = RegExp(r'\[(err|warn|notice|info|debug)\]');

/// Etiqueta do túnel (hev-socks5-tunnel): "[2026-11-06 07:33:09] [W] ...".
final RegExp _etiquetaDoTunel = RegExp(r'^\[[^\]]+\] \[([DIWE])\] ');

/// Avisos ("[warn]") do Tor que não indicam problema nenhum.
///  - "No lists of TLS groups were supported": desde o Tor 0.4.9.15 (Tor
///    Browser 16.0) aparece a cada vez que o Tor liga; ele passa a usar a
///    lista padrão do OpenSSL 3.5, que é moderna e já inclui a troca de chaves
///    pós-quântica. A conexão continua protegida (registro de 06/11/2026).
const List<String> _avisosInofensivos = ['No lists of TLS groups were supported'];

final RegExp _progressoDoTor = RegExp(r'Bootstrapped (\d+)%');
final RegExp _versaoDoTor = RegExp(r'\] Tor (\d+\.\d+\.\d+\.\d+)');

int _campoDoResumo(String linha, String campo) =>
    int.tryParse(RegExp('\\b$campo=(\\d+)').firstMatch(linha)?.group(1) ?? '') ?? 0;

/// Classifica uma linha do registro técnico.
///
/// A gravidade vem da etiqueta que o Tor (ou o túnel) escreveu, e não de
/// palavras soltas: o resumo periódico do Tor traz "connection_failed=0" e,
/// antes, aparecia em vermelho como se fosse erro.
LinhaTecnica lerLinhaTecnica(String linha) {
  final doTor = _etiquetaDoTor.firstMatch(linha)?.group(1);
  final doTunel = doTor == null ? _etiquetaDoTunel.firstMatch(linha)?.group(1) : null;
  final NivelDaLinha nivel;
  if (doTor == 'err' || doTunel == 'E') {
    nivel = NivelDaLinha.erro;
  } else if ((doTor == 'warn' && !_avisosInofensivos.any(linha.contains)) || doTunel == 'W') {
    nivel = NivelDaLinha.aviso;
  } else if (_progressoDoTor.hasMatch(linha)) {
    nivel = NivelDaLinha.progresso;
  } else if (doTor == null && doTunel == null && _pareceFalhaDoOrbion(linha)) {
    // Linhas escritas pelo próprio Orbion (em português, sem etiqueta).
    nivel = NivelDaLinha.aviso;
  } else {
    nivel = NivelDaLinha.normal;
  }
  return LinhaTecnica(linha, nivel, explicarLinhaDoTor(linha));
}

bool _pareceFalhaDoOrbion(String linha) {
  final l = linha.toLowerCase();
  return l.contains('não foi possível') || l.contains('falhou');
}

/// Explica em português as mensagens mais comuns do Tor. Em inglês, mesmo as
/// normais assustam quem lê.
String? explicarLinhaDoTor(String linha) {
  bool tem(String trecho) => linha.contains(trecho);

  final progresso = _progressoDoTor.firstMatch(linha);
  if (progresso != null) {
    final pct = progresso.group(1);
    return pct == '100' ? 'Conectado à rede Tor.' : 'Conectando à rede Tor: $pct%.';
  }
  final versao = _versaoDoTor.firstMatch(linha);
  if (versao != null && tem(' running on ')) return 'Versão do Tor embutida no Orbion: ${versao.group(1)}.';
  if (tem('Circuit build expiry summary')) {
    final prontos = _campoDoResumo(linha, 'completed');
    final cortados = _campoDoResumo(linha, 'connection_failed');
    final extra = cortados > 0
        ? ', $cortados ${cortados == 1 ? 'cortado' : 'cortados'} por falha de conexão (troca de rede ou sinal fraco)'
        : '';
    return 'Normal: resumo periódico do Tor — $prontos ${prontos == 1 ? 'circuito montado' : 'circuitos montados'}$extra.';
  }
  if (tem('excluded by path restriction')) return 'Normal: o Tor usou mais um nó de entrada para montar um circuito.';
  if (tem('DisableNetwork is set')) {
    return 'Normal: o Orbion pausou o Tor por um instante para trocar de rede (Wi-Fi ⇄ dados móveis).';
  }
  if (tem('no-longer-configured')) return 'Normal: pausa de um instante na troca de rede.';
  if (tem('New control connection opened')) return 'Normal: o Orbion deu uma ordem ao Tor (por exemplo, na troca de rede).';
  if (tem(' listener')) return 'Normal: porta interna do Orbion, que só existe dentro do celular.';
  if (tem("Tor can't help you if you use it wrong")) return 'Aviso padrão do Tor: aparece sempre que ele liga.';
  if (tem('Read configuration file')) return 'Normal: o Tor leu a configuração do Orbion.';
  if (tem('Set list of supported TLS groups')) return 'Normal: tipos de criptografia que o Tor vai usar.';
  if (tem('No lists of TLS groups were supported')) {
    return 'Inofensivo: o Tor usa a criptografia padrão do OpenSSL, moderna e com proteção pós-quântica. '
        'A conexão continua protegida.';
  }
  if (tem('Starting with guard context')) return 'Normal: o Tor escolhe os nós de entrada da rede.';
  if (tem('Tor has successfully opened a circuit')) return 'Tudo certo: o Tor está funcionando.';
  if (tem('Heartbeat:')) return 'Normal: resumo periódico do Tor.';
  if (tem('Your system clock just jumped')) return 'Normal: o celular estava dormindo; o Tor refaz os circuitos sozinho.';
  if (tem('has not observed any network activity')) return 'Normal: o celular ficou um tempo sem usar a internet.';
  if (tem("haven't used client functionality lately")) return 'Normal: o celular voltou a usar a internet.';
  if (tem('directory information is no longer up-to-date')) {
    return 'O Tor está atualizando a lista de servidores (normal depois de muito tempo sem internet).';
  }
  if (tem('learned some more directory information')) return 'Normal: o Tor está baixando a lista de servidores da rede.';
  if (tem('now have enough directory information')) return 'Normal: o Tor já pode montar circuitos.';
  if (tem('Have tried resolving or connecting to address')) {
    return 'Um site ou app não respondeu pelo Tor. Se for sempre o mesmo, a rota inteligente cuida disso.';
  }
  if (tem('is failing') && tem('circuits')) return 'Um nó de entrada do Tor está sobrecarregado; o Tor se ajusta sozinho.';
  if (tem('Failed to find node for hop')) return 'O Tor descartou um circuito e já está montando outro.';
  if (tem('Problem bootstrapping')) {
    return 'Dificuldade para conectar ao Tor (sinal fraco ou bloqueio). O Orbion tenta outro caminho sozinho.';
  }
  if (tem('Received http status code')) return 'Normal: um servidor do Tor não tinha essa informação; o Tor pede a outro.';
  if (tem('exiting cleanly') || tem('will shut down in')) return 'Normal: o Orbion desligou o Tor.';
  if (tem('new bridge descriptor')) return 'Normal: ponte (bridge) encontrada.';
  return null;
}

/// Quantos erros e avisos há nas linhas (resumo no topo do Técnico).
({int erros, int avisos}) contarProblemas(Iterable<LinhaTecnica> linhas) {
  var erros = 0;
  var avisos = 0;
  for (final l in linhas) {
    if (l.nivel == NivelDaLinha.erro) erros++;
    if (l.nivel == NivelDaLinha.aviso) avisos++;
  }
  return (erros: erros, avisos: avisos);
}

/// A frase do topo do Técnico: diz logo se há algo errado.
String resumoDoRegistro(int erros, int avisos) {
  if (erros > 0) {
    return '$erros ${erros == 1 ? 'erro' : 'erros'} nestas linhas. Se a VPN não estiver funcionando, toque em "Enviar relatório".';
  }
  if (avisos > 0) {
    return 'Nenhum erro. $avisos ${avisos == 1 ? 'aviso' : 'avisos'}, quase sempre da internet (sinal fraco ou site fora do ar).';
  }
  return 'Nenhum erro nem aviso: as mensagens abaixo são do funcionamento normal.';
}
