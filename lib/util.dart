/// Funções puras de formatação (testadas em test/util_test.dart).
library;

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
    case 'XIAOMI':
      return 'Na tela da Xiaomi, escolha "Sem restrições" e volte para o Orbion.';
    case 'LISTA':
      return 'Mude a lista para "Todos os apps", toque em Orbion VPN e escolha "Não otimizar".';
    case 'APP_INFO':
      return 'Nas informações do app, toque em Bateria e escolha "Sem restrições".';
    case 'AUTOSTART':
      return 'Ligue o "Início automático" do Orbion VPN e volte para cá.';
    case 'NADA':
    case 'FALHOU':
      return 'Não consegui abrir essa tela neste celular. Faça pelo caminho: Configurações → Apps → Orbion VPN.';
    default:
      return null; // OK, PERGUNTA: a própria tela do Android já aparece.
  }
}
