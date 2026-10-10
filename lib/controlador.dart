import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'motor.dart';

/// Estado da tela: o que o motor diz agora + as configurações salvas.
/// As telas escutam este objeto e se redesenham quando ele muda.
class ControladorOrbion extends ChangeNotifier {
  StatusVpn status = const StatusVpn();
  Config config = const Config();
  AvisosRapidos avisos = const AvisosRapidos();
  bool carregado = false;

  Timer? _relogio;
  int _voltas = 0;
  bool _ocupado = false;

  Future<void> iniciar() async {
    try {
      config = await Motor.lerConfig();
      avisos = await Motor.avisosRapidos();
      status = await Motor.status();
    } on PlatformException catch (e) {
      debugPrint('Falha ao ler o motor: ${e.message}');
    }
    carregado = true;
    notifyListeners();

    // Android 13+: pede a permissão de notificação na primeira abertura.
    if (!avisos.notificacoes) {
      unawaited(Motor.executarAcao('PEDIR_NOTIFICACAO_INICIO'));
    }
    if (config.conectarAoAbrir && status.estado == EstadoVpn.desconectado) {
      unawaited(conectar());
    }
    _relogio = Timer.periodic(const Duration(seconds: 1), (_) => _atualizar());
  }

  Future<void> _atualizar() async {
    if (_ocupado) return;
    _ocupado = true;
    try {
      status = await Motor.status();
      _voltas++;
      if (_voltas % 5 == 0) avisos = await Motor.avisosRapidos();
      notifyListeners();
    } catch (_) {
      // Ignora: tenta de novo no próximo segundo.
    } finally {
      _ocupado = false;
    }
  }

  /// Chamado quando o app volta para a frente (ex.: depois de abrir uma
  /// tela de configuração do Android).
  Future<void> voltouParaFrente() async {
    try {
      avisos = await Motor.avisosRapidos();
      status = await Motor.status();
      notifyListeners();
    } catch (_) {}
  }

  /// Retorna null se deu certo, ou o motivo de não ter ligado.
  Future<String?> conectar() async {
    try {
      final ok = await Motor.conectar();
      await _atualizar();
      return ok ? null : 'Sem a permissão de VPN o Orbion não consegue proteger o tráfego.';
    } on PlatformException catch (e) {
      return e.message ?? 'Não foi possível conectar.';
    }
  }

  Future<void> desconectar() async {
    await Motor.desconectar();
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

  Future<void> dispensarAviso(String id) async {
    await salvar({
      'avisosDispensados': {...config.avisosDispensados, id}.toList(),
    });
  }

  bool dispensado(String id) => config.avisosDispensados.contains(id);

  @override
  void dispose() {
    _relogio?.cancel();
    super.dispose();
  }
}
