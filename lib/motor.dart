import 'package:flutter/services.dart';

/// Ponte com o motor nativo (Kotlin) pelo canal "orbion/motor".
///
/// O motor — Tor, túnel, filtro, Kill Switch — continua nativo, porque é ele
/// que conversa com o Android (VpnService). Toda a interface é Flutter.
class Motor {
  static const MethodChannel _canal = MethodChannel('orbion/motor');

  static Future<T?> _chamar<T>(String metodo, [Object? argumentos]) async {
    try {
      return await _canal.invokeMethod<T>(metodo, argumentos);
    } on PlatformException {
      rethrow;
    } on MissingPluginException {
      return null;
    }
  }

  static Future<StatusVpn> status() async {
    final m = await _chamar<Map<dynamic, dynamic>>('status');
    return StatusVpn.deMapa(m ?? const {});
  }

  /// Pede a permissão de VPN (se preciso) e liga. Retorna false se a pessoa negou.
  static Future<bool> conectar() async => (await _chamar<bool>('conectar')) ?? false;
  static Future<void> desconectar() => _chamar('desconectar');
  static Future<bool> reconectar() async => (await _chamar<bool>('reconectar')) ?? false;
  static Future<void> novaIdentidade() => _chamar('novaIdentidade');
  static Future<void> limparAvisos() => _chamar('limparAvisos');

  static Future<Config> lerConfig() async {
    final m = await _chamar<Map<dynamic, dynamic>>('lerConfig');
    return Config.deMapa(m ?? const {});
  }

  static Future<Config> salvarConfig(Map<String, Object?> mudancas) async {
    final m = await _chamar<Map<dynamic, dynamic>>('salvarConfig', mudancas);
    return Config.deMapa(m ?? const {});
  }

  static Future<List<AppInstalado>> listarApps({bool incluirSistema = false}) async {
    final l = await _chamar<List<dynamic>>('listarApps', {'incluirSistema': incluirSistema});
    return (l ?? const [])
        .map((e) => AppInstalado.deMapa(e as Map<dynamic, dynamic>))
        .toList();
  }

  static Future<Map<String, Uint8List>> icones(List<String> pacotes) async {
    final m = await _chamar<Map<dynamic, dynamic>>('icones', {'pacotes': pacotes});
    final saida = <String, Uint8List>{};
    (m ?? const {}).forEach((k, v) {
      if (v is Uint8List) saida[k as String] = v;
    });
    return saida;
  }

  static Future<Registros> registros({int max = 200}) async {
    final m = await _chamar<Map<dynamic, dynamic>>('registros', {'max': max});
    return Registros.deMapa(m ?? const {});
  }

  static Future<LogTecnico> logTecnico({int max = 300}) async {
    final m = await _chamar<Map<dynamic, dynamic>>('logTecnico', {'max': max});
    List<String> lista(Object? x) => (x as List<dynamic>? ?? const []).map((e) => '$e').toList();
    return LogTecnico(tor: lista(m?['tor']), tunel: lista(m?['tunel']));
  }

  static Future<List<Suspeita>> suspeitas() async {
    final l = await _chamar<List<dynamic>>('suspeitas');
    return (l ?? const []).map((e) => Suspeita.deMapa(e as Map<dynamic, dynamic>)).toList();
  }

  static Future<List<AppProblematico>> appsProblematicos() async {
    final l = await _chamar<List<dynamic>>('appsProblematicos');
    return (l ?? const [])
        .map((e) => AppProblematico.deMapa(e as Map<dynamic, dynamic>))
        .toList();
  }

  static Future<List<ItemDiagnostico>> diagnostico() async {
    final l = await _chamar<List<dynamic>>('diagnostico');
    return (l ?? const [])
        .map((e) => ItemDiagnostico.deMapa(e as Map<dynamic, dynamic>))
        .toList();
  }

  static Future<InfoMotor> infoMotor() async {
    final m = await _chamar<Map<dynamic, dynamic>>('infoMotor');
    return InfoMotor.deMapa(m ?? const {});
  }

  static Future<AvisosRapidos> avisosRapidos() async {
    final m = await _chamar<Map<dynamic, dynamic>>('avisosRapidos');
    return AvisosRapidos.deMapa(m ?? const {});
  }

  static Future<bool> executarAcao(String acao) async =>
      (await _chamar<bool>('executarAcao', {'acao': acao})) ?? false;
}

// ---------------------------------------------------------------------------
// Modelos
// ---------------------------------------------------------------------------

enum EstadoVpn { desconectado, conectando, conectado, bloqueado, erro }

EstadoVpn _estado(Object? s) {
  switch (s) {
    case 'CONECTANDO':
      return EstadoVpn.conectando;
    case 'CONECTADO':
      return EstadoVpn.conectado;
    case 'BLOQUEADO':
      return EstadoVpn.bloqueado;
    case 'ERRO':
      return EstadoVpn.erro;
    default:
      return EstadoVpn.desconectado;
  }
}

int _int(Object? v) => v is num ? v.toInt() : 0;
bool _bool(Object? v, [bool padrao = false]) => v is bool ? v : padrao;
String? _str(Object? v) => v is String && v.isNotEmpty ? v : null;

class StatusVpn {
  final EstadoVpn estado;
  final int percentual;
  final String mensagem;
  final String? erro;
  final int conectadoDesde;
  final int enviadoBytes;
  final int recebidoBytes;
  final int velocidadeEnvio;
  final int velocidadeRecebimento;
  final String? contornoDeBloqueio;
  final String? avisoDeIdentidade;
  final String caminhoEmUso;
  final List<String> appsForaNaSessao;
  final bool vpnSempreAtiva;
  final bool bloqueioSemVpn;
  final int quedasDoTor;

  const StatusVpn({
    this.estado = EstadoVpn.desconectado,
    this.percentual = 0,
    this.mensagem = '',
    this.erro,
    this.conectadoDesde = 0,
    this.enviadoBytes = 0,
    this.recebidoBytes = 0,
    this.velocidadeEnvio = 0,
    this.velocidadeRecebimento = 0,
    this.contornoDeBloqueio,
    this.avisoDeIdentidade,
    this.caminhoEmUso = 'DIRETO',
    this.appsForaNaSessao = const [],
    this.vpnSempreAtiva = false,
    this.bloqueioSemVpn = false,
    this.quedasDoTor = 0,
  });

  factory StatusVpn.deMapa(Map<dynamic, dynamic> m) => StatusVpn(
        estado: _estado(m['estado']),
        percentual: _int(m['percentual']),
        mensagem: (m['mensagem'] as String?) ?? '',
        erro: _str(m['erro']),
        conectadoDesde: _int(m['conectadoDesde']),
        enviadoBytes: _int(m['enviadoBytes']),
        recebidoBytes: _int(m['recebidoBytes']),
        velocidadeEnvio: _int(m['velocidadeEnvio']),
        velocidadeRecebimento: _int(m['velocidadeRecebimento']),
        contornoDeBloqueio: _str(m['contornoDeBloqueio']),
        avisoDeIdentidade: _str(m['avisoDeIdentidade']),
        caminhoEmUso: (m['caminhoEmUso'] as String?) ?? 'DIRETO',
        appsForaNaSessao: ((m['appsForaNaSessao'] as List<dynamic>?) ?? const []).map((e) => '$e').toList(),
        vpnSempreAtiva: _bool(m['vpnSempreAtiva']),
        bloqueioSemVpn: _bool(m['bloqueioSemVpn']),
        quedasDoTor: _int(m['quedasDoTor']),
      );

  bool get ligada =>
      estado == EstadoVpn.conectado || estado == EstadoVpn.conectando || estado == EstadoVpn.bloqueado;
}

class Config {
  final bool killSwitch;
  final bool bloquearRastreadores;
  final bool desvioDireto;
  final bool capturarIPv6;
  final bool escolherApps;
  final Set<String> appsForaDoTunel;
  final int intervaloRotacaoSegundos;
  final bool conectarAoAbrir;
  final bool conectarNoBoot;
  final bool usarBridges;
  final String bridges;
  final bool contornoAutomatico;
  final Set<String> avisosDispensados;

  const Config({
    this.killSwitch = true,
    this.bloquearRastreadores = true,
    this.desvioDireto = true,
    this.capturarIPv6 = true,
    this.escolherApps = false,
    this.appsForaDoTunel = const {},
    this.intervaloRotacaoSegundos = 600,
    this.conectarAoAbrir = false,
    this.conectarNoBoot = false,
    this.usarBridges = false,
    this.bridges = '',
    this.contornoAutomatico = true,
    this.avisosDispensados = const {},
  });

  factory Config.deMapa(Map<dynamic, dynamic> m) => Config(
        killSwitch: _bool(m['killSwitch'], true),
        bloquearRastreadores: _bool(m['bloquearRastreadores'], true),
        desvioDireto: _bool(m['desvioDireto'], true),
        capturarIPv6: _bool(m['capturarIPv6'], true),
        escolherApps: _bool(m['escolherApps']),
        appsForaDoTunel: ((m['appsForaDoTunel'] as List<dynamic>?) ?? const []).map((e) => '$e').toSet(),
        intervaloRotacaoSegundos: m['intervaloRotacaoSegundos'] is num ? _int(m['intervaloRotacaoSegundos']) : 600,
        conectarAoAbrir: _bool(m['conectarAoAbrir']),
        conectarNoBoot: _bool(m['conectarNoBoot']),
        usarBridges: _bool(m['usarBridges']),
        bridges: (m['bridges'] as String?) ?? '',
        contornoAutomatico: _bool(m['contornoAutomatico'], true),
        avisosDispensados: ((m['avisosDispensados'] as List<dynamic>?) ?? const []).map((e) => '$e').toSet(),
      );
}

class AppInstalado {
  final String pacote;
  final String nome;
  final bool sistema;
  const AppInstalado(this.pacote, this.nome, this.sistema);
  factory AppInstalado.deMapa(Map<dynamic, dynamic> m) =>
      AppInstalado('${m['pacote']}', '${m['nome']}', _bool(m['sistema']));
}

enum Destino { tor, direto, bloqueado }

class Registro {
  final String dominio;
  final Destino destino;
  final int quando;
  const Registro(this.dominio, this.destino, this.quando);
  factory Registro.deMapa(Map<dynamic, dynamic> m) {
    final d = switch (m['destino']) {
      'DIRETO' => Destino.direto,
      'BLOQUEADO' => Destino.bloqueado,
      _ => Destino.tor,
    };
    return Registro('${m['dominio']}', d, _int(m['quando']));
  }
}

class Registros {
  final int bloqueados;
  final int pelaRedeTor;
  final int diretos;
  final int ipv6Recusados;
  final List<Registro> lista;
  const Registros({
    this.bloqueados = 0,
    this.pelaRedeTor = 0,
    this.diretos = 0,
    this.ipv6Recusados = 0,
    this.lista = const [],
  });
  factory Registros.deMapa(Map<dynamic, dynamic> m) {
    final c = (m['contagem'] as Map<dynamic, dynamic>?) ?? const {};
    return Registros(
      bloqueados: _int(c['bloqueados']),
      pelaRedeTor: _int(c['pelaRedeTor']),
      diretos: _int(c['diretos']),
      ipv6Recusados: _int(c['ipv6Recusados']),
      lista: ((m['registros'] as List<dynamic>?) ?? const [])
          .map((e) => Registro.deMapa(e as Map<dynamic, dynamic>))
          .toList(),
    );
  }
}

class LogTecnico {
  final List<String> tor;
  final List<String> tunel;
  const LogTecnico({this.tor = const [], this.tunel = const []});
}

class Suspeita {
  final String servidor;
  final int tentativas;
  final String? servico;
  final String? motivo;
  final String? explicacao;
  const Suspeita(this.servidor, this.tentativas, this.servico, this.motivo, this.explicacao);
  factory Suspeita.deMapa(Map<dynamic, dynamic> m) => Suspeita(
        '${m['servidor']}',
        _int(m['tentativas']),
        _str(m['servico']),
        _str(m['motivo']),
        _str(m['explicacao']),
      );
}

class AppProblematico {
  final String pacote;
  final String nome;
  final String motivo;
  final String explicacao;
  const AppProblematico(this.pacote, this.nome, this.motivo, this.explicacao);
  factory AppProblematico.deMapa(Map<dynamic, dynamic> m) =>
      AppProblematico('${m['pacote']}', '${m['nome']}', '${m['motivo']}', '${m['explicacao']}');
}

enum Gravidade { ok, dica, atencao, grave }

class ItemDiagnostico {
  final String id;
  final String titulo;
  final String situacao;
  final Gravidade gravidade;
  final String acao;
  final String botao;
  const ItemDiagnostico(this.id, this.titulo, this.situacao, this.gravidade, this.acao, this.botao);
  factory ItemDiagnostico.deMapa(Map<dynamic, dynamic> m) => ItemDiagnostico(
        '${m['id']}',
        '${m['titulo']}',
        '${m['situacao']}',
        switch (m['gravidade']) {
          'GRAVE' => Gravidade.grave,
          'ATENCAO' => Gravidade.atencao,
          'DICA' => Gravidade.dica,
          _ => Gravidade.ok,
        },
        '${m['acao'] ?? 'NENHUMA'}',
        '${m['botao'] ?? ''}',
      );
}

class InfoMotor {
  final String versaoApp;
  final String versaoTor;
  final String abi;
  final String android;
  final String fabricante;
  final String modelo;
  final bool torDisponivel;
  final bool obfs4Disponivel;
  final bool snowflakeDisponivel;
  final int obfs4Embutidas;
  final String origemEmbutidas;
  const InfoMotor({
    this.versaoApp = '',
    this.versaoTor = '',
    this.abi = '',
    this.android = '',
    this.fabricante = '',
    this.modelo = '',
    this.torDisponivel = false,
    this.obfs4Disponivel = false,
    this.snowflakeDisponivel = false,
    this.obfs4Embutidas = 0,
    this.origemEmbutidas = '',
  });
  factory InfoMotor.deMapa(Map<dynamic, dynamic> m) => InfoMotor(
        versaoApp: '${m['versaoApp'] ?? ''}',
        versaoTor: '${m['versaoTor'] ?? ''}',
        abi: '${m['abi'] ?? ''}',
        android: '${m['android'] ?? ''}',
        fabricante: '${m['fabricante'] ?? ''}',
        modelo: '${m['modelo'] ?? ''}',
        torDisponivel: _bool(m['torDisponivel']),
        obfs4Disponivel: _bool(m['obfs4Disponivel']),
        snowflakeDisponivel: _bool(m['snowflakeDisponivel']),
        obfs4Embutidas: _int(m['obfs4Embutidas']),
        origemEmbutidas: '${m['origemEmbutidas'] ?? ''}',
      );
}

class AvisosRapidos {
  final bool bateriaIsenta;
  final String dnsPrivado; // DESLIGADO, AUTOMATICO ou FIXO:servidor
  final bool notificacoes;
  final bool vpnAutorizada;
  const AvisosRapidos({
    this.bateriaIsenta = true,
    this.dnsPrivado = 'DESLIGADO',
    this.notificacoes = true,
    this.vpnAutorizada = true,
  });
  factory AvisosRapidos.deMapa(Map<dynamic, dynamic> m) => AvisosRapidos(
        bateriaIsenta: _bool(m['bateriaIsenta'], true),
        dnsPrivado: '${m['dnsPrivado'] ?? 'DESLIGADO'}',
        notificacoes: _bool(m['notificacoes'], true),
        vpnAutorizada: _bool(m['vpnAutorizada'], true),
      );
}
