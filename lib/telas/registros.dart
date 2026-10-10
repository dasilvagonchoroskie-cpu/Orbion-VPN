import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../controlador.dart';
import '../motor.dart';
import '../tema.dart';
import '../util.dart';
import '../widgets/comuns.dart';

class TelaRegistros extends StatefulWidget {
  final ControladorOrbion c;
  const TelaRegistros({super.key, required this.c});

  @override
  State<TelaRegistros> createState() => _TelaRegistrosState();
}

class _TelaRegistrosState extends State<TelaRegistros> {
  bool _tecnico = false;
  bool _logDoTor = true;
  Registros _r = const Registros();
  LogTecnico _log = const LogTecnico();
  Timer? _relogio;

  @override
  void initState() {
    super.initState();
    _atualizar();
    _relogio = Timer.periodic(const Duration(seconds: 3), (_) => _atualizar());
  }

  @override
  void dispose() {
    _relogio?.cancel();
    super.dispose();
  }

  Future<void> _atualizar() async {
    try {
      if (_tecnico) {
        final l = await Motor.logTecnico(max: 400);
        if (mounted) setState(() => _log = l);
      } else {
        final r = await Motor.registros(max: 250);
        if (mounted) setState(() => _r = r);
      }
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(18, 12, 18, 8),
          child: Row(
            children: [
              const Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('REGISTROS', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900, letterSpacing: 2)),
                    SizedBox(height: 2),
                    Text('O que o Orbion decidiu, conexão por conexão', style: TextStyle(color: Cores.textoFraco)),
                  ],
                ),
              ),
              TextButton(
                onPressed: () {
                  setState(() => _tecnico = !_tecnico);
                  _atualizar();
                },
                child: Text(_tecnico ? 'Ver decisões' : 'Ver técnico'),
              ),
            ],
          ),
        ),
        Expanded(child: _tecnico ? _vistaTecnica() : _vistaDecisoes()),
      ],
    );
  }

  Widget _contador(String numero, String rotulo, Color cor) => Expanded(
        child: Card(
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 8),
            child: Column(
              children: [
                Text(numero, style: TextStyle(color: cor, fontSize: 24, fontWeight: FontWeight.w900)),
                const SizedBox(height: 4),
                Text(rotulo, textAlign: TextAlign.center, style: const TextStyle(color: Cores.textoFraco, fontSize: 11.5, height: 1.2)),
              ],
            ),
          ),
        ),
      );

  Widget _vistaDecisoes() {
    return ListView(
      padding: const EdgeInsets.fromLTRB(18, 4, 18, 24),
      children: [
        Row(
          children: [
            _contador('${_r.bloqueados}', 'rastreadores\nrecusados', Cores.vermelho),
            const SizedBox(width: 8),
            _contador('${_r.pelaRedeTor}', 'pela rede\nTor', Cores.verde),
            const SizedBox(width: 8),
            _contador('${_r.diretos}', 'desviados\n(barram Tor)', Cores.amarelo),
          ],
        ),
        const SizedBox(height: 14),
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
                  'Conecte-se e use o celular normalmente. Cada endereço que os aplicativos procurarem aparece aqui, com o caminho que o Orbion escolheu para ele.',
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
              Destino.direto => ('DESVIADO', Cores.amarelo),
              Destino.tor => ('PELO TOR', Cores.verde),
            };
            return Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Row(
                children: [
                  SizedBox(width: 86, child: Selo(texto, cor)),
                  const SizedBox(width: 8),
                  Expanded(child: Text(r.dominio, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 13.5))),
                  Text(horaCurta(r.quando), style: const TextStyle(color: Cores.textoFraco, fontSize: 11.5)),
                ],
              ),
            );
          }),
      ],
    );
  }

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
                tooltip: 'Copiar',
                icon: const Icon(Icons.copy),
                onPressed: () async {
                  await Clipboard.setData(ClipboardData(text: (_logDoTor ? _log.tor : _log.tunel).join('\n')));
                  if (mounted) mostrarAviso(context, 'Registro copiado.');
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
