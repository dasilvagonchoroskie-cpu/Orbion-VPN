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
