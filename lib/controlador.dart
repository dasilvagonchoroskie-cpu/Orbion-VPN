import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'motor.dart';
import 'util.dart';

/// Estado da tela: o que o motor diz agora + as configurações salvas.
/// As telas escutam este objeto e se redesenham quando ele muda.
class ControladorOrbion extends ChangeNotifier {
  StatusVpn status = const StatusVpn();
  Config config = const Config();
  AvisosRapidos avisos = const AvisosRapidos();
  bool carregado = false;

  /// Conta as vezes que o app voltou para a frente (ex.: depois de uma tela
  /// de configuração do Android). As telas usam isso para se reverificar.
  int retornos = 0;

  /// Mensagem curta para o Painel mostrar uma vez (ex.: religamento).
  String? avisoDaTela;

  Timer? _relogio;
  int _voltas = 0;
  bool _ocupado = false;
  DateTime _ultimoReligamento = DateTime.fromMillisecondsSinceEpoch(0);
  DateTime _ultimoDesligarPorVoce = DateTime.fromMillisecondsSinceEpoch(0);

  Future<void> iniciar() async {
    try {
      config = await Motor.lerConfig();
      status = await Motor.status();
      avisos = await Motor.avisosRapidos();
    } catch (e) {
      debugPrint('Falha ao ler o motor: $e');
    }
    carregado = true;
    notifyListeners();

    // Android 13+: pede a permissão de notificação na primeira abertura.
    if (!avisos.notificacoes) {
      unawaited(Motor.executarAcao('PEDIR_NOTIFICACAO_INICIO'));
    }
    if (!await _talvezReligar() && config.conectarAoAbrir && status.estado == EstadoVpn.desconectado) {
      unawaited(conectar());
    }
    _relogio = Timer.periodic(const Duration(seconds: 1), (_) => _atualizar());
  }

  /// A VPN estava ligada e o Android derrubou o motor: religa sem a pessoa
  /// precisar tocar em nada.
  Future<bool> _talvezReligar() async {
    if (!status.derrubadaPeloSistema || !avisos.vpnAutorizada) return false;
    final agora = DateTime.now();
    if (agora.difference(_ultimoReligamento).inSeconds < 30) return false;
    if (agora.difference(_ultimoDesligarPorVoce).inSeconds < 15) return false;
    _ultimoReligamento = agora;
    avisoDaTela = 'A VPN tinha sido fechada pelo sistema do celular. Religando...';
    notifyListeners();
    await conectar(origem: 'reabertura');
    return true;
  }

  Future<void> _atualizar() async {
    if (_ocupado) return;
    _ocupado = true;
    try {
      status = await Motor.status();
      _voltas++;
      if (_voltas % 5 == 0) avisos = await Motor.avisosRapidos();
      notifyListeners();
      if (_voltas % 3 == 0) await _talvezReligar();
    } catch (_) {
      // Ignora: tenta de novo no próximo segundo.
    } finally {
      _ocupado = false;
    }
  }

  /// Chamado quando o app volta para a frente (ex.: depois de abrir uma
  /// tela de configuração do Android).
  Future<void> voltouParaFrente() async {
    retornos++;
    try {
      status = await Motor.status();
      avisos = await Motor.avisosRapidos();
    } catch (_) {}
    notifyListeners();
    await _talvezReligar();
  }

  /// Abre a tela do Android pedida e devolve o que dizer à pessoa (ou null).
  Future<String?> acao(String acao) async {
    try {
      final codigo = await Motor.executarAcao(acao);
      return mensagemDaAcao(codigo);
    } on PlatformException catch (e) {
      return e.message;
    }
  }

  /// "Já fiz": a pessoa confirma um ajuste que o app não consegue conferir.
  Future<void> confirmar(String id) async {
    config = await Motor.confirmar(id);
    try {
      avisos = await Motor.avisosRapidos();
    } catch (_) {}
    notifyListeners();
  }

  Future<void> desfazerConfirmacoes() async {
    config = await Motor.desfazerConfirmacoes();
    try {
      avisos = await Motor.avisosRapidos();
    } catch (_) {}
    notifyListeners();
  }

  /// Retorna null se deu certo, ou o motivo de não ter ligado.
  Future<String?> conectar({String origem = 'tela'}) async {
    try {
      final ok = await Motor.conectar(origem: origem);
      await _atualizar();
      return ok ? null : 'Sem a permissão de VPN o Orbion não consegue proteger o tráfego.';
    } on PlatformException catch (e) {
      return e.message ?? 'Não foi possível conectar.';
    }
  }

  Future<void> desconectar() async {
    _ultimoDesligarPorVoce = DateTime.now();
    avisoDaTela = null;
    try {
      await Motor.desconectar();
    } catch (_) {}
    await _atualizar();
  }

  Future<String?> reconectar() async {
    try {
      final ok = await Motor.reconectar();
      await _atualizar();
      return ok ? null : 'Sem a permissão de VPN o Orbion não consegue proteger o tráfego.';
    } on PlatformException catch (e) {
      return e.message ?? 'Não foi possível reconectar.';
    }
  }

  Future<void> salvar(Map<String, Object?> mudancas) async {
    config = await Motor.salvarConfig(mudancas);
    notifyListeners();
  }

  Future<void> rotaDeDominio(String dominio, String rota) async {
    config = await Motor.rotaDeDominio(dominio, rota);
    notifyListeners();
  }

  Future<void> dispensarAviso(String id) async {
    await salvar({
      'avisosDispensados': {...config.avisosDispensados, id}.toList(),
    });
  }

  bool dispensado(String id) => config.avisosDispensados.contains(id);

  void limparAvisoDaTela() {
    avisoDaTela = null;
    notifyListeners();
  }

  @override
  void dispose() {
    _relogio?.cancel();
    super.dispose();
  }
}
