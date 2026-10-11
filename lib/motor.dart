import 'package:flutter/services.dart';

/// Ponte com o motor nativo pelo canal "orbion/motor".
///
/// O motor — Tor, túnel, filtro, Kill Switch, rota inteligente — roda num
/// processo separado do Android (":vpn"). Se esta tela fechar ou travar, a
/// VPN continua. Toda a interface é Flutter.
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

  static Map<dynamic, dynamic> _mapa(Object? x) => x is Map ? x : const {};
  static List<dynamic> _lista(Object? x) => x is List ? x : const [];

  static Future<StatusVpn> status() async =>
      StatusVpn.deMapa(_mapa(await _chamar<Object>('status').timeout(const Duration(seconds: 6))));

  /// Pede a permissão de VPN (se preciso) e liga. Retorna false se a pessoa negou.
  static Future<bool> conectar({String origem = 'tela'}) async =>
      (await _chamar<bool>('conectar', {'origem': origem})) ?? false;
  static Future<void> desconectar() => _chamar('desligar');
  static Future<bool> reconectar() async => (await _chamar<bool>('reconectar')) ?? false;
  static Future<void> novaIdentidade() => _chamar('novaIdentidade');
  static Future<void> limparAvisos() => _chamar('limparAvisos');

  static Future<Config> lerConfig() async => Config.deMapa(_mapa(await _chamar<Object>('lerConfig')));

  static Future<Config> salvarConfig(Map<String, Object?> mudancas) async =>
      Config.deMapa(_mapa(await _chamar<Object>('salvarConfig', mudancas)));

  static Future<List<AppRota>> listarApps({bool incluirSistema = false}) async =>
      _lista(await _chamar<Object>('listarApps', {'incluirSistema': incluirSistema}))
          .map((e) => AppRota.deMapa(_mapa(e)))
          .toList();

  static Future<Map<String, Uint8List>> icones(List<String> pacotes) async {
    final m = _mapa(await _chamar<Object>('icones', {'pacotes': pacotes}));
    final saida = <String, Uint8List>{};
    m.forEach((k, v) {
      if (v is Uint8List) saida[k as String] = v;
    });
    return saida;
  }

  static Future<Registros> registros({int max = 200}) async =>
      Registros.deMapa(_mapa(await _chamar<Object>('registros', {'max': max})));

  static Future<LogTecnico> logTecnico({int max = 300}) async {
    final m = _mapa(await _chamar<Object>('logTecnico', {'max': max}));
    List<String> lista(Object? x) => _lista(x).map((e) => '$e').toList();
    return LogTecnico(tor: lista(m['tor']), tunel: lista(m['tunel']));
  }

  static Future<List<ItemDiagnostico>> diagnostico() async =>
      _lista(await _chamar<Object>('diagnostico')).map((e) => ItemDiagnostico.deMapa(_mapa(e))).toList();

  static Future<InfoMotor> infoMotor() async => InfoMotor.deMapa(_mapa(await _chamar<Object>('infoMotor')));

  static Future<AvisosRapidos> avisosRapidos() async =>
      AvisosRapidos.deMapa(_mapa(await _chamar<Object>('avisosRapidos').timeout(const Duration(seconds: 10))));

  static Future<ResumoRotas> rotas() async => ResumoRotas.deMapa(_mapa(await _chamar<Object>('rotas')));

  /// Manda um app pela rede Tor (true) ou por fora dela (false). Devolve a
  /// rota que ficou valendo e a configuração já atualizada.
  static Future<({RotaApp rota, Config config})> rotaDeApp(String pacote, bool pelaTor) async {
    final m = _mapa(await _chamar<Object>('rotaDeApp', {'pacote': pacote, 'pelaTor': pelaTor}));
    return (rota: rotaAppDeTexto(m['rota']), config: Config.deMapa(_mapa(m['config'])));
  }

  /// rota: 'direto' (sempre por fora), 'tor' (sempre pelo Tor) ou 'padrao'.
  static Future<Config> rotaDeDominio(String dominio, String rota) async =>
      Config.deMapa(_mapa(await _chamar<Object>('rotaDeDominio', {'dominio': dominio, 'rota': rota})));

  static Future<List<Evento>> eventos({int max = 150}) async =>
      _lista(await _chamar<Object>('eventos', {'max': max})).map((e) => Evento.deMapa(_mapa(e))).toList();

  /// Monta o relatório para o suporte e abre o menu Compartilhar do Android.
  static Future<void> compartilharRelatorio() => _chamar('compartilharRelatorio');

  static Future<List<Fechamento>> fechamentos() async =>
      _lista(await _chamar<Object>('fechamentos')).map((e) => Fechamento.deMapa(_mapa(e))).toList();

  /// Abre a tela do Android pedida. Devolve um código (OK, FALHOU, MARCA...).
  static Future<String> executarAcao(String acao) async =>
      (await _chamar<String>('executarAcao', {'acao': acao})) ?? 'FALHOU';

  /// A pessoa confirma um ajuste que o app não consegue conferir sozinho.
  static Future<Config> confirmar(String id) async => Config.deMapa(_mapa(await _chamar<Object>('confirmar', {'id': id})));

  /// Volta a mostrar os avisos que a pessoa marcou como feitos.
  static Future<Config> desfazerConfirmacoes() async =>
      Config.deMapa(_mapa(await _chamar<Object>('desfazerConfirmacoes')));
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
List<String> _textos(Object? v) => (v is List ? v : const []).map((e) => '$e').toList();

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
  final String? religamento;
  final String caminhoEmUso;
  final List<String> appsForaNaSessao;
  final bool aplicandoRotas;
  final bool servicoAtivo;
  final bool semInternet;
  final bool estavaLigada;
  final bool vpnSempreAtiva;
  final bool bloqueioSemVpn;
  final int quedasDoTor;
  final int desviadosAuto;
  final int pausados;

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
    this.religamento,
    this.caminhoEmUso = 'DIRETO',
    this.appsForaNaSessao = const [],
    this.aplicandoRotas = false,
    this.servicoAtivo = false,
    this.semInternet = false,
    this.estavaLigada = false,
    this.vpnSempreAtiva = false,
    this.bloqueioSemVpn = false,
    this.quedasDoTor = 0,
    this.desviadosAuto = 0,
    this.pausados = 0,
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
        religamento: _str(m['religamento']),
        caminhoEmUso: (m['caminhoEmUso'] as String?) ?? 'DIRETO',
        appsForaNaSessao: _textos(m['appsForaNaSessao']),
        aplicandoRotas: _bool(m['aplicandoRotas']),
        servicoAtivo: _bool(m['servicoAtivo']),
        semInternet: _bool(m['semInternet']),
        estavaLigada: _bool(m['estavaLigada']),
        vpnSempreAtiva: _bool(m['vpnSempreAtiva']),
        bloqueioSemVpn: _bool(m['bloqueioSemVpn']),
        quedasDoTor: _int(m['quedasDoTor']),
        desviadosAuto: _int(m['desviadosAuto']),
        pausados: _int(m['pausados']),
      );

  bool get ligada =>
      estado == EstadoVpn.conectado || estado == EstadoVpn.conectando || estado == EstadoVpn.bloqueado;

  /// A pessoa deixou a VPN ligada, mas o Android derrubou o motor.
  bool get derrubadaPeloSistema => estavaLigada && !servicoAtivo && estado == EstadoVpn.desconectado;
}

class Config {
  final bool killSwitch;
  final bool bloquearRastreadores;
  final bool capturarIPv6;
  final int intervaloRotacaoSegundos;
  final bool rotaInteligente;
  final Set<String> categoriasDesligadas;
  final bool desvioAutomatico;
  final Set<String> appsForaDoTunel;
  final Set<String> appsSempreNoTor;
  final List<String> dominiosDiretos;
  final List<String> dominiosSempreTor;
  final bool conectarAoAbrir;
  final bool conectarNoBoot;
  final bool usarBridges;
  final String bridges;
  final bool contornoAutomatico;
  final Set<String> avisosDispensados;

  const Config({
    this.killSwitch = true,
    this.bloquearRastreadores = true,
    this.capturarIPv6 = true,
    this.intervaloRotacaoSegundos = 600,
    this.rotaInteligente = true,
    this.categoriasDesligadas = const {},
    this.desvioAutomatico = true,
    this.appsForaDoTunel = const {},
    this.appsSempreNoTor = const {},
    this.dominiosDiretos = const [],
    this.dominiosSempreTor = const [],
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
        capturarIPv6: _bool(m['capturarIPv6'], true),
        intervaloRotacaoSegundos: m['intervaloRotacaoSegundos'] is num ? _int(m['intervaloRotacaoSegundos']) : 600,
        rotaInteligente: _bool(m['rotaInteligente'], true),
        categoriasDesligadas: _textos(m['categoriasDesligadas']).toSet(),
        desvioAutomatico: _bool(m['desvioAutomatico'], true),
        appsForaDoTunel: _textos(m['appsForaDoTunel']).toSet(),
        appsSempreNoTor: _textos(m['appsSempreNoTor']).toSet(),
        dominiosDiretos: _textos(m['dominiosDiretos']),
        dominiosSempreTor: _textos(m['dominiosSempreTor']),
        conectarAoAbrir: _bool(m['conectarAoAbrir']),
        conectarNoBoot: _bool(m['conectarNoBoot']),
        usarBridges: _bool(m['usarBridges']),
        bridges: (m['bridges'] as String?) ?? '',
        contornoAutomatico: _bool(m['contornoAutomatico'], true),
        avisosDispensados: _textos(m['avisosDispensados']).toSet(),
      );
}

/// Rota de um aplicativo: pela rede Tor ou por fora dela.
enum RotaApp { tor, foraAutomatico, foraManual, torManual }

RotaApp rotaAppDeTexto(Object? s) => switch (s) {
      'FORA_AUTO' => RotaApp.foraAutomatico,
      'FORA_MANUAL' => RotaApp.foraManual,
      'TOR_MANUAL' => RotaApp.torManual,
      _ => RotaApp.tor,
    };

class AppRota {
  final String pacote;
  final String nome;
  final bool sistema;
  final RotaApp rota;
  final String? categoria;

  /// Código da categoria da rota inteligente (bancos, mensageiros...).
  final String? categoriaId;
  const AppRota(this.pacote, this.nome, this.sistema, this.rota, this.categoria, {this.categoriaId});
  factory AppRota.deMapa(Map<dynamic, dynamic> m) => AppRota(
        '${m['pacote']}',
        '${m['nome']}',
        _bool(m['sistema']),
        rotaAppDeTexto(m['rota']),
        _str(m['categoria']),
        categoriaId: _str(m['categoriaId']),
      );

  bool get pelaTor => rota == RotaApp.tor || rota == RotaApp.torManual;

  /// Escolha manual (a pessoa mudou a rota que o automático daria).
  bool get manual => rota == RotaApp.torManual || rota == RotaApp.foraManual;

  AppRota comRota(RotaApp r) => AppRota(pacote, nome, sistema, r, categoria, categoriaId: categoriaId);
}

enum Destino { tor, direto, bloqueado, pausado }

class Registro {
  final String dominio;
  final Destino destino;
  final int quando;
  final String? motivo;
  final String? base;
  const Registro(this.dominio, this.destino, this.quando, this.motivo, this.base);
  factory Registro.deMapa(Map<dynamic, dynamic> m) {
    final d = switch (m['destino']) {
      'DIRETO' => Destino.direto,
      'BLOQUEADO' => Destino.bloqueado,
      'PAUSADO' => Destino.pausado,
      _ => Destino.tor,
    };
    return Registro('${m['dominio']}', d, _int(m['quando']), _str(m['motivo']), _str(m['base']));
  }
}

class Registros {
  final int bloqueados;
  final int pelaRedeTor;
  final int diretos;
  final int pausados;
  final int ipv6Recusados;
  final List<Registro> lista;
  const Registros({
    this.bloqueados = 0,
    this.pelaRedeTor = 0,
    this.diretos = 0,
    this.pausados = 0,
    this.ipv6Recusados = 0,
    this.lista = const [],
  });
  factory Registros.deMapa(Map<dynamic, dynamic> m) {
    final c = (m['contagem'] as Map<dynamic, dynamic>?) ?? const {};
    return Registros(
      bloqueados: _int(c['bloqueados']),
      pelaRedeTor: _int(c['pelaRedeTor']),
      diretos: _int(c['diretos']),
      pausados: _int(c['pausados']),
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

class CategoriaRota {
  final String id;
  final String titulo;
  final String resumo;
  final String porque;
  final bool ligada;
  final List<String> apps;
  final int dominios;
  const CategoriaRota(this.id, this.titulo, this.resumo, this.porque, this.ligada, this.apps, this.dominios);
  factory CategoriaRota.deMapa(Map<dynamic, dynamic> m) => CategoriaRota(
        '${m['id']}',
        '${m['titulo']}',
        '${m['resumo'] ?? ''}',
        '${m['porque'] ?? ''}',
        _bool(m['ligada'], true),
        _textos(m['apps']),
        _int(m['dominios']),
      );
}

class DesvioAutomatico {
  final String dominio;
  final int quando;
  final String? categoria;
  const DesvioAutomatico(this.dominio, this.quando, this.categoria);
  factory DesvioAutomatico.deMapa(Map<dynamic, dynamic> m) =>
      DesvioAutomatico('${m['dominio']}', _int(m['quando']), _str(m['categoria']));
}

class DestinoPausado {
  final String destino;
  final int falhas;
  final int segundos;
  const DestinoPausado(this.destino, this.falhas, this.segundos);
  factory DestinoPausado.deMapa(Map<dynamic, dynamic> m) =>
      DestinoPausado('${m['destino']}', _int(m['falhas']), _int(m['segundos']));
}

class ResumoRotas {
  final List<CategoriaRota> categorias;
  final List<DesvioAutomatico> desviadosAuto;
  final List<DestinoPausado> pausados;
  final List<String> dominiosDiretos;
  final List<String> dominiosSempreTor;
  final int appsFora;
  const ResumoRotas({
    this.categorias = const [],
    this.desviadosAuto = const [],
    this.pausados = const [],
    this.dominiosDiretos = const [],
    this.dominiosSempreTor = const [],
    this.appsFora = 0,
  });
  factory ResumoRotas.deMapa(Map<dynamic, dynamic> m) => ResumoRotas(
        categorias: (m['categorias'] as List<dynamic>? ?? const []).map((e) => CategoriaRota.deMapa(e as Map)).toList(),
        desviadosAuto:
            (m['desviadosAuto'] as List<dynamic>? ?? const []).map((e) => DesvioAutomatico.deMapa(e as Map)).toList(),
        pausados: (m['pausados'] as List<dynamic>? ?? const []).map((e) => DestinoPausado.deMapa(e as Map)).toList(),
        dominiosDiretos: _textos(m['dominiosDiretos']),
        dominiosSempreTor: _textos(m['dominiosSempreTor']),
        appsFora: _int(m['appsFora']),
      );
}

class Evento {
  final int quando;
  final String texto;
  const Evento(this.quando, this.texto);
  factory Evento.deMapa(Map<dynamic, dynamic> m) => Evento(_int(m['quando']), '${m['texto']}');
}

/// Por que o Android fechou o Orbion (Android 11+).
class Fechamento {
  final String processo; // 'vpn' ou 'tela'
  final int quando;
  final String explicacao;
  final String descricao;
  final String? rastro;

  /// Aconteceu antes da instalação desta versão do Orbion.
  final bool anterior;
  const Fechamento(this.processo, this.quando, this.explicacao, this.descricao, this.rastro, {this.anterior = false});
  factory Fechamento.deMapa(Map<dynamic, dynamic> m) => Fechamento(
        '${m['processo']}',
        _int(m['quando']),
        '${m['explicacao'] ?? ''}',
        '${m['descricao'] ?? ''}',
        _str(m['rastro']),
        anterior: _bool(m['anterior']),
      );
  bool get daVpn => processo == 'vpn';
}

enum Gravidade { ok, dica, atencao, grave }

class ItemDiagnostico {
  final String id;
  final String titulo;
  final String situacao;
  final Gravidade gravidade;
  final String acao;
  final String botao;

  /// Texto do botão "Já fiz" — vazio quando o app confere sozinho.
  final String dispensar;
  final String acao2;
  final String botao2;

  const ItemDiagnostico(
    this.id,
    this.titulo,
    this.situacao,
    this.gravidade,
    this.acao,
    this.botao, {
    this.dispensar = '',
    this.acao2 = 'NENHUMA',
    this.botao2 = '',
  });

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
        dispensar: '${m['dispensar'] ?? ''}',
        acao2: '${m['acao2'] ?? 'NENHUMA'}',
        botao2: '${m['botao2'] ?? ''}',
      );

  bool get pendente => gravidade != Gravidade.ok;
  bool get confirmadoPelaPessoa => gravidade == Gravidade.ok && situacao.startsWith('Feito — confirmado');
}

class InfoMotor {
  final String versaoApp;
  final String versaoTor;
  final String abi;
  final String android;
  final String fabricante;
  final String modelo;
  final String marca;
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
    this.marca = '',
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
        marca: '${m['marca'] ?? ''}',
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
  final String marca;

  /// "VPN sempre ativa": true/false, ou null quando não dá para saber.
  final bool? sempreAtiva;
  const AvisosRapidos({
    this.bateriaIsenta = true,
    this.dnsPrivado = 'DESLIGADO',
    this.notificacoes = true,
    this.vpnAutorizada = true,
    this.marca = '',
    this.sempreAtiva,
  });
  factory AvisosRapidos.deMapa(Map<dynamic, dynamic> m) => AvisosRapidos(
        bateriaIsenta: _bool(m['bateriaIsenta'], true),
        dnsPrivado: '${m['dnsPrivado'] ?? 'DESLIGADO'}',
        notificacoes: _bool(m['notificacoes'], true),
        vpnAutorizada: _bool(m['vpnAutorizada'], true),
        marca: '${m['marca'] ?? ''}',
        sempreAtiva: m['sempreAtiva'] is bool ? m['sempreAtiva'] as bool : null,
      );
}
