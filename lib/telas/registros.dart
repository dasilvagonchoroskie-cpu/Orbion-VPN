import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../controlador.dart';
import '../motor.dart';
import '../tema.dart';
import '../util.dart';
import '../widgets/comuns.dart';

enum _Vista { decisoes, eventos, tecnico }

class TelaRegistros extends StatefulWidget {
  final ControladorOrbion c;

  /// A aba está à vista? (As abas ficam todas montadas; só a visível consulta.)
  final bool visivel;
  const TelaRegistros({super.key, required this.c, this.visivel = true});

  @override
  State<TelaRegistros> createState() => _TelaRegistrosState();
}

class _TelaRegistrosState extends State<TelaRegistros> {
  /// Linhas copiadas no "Técnico": colar mais do que isso em conversas corta
  /// o texto. O registro completo vai pelo "Enviar relatório".
  static const _linhasParaCopiar = 150;

  _Vista _vista = _Vista.decisoes;
  bool _logDoTor = true;
  Registros _r = const Registros();
  LogTecnico _log = const LogTecnico();
  List<Evento> _eventos = const [];
  List<Fechamento> _fechamentos = const [];
  Timer? _relogio;
  bool _ocupado = false;
  bool _deNovo = false;
  bool _enviando = false;

  @override
  void initState() {
    super.initState();
    if (widget.visivel) _atualizar();
    // Sem ninguém olhando (outra aba ou app em segundo plano) não consulta
    // nada: poupa bateria.
    _relogio = Timer.periodic(const Duration(seconds: 3), (_) {
      if (widget.visivel && widget.c.emPrimeiroPlano) _atualizar();
    });
  }

  @override
  void didUpdateWidget(TelaRegistros antes) {
    super.didUpdateWidget(antes);
    if (widget.visivel && !antes.visivel) _atualizar();
  }

  @override
  void dispose() {
    _relogio?.cancel();
    super.dispose();
  }

  Future<void> _enviarRelatorio() async {
    if (_enviando) return;
    setState(() => _enviando = true);
    final erro = await widget.c.enviarRelatorio();
    if (!mounted) return;
    setState(() => _enviando = false);
    if (erro != null) mostrarAviso(context, erro);
  }

  Future<void> _atualizar() async {
    if (_ocupado) {
      _deNovo = true; // ex.: trocou de vista no meio de uma consulta
      return;
    }
    _ocupado = true;
    try {
      switch (_vista) {
        case _Vista.tecnico:
          final l = await Motor.logTecnico(max: 400);
          if (mounted) setState(() => _log = l);
        case _Vista.eventos:
          final e = await Motor.eventos(max: 150);
          final f = await Motor.fechamentos();
          if (mounted) {
            setState(() {
              _eventos = e;
              _fechamentos = f;
            });
          }
        case _Vista.decisoes:
          final r = await Motor.registros(max: 250);
          if (mounted) setState(() => _r = r);
      }
    } catch (_) {
    } finally {
      _ocupado = false;
      if (_deNovo && mounted) {
        _deNovo = false;
        unawaited(_atualizar());
      }
    }
  }

  void _mudarVista(_Vista v) {
    setState(() => _vista = v);
    _atualizar();
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(18, 12, 18, 8),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Expanded(
                    child: Text('REGISTROS', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900, letterSpacing: 2)),
                  ),
                  TextButton.icon(
                    onPressed: _enviando ? null : _enviarRelatorio,
                    icon: _enviando
                        ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Icon(Icons.ios_share, size: 18),
                    label: const Text('Enviar relatório'),
                  ),
                ],
              ),
              const Text('O que o Orbion decidiu e o que aconteceu com a VPN', style: TextStyle(color: Cores.textoFraco)),
              const SizedBox(height: 10),
              SegmentedButton<_Vista>(
                segments: const [
                  ButtonSegment(value: _Vista.decisoes, label: Text('Decisões')),
                  ButtonSegment(value: _Vista.eventos, label: Text('Eventos')),
                  ButtonSegment(value: _Vista.tecnico, label: Text('Técnico')),
                ],
                selected: {_vista},
                showSelectedIcon: false,
                onSelectionChanged: (v) => _mudarVista(v.first),
              ),
            ],
          ),
        ),
        Expanded(
          child: switch (_vista) {
            _Vista.decisoes => _vistaDecisoes(),
            _Vista.eventos => _vistaEventos(),
            _Vista.tecnico => _vistaTecnica(),
          },
        ),
      ],
    );
  }

  Widget _contador(String numero, String rotulo, Color cor) => Expanded(
        child: Card(
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 6),
            child: Column(
              children: [
                Text(numero, style: TextStyle(color: cor, fontSize: 22, fontWeight: FontWeight.w900)),
                const SizedBox(height: 4),
                Text(rotulo, textAlign: TextAlign.center, style: const TextStyle(color: Cores.textoFraco, fontSize: 11, height: 1.2)),
              ],
            ),
          ),
        ),
      );

  // ------------------------------------------------------------------
  // Decisões
  // ------------------------------------------------------------------

  Widget _vistaDecisoes() {
    return ListView(
      padding: const EdgeInsets.fromLTRB(18, 4, 18, 24),
      children: [
        Row(
          children: [
            _contador('${_r.bloqueados}', 'rastreadores\nrecusados', Cores.vermelho),
            const SizedBox(width: 6),
            _contador('${_r.pelaRedeTor}', 'pela rede\nTor', Cores.verde),
            const SizedBox(width: 6),
            _contador('${_r.diretos}', 'por fora\ndo Tor', Cores.amarelo),
            const SizedBox(width: 6),
            _contador('${_r.pausados}', 'em pausa\n(falharam)', Cores.ciano),
          ],
        ),
        const SizedBox(height: 10),
        if (_r.lista.isNotEmpty)
          const Padding(
            padding: EdgeInsets.only(bottom: 8),
            child: Text(
              'Um site não abriu? Toque nele para mandar por fora do Tor (ou sempre pelo Tor).',
              style: TextStyle(color: Cores.textoFraco, fontSize: 12.5),
            ),
          ),
        if (_r.lista.isEmpty)
          const Padding(
            padding: EdgeInsets.only(top: 40),
            child: Column(
              children: [
                Icon(Icons.travel_explore, color: Cores.textoFraco, size: 48),
                SizedBox(height: 12),
                Text('Nenhuma decisão ainda.', style: TextStyle(fontWeight: FontWeight.w700)),
                SizedBox(height: 6),
                Text(
                  'Conecte-se e use o celular normalmente. Cada endereço que os aplicativos procurarem aparece aqui, com o caminho que o Orbion escolheu para ele. '
                  'Os apps que vão inteiros por fora do Tor (banco, WhatsApp...) não passam pelo Orbion, então não aparecem.',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: Cores.textoFraco, height: 1.4),
                ),
              ],
            ),
          )
        else
          ..._r.lista.map((r) {
            final (texto, cor) = switch (r.destino) {
              Destino.bloqueado => ('RECUSADO', Cores.vermelho),
              Destino.direto => ('POR FORA', Cores.amarelo),
              Destino.pausado => ('PAUSADO', Cores.ciano),
              Destino.tor => ('PELO TOR', Cores.verde),
            };
            return InkWell(
              onTap: r.base == null ? null : () => _escolherRota(r),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 5),
                child: Row(
                  children: [
                    SizedBox(width: 82, child: Selo(texto, cor)),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(r.dominio, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 13.5)),
                          if (r.motivo != null)
                            Text(r.motivo!, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: Cores.textoFraco, fontSize: 11)),
                        ],
                      ),
                    ),
                    Text(horaCurta(r.quando), style: const TextStyle(color: Cores.textoFraco, fontSize: 11.5)),
                  ],
                ),
              ),
            );
          }),
      ],
    );
  }

  Future<void> _escolherRota(Registro r) async {
    final base = r.base!;
    final escolha = await showModalBottomSheet<String>(
      context: context,
      backgroundColor: Cores.cartao,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 18, 20, 6),
              child: Text(base, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 17)),
            ),
            const Padding(
              padding: EdgeInsets.fromLTRB(20, 0, 20, 8),
              child: Text('Vale para este site e todos os endereços dele.', style: TextStyle(color: Cores.textoFraco)),
            ),
            ListTile(
              leading: const Icon(Icons.call_split, color: Cores.amarelo),
              title: const Text('Sempre por fora do Tor'),
              subtitle: const Text('Para sites que não abrem pelo Tor. Só com HTTPS.'),
              onTap: () => Navigator.pop(ctx, 'direto'),
            ),
            ListTile(
              leading: const Icon(Icons.shield, color: Cores.verde),
              title: const Text('Sempre pela rede Tor'),
              subtitle: const Text('Mesmo que a rota inteligente mande por fora.'),
              onTap: () => Navigator.pop(ctx, 'tor'),
            ),
            ListTile(
              leading: const Icon(Icons.undo, color: Cores.textoFraco),
              title: const Text('Usar o automático'),
              subtitle: const Text('Remove a sua escolha para este site.'),
              onTap: () => Navigator.pop(ctx, 'padrao'),
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
    if (escolha == null) return;
    try {
      await widget.c.rotaDeDominio(base, escolha);
      if (!mounted) return;
      mostrarAviso(
        context,
        switch (escolha) {
          'direto' => '$base vai sempre por fora do Tor.',
          'tor' => '$base vai sempre pela rede Tor.',
          _ => '$base voltou para o automático.',
        },
      );
    } catch (e) {
      if (mounted) mostrarAviso(context, 'Não foi possível mudar a rota: $e');
    }
  }

  // ------------------------------------------------------------------
  // Eventos
  // ------------------------------------------------------------------

  Widget _vistaEventos() {
    // O que aconteceu antes da atualização era de outra versão: não assusta
    // quem acabou de atualizar, mas continua no relatório para o suporte.
    final atuais = _fechamentos.where((f) => !f.anterior).toList();
    final antigos = _fechamentos.length - atuais.length;
    return ListView(
      padding: const EdgeInsets.fromLTRB(18, 4, 18, 24),
      children: [
        const TituloDeSecao('QUANDO O ANDROID FECHOU O ORBION'),
        if (atuais.isEmpty)
          const LinhaInfo(
            icone: Icons.verified,
            corDoIcone: Cores.verde,
            titulo: 'Nenhum fechamento inesperado',
            subtitulo: 'Desde que esta versão foi instalada, o Android não derrubou o Orbion.',
          )
        else
          for (final f in atuais) _cartaoFechamento(f),
        if (antigos > 0)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Text(
              '$antigos fechamento(s) de antes da atualização (versão anterior do Orbion) não aparecem aqui. Eles continuam no relatório para o suporte.',
              style: const TextStyle(color: Cores.textoFraco, fontSize: 12),
            ),
          ),
        const TituloDeSecao('DIÁRIO DA VPN'),
        if (_eventos.isEmpty)
          const Text('Nada registrado ainda.', style: TextStyle(color: Cores.textoFraco))
        else
          for (final e in _eventos)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(width: 92, child: Text(dataEHora(e.quando), style: const TextStyle(color: Cores.textoFraco, fontSize: 12))),
                  Expanded(child: Text(e.texto, style: const TextStyle(fontSize: 13, height: 1.3))),
                ],
              ),
            ),
      ],
    );
  }

  Widget _cartaoFechamento(Fechamento f) {
    final titulo = '${dataEHora(f.quando)} — ${f.daVpn ? 'VPN' : 'tela do app'}';
    final detalhes = StringBuffer()
      ..writeln('Orbion VPN — fechamento em ${dataEHora(f.quando)}')
      ..writeln('Parte: ${f.daVpn ? 'VPN (processo :vpn)' : 'tela'}')
      ..writeln('Motivo: ${f.explicacao}')
      ..writeln('Descrição do Android: ${f.descricao}');
    if (f.rastro != null) {
      detalhes
        ..writeln()
        ..writeln(f.rastro);
    }
    return CartaoAviso(
      icone: f.daVpn ? Icons.vpn_lock : Icons.phone_android,
      cor: f.daVpn ? Cores.amarelo : Cores.ciano,
      titulo: titulo,
      texto: '${f.explicacao.isEmpty ? 'Motivo não informado' : f.explicacao[0].toUpperCase() + f.explicacao.substring(1)}.'
          '${f.descricao.isNotEmpty ? '\n\nO Android disse: ${f.descricao}' : ''}'
          '${f.daVpn ? '\n\nA VPN é religada sozinha. Para evitar novas quedas, faça os ajustes de bateria em Config.' : '\n\nSó a tela fechou: a VPN continuou funcionando.'}',
      botao: f.rastro != null ? 'Copiar relatório do travamento' : 'Copiar detalhes',
      aoTocarBotao: () async {
        await Clipboard.setData(ClipboardData(text: detalhes.toString()));
        if (mounted) mostrarAviso(context, 'Copiado. Cole e envie para o suporte.');
      },
    );
  }

  // ------------------------------------------------------------------
  // Técnico
  // ------------------------------------------------------------------

  Widget _vistaTecnica() {
    final linhas = (_logDoTor ? _log.tor : _log.tunel).reversed.toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 18),
          child: Row(
            children: [
              ChoiceChip(
                label: const Text('Tor'),
                selected: _logDoTor,
                onSelected: (_) => setState(() => _logDoTor = true),
              ),
              const SizedBox(width: 8),
              ChoiceChip(
                label: const Text('Túnel'),
                selected: !_logDoTor,
                onSelected: (_) => setState(() => _logDoTor = false),
              ),
              const Spacer(),
              IconButton(
                tooltip: 'Copiar as últimas $_linhasParaCopiar linhas',
                icon: const Icon(Icons.copy),
                onPressed: () async {
                  final todas = _logDoTor ? _log.tor : _log.tunel;
                  final ultimas = todas.length > _linhasParaCopiar ? todas.sublist(todas.length - _linhasParaCopiar) : todas;
                  await Clipboard.setData(ClipboardData(text: ultimas.join('\n')));
                  if (!mounted) return;
                  mostrarAviso(
                    context,
                    'Copiadas as últimas ${ultimas.length} linhas. Para mandar tudo ao suporte, use "Enviar relatório".',
                    acao: 'Enviar',
                    aoTocar: _enviarRelatorio,
                  );
                },
              ),
            ],
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(18, 2, 18, 6),
          child: Text('${linhas.length} linhas  •  mais recente no topo', style: const TextStyle(color: Cores.textoFraco, fontSize: 12)),
        ),
        Expanded(
          child: linhas.isEmpty
              ? const Center(
                  child: Text('Nenhum registro ainda.\nOs registros aparecem depois da primeira tentativa de conexão.',
                      textAlign: TextAlign.center, style: TextStyle(color: Cores.textoFraco)),
                )
              : Container(
                  margin: const EdgeInsets.fromLTRB(12, 0, 12, 12),
                  decoration: BoxDecoration(color: Colors.black, borderRadius: BorderRadius.circular(12), border: Border.all(color: Cores.borda)),
                  child: ListView.builder(
                    padding: const EdgeInsets.all(10),
                    itemCount: linhas.length,
                    itemBuilder: (context, i) {
                      final l = linhas[i];
                      final low = l.toLowerCase();
                      final cor = (low.contains('[err') || low.contains('[e]') || low.contains('error') || low.contains('failed'))
                          ? Cores.vermelho
                          : (low.contains('[warn') || low.contains('[w]'))
                              ? Cores.amarelo
                              : low.contains('bootstrapped')
                                  ? Cores.ciano
                                  : const Color(0xFFB5C2D3);
                      return Padding(
                        padding: const EdgeInsets.symmetric(vertical: 1.5),
                        child: Text(l, style: TextStyle(fontFamily: 'monospace', fontSize: 11, color: cor, height: 1.3)),
                      );
                    },
                  ),
                ),
        ),
      ],
    );
  }
}
