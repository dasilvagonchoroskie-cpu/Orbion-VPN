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
