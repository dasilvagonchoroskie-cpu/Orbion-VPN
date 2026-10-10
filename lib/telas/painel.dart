import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../controlador.dart';
import '../motor.dart';
import '../tema.dart';
import '../util.dart';
import '../widgets/comuns.dart';

class TelaPainel extends StatefulWidget {
  final ControladorOrbion c;
  final ValueChanged<int> irPara;
  const TelaPainel({super.key, required this.c, required this.irPara});

  @override
  State<TelaPainel> createState() => _TelaPainelState();
}

class _TelaPainelState extends State<TelaPainel> with SingleTickerProviderStateMixin {
  late final AnimationController _pulso =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 1600))..repeat();
  bool _agindo = false;
  String? _ultimoAvisoIdentidade;

  ControladorOrbion get c => widget.c;

  @override
  void dispose() {
    _pulso.dispose();
    super.dispose();
  }

  Color _corDoEstado(EstadoVpn e) => switch (e) {
        EstadoVpn.conectado => Cores.verde,
        EstadoVpn.conectando => Cores.ciano,
        EstadoVpn.bloqueado => Cores.amarelo,
        EstadoVpn.erro => Cores.vermelho,
        EstadoVpn.desconectado => Cores.textoFraco,
      };

  String _tituloDoEstado(EstadoVpn e) => switch (e) {
        EstadoVpn.conectado => 'CONECTADO',
        EstadoVpn.conectando => 'CONECTANDO',
        EstadoVpn.bloqueado => 'TRÁFEGO BLOQUEADO',
        EstadoVpn.erro => 'FALHA NA CONEXÃO',
        EstadoVpn.desconectado => 'DESCONECTADO',
      };

  Future<void> _botaoPrincipal(StatusVpn s) async {
    if (_agindo) return;
    setState(() => _agindo = true);
    try {
      String? erro;
      if (s.estado == EstadoVpn.conectado || s.estado == EstadoVpn.conectando) {
        await c.desconectar();
      } else if (s.estado == EstadoVpn.bloqueado) {
        erro = await c.reconectar();
      } else {
        erro = await c.conectar();
      }
      if (erro != null && mounted) mostrarAviso(context, erro);
    } finally {
      if (mounted) setState(() => _agindo = false);
    }
  }

  String _textoDoBotao(EstadoVpn e) => switch (e) {
        EstadoVpn.conectado => 'DESCONECTAR',
        EstadoVpn.conectando => 'CANCELAR',
        EstadoVpn.bloqueado => 'RECONECTAR AGORA',
        EstadoVpn.erro => 'TENTAR DE NOVO',
        EstadoVpn.desconectado => 'CONECTAR',
      };

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: c,
      builder: (context, _) {
        final s = c.status;
        _mostrarAvisoDeIdentidade(s);
        final cor = _corDoEstado(s.estado);
        return ListView(
          padding: const EdgeInsets.fromLTRB(18, 12, 18, 28),
          children: [
            _cabecalho(),
            const SizedBox(height: 18),
            Center(child: _anel(s, cor)),
            const SizedBox(height: 18),
            Center(
              child: Text(
                _tituloDoEstado(s.estado),
                style: TextStyle(color: cor, fontSize: 22, fontWeight: FontWeight.w900, letterSpacing: 2),
              ),
            ),
            const SizedBox(height: 6),
            Center(
              child: Text(
                _subtitulo(s),
                textAlign: TextAlign.center,
                style: const TextStyle(color: Cores.textoFraco, fontSize: 14, height: 1.35),
              ),
            ),
            const SizedBox(height: 22),
            SizedBox(
              height: 56,
              child: FilledButton(
                style: FilledButton.styleFrom(
                  backgroundColor: s.estado == EstadoVpn.conectado || s.estado == EstadoVpn.conectando
                      ? Cores.cartao
                      : Cores.ciano,
                  foregroundColor: s.estado == EstadoVpn.conectado || s.estado == EstadoVpn.conectando
                      ? Cores.ciano
                      : const Color(0xFF00222A),
                  side: const BorderSide(color: Cores.ciano, width: 1.4),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                ),
                onPressed: _agindo ? null : () => _botaoPrincipal(s),
                child: Text(
                  _textoDoBotao(s.estado),
                  style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w900, letterSpacing: 1.5),
                ),
              ),
            ),
            if (s.estado == EstadoVpn.conectado) ...[
              const SizedBox(height: 18),
              _trafego(s),
              const SizedBox(height: 12),
              OutlinedButton.icon(
                style: OutlinedButton.styleFrom(
                  foregroundColor: Cores.magenta,
                  side: const BorderSide(color: Cores.magenta),
                  minimumSize: const Size.fromHeight(48),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                ),
                onPressed: () async {
                  await Motor.novaIdentidade();
                  if (context.mounted) mostrarAviso(context, 'Pedindo uma nova identidade ao Tor...');
                },
                icon: const Icon(Icons.autorenew),
                label: const Text('Nova identidade  •  troca o IP de saída'),
              ),
            ],
            const SizedBox(height: 18),
            ..._avisos(s),
          ],
        );
      },
    );
  }

  Future<void> _executar(String acao) async {
    final msg = await c.acao(acao);
    if (msg != null && mounted) mostrarAviso(context, msg);
  }

  void _mostrarAvisoDeIdentidade(StatusVpn s) {
    final a = s.avisoDeIdentidade;
    if (a == null || a == _ultimoAvisoIdentidade) return;
    _ultimoAvisoIdentidade = a;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) mostrarAviso(context, a);
      Motor.limparAvisos();
    });
  }

  Widget _cabecalho() => Row(
        children: [
          const Icon(Icons.shield, color: Cores.ciano, size: 26),
          const SizedBox(width: 10),
          const Text('ORBION', style: TextStyle(fontSize: 22, fontWeight: FontWeight.w900, letterSpacing: 4)),
          const SizedBox(width: 6),
          const Text('VPN', style: TextStyle(fontSize: 22, fontWeight: FontWeight.w300, color: Cores.ciano, letterSpacing: 4)),
          const Spacer(),
          Selo('TOR', Cores.roxo),
        ],
      );

  String _subtitulo(StatusVpn s) {
    switch (s.estado) {
      case EstadoVpn.conectado:
        final desde = s.conectadoDesde > 0
            ? formatarDuracao(DateTime.now().difference(DateTime.fromMillisecondsSinceEpoch(s.conectadoDesde)))
            : '—';
        return 'Conectado há $desde  •  ${nomeDoCaminho(s.caminhoEmUso)}\nTráfego TCP e DNS pela rede Tor';
      case EstadoVpn.conectando:
        return s.mensagem.isEmpty ? 'Iniciando...' : s.mensagem;
      case EstadoVpn.bloqueado:
        return 'A rede Tor caiu. Reconectando sozinho...';
      case EstadoVpn.erro:
        return 'Toque em tentar de novo. O motivo está logo abaixo.';
      case EstadoVpn.desconectado:
        return 'Seu tráfego NÃO está protegido.';
    }
  }

  Widget _anel(StatusVpn s, Color cor) {
    return SizedBox(
      width: 210,
      height: 210,
      child: AnimatedBuilder(
        animation: _pulso,
        builder: (context, _) => CustomPaint(
          painter: _PintorDoAnel(
            cor: cor,
            progresso: s.estado == EstadoVpn.conectando ? s.percentual / 100.0 : (s.estado == EstadoVpn.conectado ? 1.0 : 0.0),
            fase: _pulso.value,
            animado: s.estado == EstadoVpn.conectando || s.estado == EstadoVpn.bloqueado,
          ),
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  switch (s.estado) {
                    EstadoVpn.conectado => Icons.lock,
                    EstadoVpn.conectando => Icons.lock_clock,
                    EstadoVpn.bloqueado => Icons.gpp_maybe,
                    EstadoVpn.erro => Icons.error_outline,
                    EstadoVpn.desconectado => Icons.lock_open,
                  },
                  color: cor,
                  size: 64,
                ),
                if (s.estado == EstadoVpn.conectando)
                  Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: Text('${s.percentual}%', style: TextStyle(color: cor, fontSize: 20, fontWeight: FontWeight.w800)),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _trafego(StatusVpn s) {
    Widget bloco(String rotulo, IconData icone, int total, int vel, Color cor) => Expanded(
          child: Card(
            child: Padding(
              padding: const EdgeInsets.all(14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(children: [
                    Icon(icone, color: cor, size: 18),
                    const SizedBox(width: 6),
                    Text(rotulo, style: const TextStyle(color: Cores.textoFraco, fontSize: 12, fontWeight: FontWeight.w700, letterSpacing: 1)),
                  ]),
                  const SizedBox(height: 8),
                  Text(formatarBytes(total), style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800)),
                  Text('${formatarBytes(vel)}/s', style: TextStyle(color: cor, fontSize: 13)),
                ],
              ),
            ),
          ),
        );
    return Row(
      children: [
        bloco('ENVIADO', Icons.arrow_upward, s.enviadoBytes, s.velocidadeEnvio, Cores.magenta),
        const SizedBox(width: 12),
        bloco('RECEBIDO', Icons.arrow_downward, s.recebidoBytes, s.velocidadeRecebimento, Cores.ciano),
      ],
    );
  }

  List<Widget> _avisos(StatusVpn s) {
    final lista = <Widget>[];
    final a = c.avisos;

    if (s.estado == EstadoVpn.bloqueado) {
      lista.add(CartaoAviso(
        icone: Icons.gpp_maybe,
        cor: Cores.amarelo,
        titulo: '🔒 Kill Switch protegeu você',
        texto: 'O tráfego que dependia da rede Tor está bloqueado de propósito. Sem isso, ele voltaria a sair sem proteção e você não teria como perceber.\n\n'
            'O Orbion está tentando reconectar sozinho.${s.erro != null ? '\n\nMotivo: ${s.erro}' : ''}',
        botao: 'Desligar a VPN e liberar a internet',
        aoTocarBotao: () => c.desconectar(),
      ));
    }
    if (s.estado == EstadoVpn.erro && s.erro != null) {
      lista.add(CartaoAviso(
        icone: Icons.error_outline,
        cor: Cores.vermelho,
        titulo: 'Por que não conectou',
        texto: s.erro!,
        botao: 'Ver registros técnicos',
        aoTocarBotao: () => widget.irPara(2),
      ));
    }
    if (s.contornoDeBloqueio != null && s.estado == EstadoVpn.conectado) {
      lista.add(CartaoAviso(
        icone: Icons.alt_route,
        cor: Cores.roxo,
        titulo: 'Bloqueio contornado',
        texto: s.contornoDeBloqueio!,
        botaoSecundario: 'Entendi',
        aoTocarSecundario: () => Motor.limparAvisos(),
      ));
    }
    if (!a.vpnAutorizada && s.estado == EstadoVpn.desconectado && !c.dispensado('painel:vpnrevogada')) {
      lista.add(CartaoAviso(
        icone: Icons.vpn_key_off,
        cor: Cores.amarelo,
        titulo: 'Permissão de VPN não concedida',
        texto: 'Ao tocar em Conectar o Android vai perguntar se o Orbion pode criar uma VPN. Toque em OK.\n\n'
            'Se outra VPN estiver ligada no aparelho, ela será desligada — o Android só permite uma por vez.',
        botaoSecundario: 'Entendi',
        aoTocarSecundario: () => c.dispensarAviso('painel:vpnrevogada'),
      ));
    }
    if (!a.bateriaIsenta && !c.dispensado('painel:bateria')) {
      lista.add(CartaoAviso(
        icone: Icons.battery_alert,
        cor: Cores.vermelho,
        titulo: '⚠ Ação necessária: economia de bateria',
        texto: 'O seu aparelho está autorizado a encerrar o Orbion para poupar bateria. Quando isso acontece a VPN cai e o celular pode ficar sem internet, mostrando o Wi-Fi com um ponto de exclamação.\n\n'
            'Toque abaixo e escolha "Permitir" para o Orbion continuar funcionando em segundo plano.',
        botao: 'Corrigir agora',
        aoTocarBotao: () => _executar('ISENTAR_BATERIA'),
        botaoSecundario: 'Não avisar',
        aoTocarSecundario: () => c.dispensarAviso('painel:bateria'),
      ));
    }
    if (s.estado == EstadoVpn.conectado && c.avisos.sempreAtiva == false && !c.dispensado('painel:sempreativa')) {
      lista.add(CartaoAviso(
        icone: Icons.all_inclusive,
        cor: Cores.ciano,
        titulo: 'Deixe o Android religar o Orbion sozinho',
        texto: 'Marque o Orbion como "VPN sempre ativa": se o sistema fechar o app por qualquer motivo, é o próprio Android que liga a VPN de volta.\n\n'
            'Toque abaixo, abra a engrenagem ao lado de Orbion VPN e ligue "VPN sempre ativa".',
        botao: 'Abrir configurações de VPN',
        aoTocarBotao: () => _executar('ABRIR_VPN'),
        botaoSecundario: 'Não avisar',
        aoTocarSecundario: () => c.dispensarAviso('painel:sempreativa'),
      ));
    }
    if (a.dnsPrivado != 'DESLIGADO' && !c.dispensado('painel:dnsprivado:${a.dnsPrivado}')) {
      final fixo = a.dnsPrivado.startsWith('FIXO:');
      lista.add(CartaoAviso(
        icone: Icons.dns,
        cor: fixo ? Cores.vermelho : Cores.amarelo,
        titulo: fixo ? 'DNS Privado impede o Orbion de funcionar' : 'DNS Privado deixa a navegação mais lenta',
        texto: fixo
            ? 'O DNS Privado está fixo em "${a.dnsPrivado.substring(5)}". Com ele ligado, Instagram, YouTube e outros param de abrir, e o bloqueio de rastreadores deixa de funcionar.\n\nDesligue em Configurações → Rede e Internet → DNS Privado. Com o Orbion conectado você não perde nada: o túnel resolve os nomes por dentro.'
            : 'O DNS Privado está no modo "Automático": a primeira página demora uns 15 segundos a mais para abrir.\n\nPara corrigir: Configurações → Rede e Internet → DNS Privado → "Desativado".',
        botao: 'Corrigir agora',
        aoTocarBotao: () => _executar('ABRIR_DNS_PRIVADO'),
        botaoSecundario: 'Não avisar',
        aoTocarSecundario: () => c.dispensarAviso('painel:dnsprivado:${a.dnsPrivado}'),
      ));
    }
    if (!a.notificacoes && !c.dispensado('painel:notificacao')) {
      lista.add(CartaoAviso(
        icone: Icons.notifications_off,
        cor: Cores.amarelo,
        titulo: 'Notificações bloqueadas',
        texto: 'Sem elas você não vê se a VPN está no ar, nem consegue desligá-la pela barra de notificações.',
        botao: 'Permitir',
        aoTocarBotao: () => _executar('PEDIR_NOTIFICACAO'),
        botaoSecundario: 'Não avisar',
        aoTocarSecundario: () => c.dispensarAviso('painel:notificacao'),
      ));
    }
    if (s.estado == EstadoVpn.conectado && s.quedasDoTor >= 2 && !c.dispensado('painel:quedas')) {
      lista.add(CartaoAviso(
        icone: Icons.healing,
        cor: Cores.amarelo,
        titulo: 'O Tor caiu ${s.quedasDoTor} vezes nesta sessão',
        texto: 'O Orbion religou sozinho todas as vezes. Se continuar acontecendo, o Android pode estar encerrando o Tor em segundo plano: confira os ajustes de "Manter o Orbion vivo" na aba Config.',
        botao: 'Ver ajustes',
        aoTocarBotao: () => widget.irPara(3),
        botaoSecundario: 'Não avisar',
        aoTocarSecundario: () => c.dispensarAviso('painel:quedas'),
      ));
    }
    return lista;
  }
}

class _PintorDoAnel extends CustomPainter {
  final Color cor;
  final double progresso;
  final double fase;
  final bool animado;

  _PintorDoAnel({required this.cor, required this.progresso, required this.fase, required this.animado});

  @override
  void paint(Canvas canvas, Size size) {
    final centro = size.center(Offset.zero);
    final raio = size.width / 2 - 10;

    // Brilho de fundo.
    final brilho = Paint()
      ..color = cor.withValues(alpha: 0.10 + (animado ? 0.08 * math.sin(fase * 2 * math.pi).abs() : 0.04))
      ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 26);
    canvas.drawCircle(centro, raio - 8, brilho);

    // Trilho.
    final trilho = Paint()
      ..color = Cores.borda
      ..style = PaintingStyle.stroke
      ..strokeWidth = 8;
    canvas.drawCircle(centro, raio, trilho);

    // Arco de progresso.
    final arco = Paint()
      ..shader = SweepGradient(
        colors: [cor.withValues(alpha: 0.4), cor, cor.withValues(alpha: 0.4)],
        transform: GradientRotation(fase * 2 * math.pi),
      ).createShader(Rect.fromCircle(center: centro, radius: raio))
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round
      ..strokeWidth = 8;
    final double varredura = animado && progresso <= 0.02 ? 0.25 : progresso.clamp(0.0, 1.0).toDouble();
    final double inicio = animado && progresso <= 0.02 ? fase * 2 * math.pi : -math.pi / 2;
    if (varredura > 0) {
      canvas.drawArc(Rect.fromCircle(center: centro, radius: raio), inicio, varredura * 2 * math.pi, false, arco);
    }
  }

  @override
  bool shouldRepaint(covariant _PintorDoAnel old) =>
      old.cor != cor || old.progresso != progresso || old.fase != fase || old.animado != animado;
}
