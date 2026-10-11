import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../controlador.dart';
import '../motor.dart';
import '../tema.dart';
import '../util.dart';
import '../widgets/comuns.dart';

enum _Filtro { todos, fora, tor }

/// Rotas por aplicativo: quem passa pela rede Tor e quem vai por fora.
class TelaApps extends StatefulWidget {
  final ControladorOrbion c;
  final ValueChanged<int> irPara;
  const TelaApps({super.key, required this.c, required this.irPara});

  @override
  State<TelaApps> createState() => _TelaAppsState();
}

class _TelaAppsState extends State<TelaApps> {
  List<AppRota> _apps = [];
  final Map<String, Uint8List> _icones = {};
  final Set<String> _pedindoIcone = {};
  bool _carregando = true;
  bool _mostrarSistema = false;
  String _busca = '';
  _Filtro _filtro = _Filtro.todos;
  String? _erro;
  int _ultimoRetorno = -1;

  ControladorOrbion get c => widget.c;

  @override
  void initState() {
    super.initState();
    _carregar();
  }

  Future<void> _carregar() async {
    setState(() {
      _carregando = true;
      _erro = null;
    });
    try {
      final apps = await Motor.listarApps(incluirSistema: _mostrarSistema);
      if (!mounted) return;
      setState(() => _apps = apps);
    } catch (e) {
      if (mounted) setState(() => _erro = 'Não deu para ler a lista de apps agora. Puxe para baixo para tentar de novo.');
    } finally {
      if (mounted) setState(() => _carregando = false);
    }
  }

  void _pedirIcones(List<String> pacotes) {
    final faltando = pacotes.where((p) => !_icones.containsKey(p) && !_pedindoIcone.contains(p)).take(30).toList();
    if (faltando.isEmpty) return;
    _pedindoIcone.addAll(faltando);
    Motor.icones(faltando).then((m) {
      if (!mounted) return;
      setState(() => _icones.addAll(m));
    }).catchError((_) {});
  }

  Future<void> _alternar(AppRota app, bool pelaTor) async {
    final ok = await confirmar(
      context,
      titulo: pelaTor ? 'Mandar ${app.nome} pela rede Tor?' : 'Mandar ${app.nome} por fora do Tor?',
      texto: pelaTor
          ? 'Esse aplicativo passa a usar a rede Tor.\n\nSe ele recusar conexões do Tor (bancos, gov.br, WhatsApp e redes sociais costumam recusar), pode parar de funcionar.'
          : 'Esse aplicativo passa a usar a internet normal, sem o Tor: o serviço dele vê o seu IP real. Os dados continuam protegidos pela criptografia do próprio app.\n\nOs demais continuam pela rede Tor.',
      sim: pelaTor ? 'Pela rede Tor' : 'Por fora do Tor',
    );
    if (!ok) return;
    try {
      final rota = await Motor.rotaDeApp(app.pacote, pelaTor);
      if (!mounted) return;
      setState(() {
        final i = _apps.indexWhere((a) => a.pacote == app.pacote);
        if (i >= 0) _apps[i] = app.comRota(rotaAppDeTexto(rota));
      });
      await c.salvar(const {}); // recarrega a configuração
      if (mounted && c.status.ligada) {
        mostrarAviso(context, 'Aplicado. A internet pode piscar por 1 segundo.');
      }
    } catch (e) {
      if (mounted) mostrarAviso(context, 'Não foi possível mudar a rota: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: c,
      builder: (context, _) {
        // Voltou de outra tela do Android: a lista de apps pode ter mudado.
        if (c.retornos != _ultimoRetorno) {
          final primeira = _ultimoRetorno < 0;
          _ultimoRetorno = c.retornos;
          if (!primeira) {
            WidgetsBinding.instance.addPostFrameCallback((_) {
              if (mounted && !_carregando) _carregar();
            });
          }
        }
        final busca = _busca.toLowerCase();
        final fora = _apps.where((a) => !a.pelaTor).toList();
        final filtrados = _apps.where((a) {
          if (_filtro == _Filtro.fora && a.pelaTor) return false;
          if (_filtro == _Filtro.tor && !a.pelaTor) return false;
          return busca.isEmpty || a.nome.toLowerCase().contains(busca) || a.pacote.toLowerCase().contains(busca);
        }).toList();

        return RefreshIndicator(
          onRefresh: _carregar,
          child: CustomScrollView(
            slivers: [
              SliverPadding(
                padding: const EdgeInsets.fromLTRB(18, 12, 18, 0),
                sliver: SliverList(
                  delegate: SliverChildListDelegate([
                    const Text('ROTAS DOS APPS', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900, letterSpacing: 2)),
                    const SizedBox(height: 4),
                    const Text('Quem passa pela rede Tor e quem vai por fora', style: TextStyle(color: Cores.textoFraco)),
                    const SizedBox(height: 16),
                    _cartaoResumo(fora.length),
                    if (c.status.aplicandoRotas)
                      const Padding(
                        padding: EdgeInsets.only(bottom: 10),
                        child: Row(children: [
                          SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)),
                          SizedBox(width: 10),
                          Expanded(child: Text('Aplicando as rotas... a internet pode piscar por 1 segundo.', style: TextStyle(color: Cores.ciano))),
                        ]),
                      ),
                    if (_erro != null) CartaoAviso(icone: Icons.error_outline, cor: Cores.amarelo, titulo: 'Lista indisponível', texto: _erro!),
                    TextField(
                      decoration: InputDecoration(
                        hintText: 'Buscar apps...',
                        prefixIcon: const Icon(Icons.search),
                        filled: true,
                        fillColor: Cores.cartao,
                        border: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: BorderSide.none),
                      ),
                      onChanged: (v) => setState(() => _busca = v),
                    ),
                    const SizedBox(height: 10),
                    Wrap(
                      spacing: 8,
                      children: [
                        ChoiceChip(
                          label: Text('Todos (${_apps.length})'),
                          selected: _filtro == _Filtro.todos,
                          onSelected: (_) => setState(() => _filtro = _Filtro.todos),
                        ),
                        ChoiceChip(
                          label: Text('Por fora (${fora.length})'),
                          selected: _filtro == _Filtro.fora,
                          onSelected: (_) => setState(() => _filtro = _Filtro.fora),
                        ),
                        ChoiceChip(
                          label: Text('Pelo Tor (${_apps.length - fora.length})'),
                          selected: _filtro == _Filtro.tor,
                          onSelected: (_) => setState(() => _filtro = _Filtro.tor),
                        ),
                      ],
                    ),
                    Row(
                      children: [
                        Text('${filtrados.length} aplicativos', style: const TextStyle(color: Cores.textoFraco)),
                        const Spacer(),
                        TextButton.icon(
                          onPressed: () {
                            setState(() => _mostrarSistema = !_mostrarSistema);
                            _carregar();
                          },
                          icon: Icon(_mostrarSistema ? Icons.visibility_off : Icons.visibility, size: 18),
                          label: Text(_mostrarSistema ? 'Ocultar sistema' : 'Mostrar sistema'),
                        ),
                      ],
                    ),
                  ]),
                ),
              ),
              if (_carregando && _apps.isEmpty)
                const SliverToBoxAdapter(
                  child: Padding(padding: EdgeInsets.all(30), child: Center(child: CircularProgressIndicator())),
                )
              else
                SliverPadding(
                  padding: const EdgeInsets.fromLTRB(10, 0, 10, 24),
                  sliver: SliverList(
                    delegate: SliverChildBuilderDelegate(
                      (context, i) {
                        final app = filtrados[i];
                        _pedirIcones(filtrados.skip(i).take(20).map((a) => a.pacote).toList());
                        final icone = _icones[app.pacote];
                        final corRota = app.pelaTor ? Cores.verde : Cores.amarelo;
                        return ListTile(
                          leading: icone != null
                              ? Image.memory(icone, width: 40, height: 40, gaplessPlayback: true)
                              : const SizedBox(width: 40, height: 40, child: Icon(Icons.android, color: Cores.textoFraco)),
                          title: Text(app.nome, maxLines: 1, overflow: TextOverflow.ellipsis),
                          subtitle: Text(textoDaRota(app), style: TextStyle(color: corRota, fontSize: 12.5)),
                          trailing: Switch(value: app.pelaTor, onChanged: (v) => _alternar(app, v)),
                        );
                      },
                      childCount: filtrados.length,
                    ),
                  ),
                ),
              const SliverToBoxAdapter(child: SizedBox(height: 20)),
            ],
          ),
        );
      },
    );
  }

  Widget _cartaoResumo(int quantosFora) {
    final inteligente = c.config.rotaInteligente;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.alt_route, color: inteligente ? Cores.ciano : Cores.textoFraco),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    inteligente ? 'Rota inteligente ligada' : 'Rota inteligente desligada',
                    style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 15),
                  ),
                ),
                Selo(inteligente ? 'AUTOMÁTICO' : 'MANUAL', inteligente ? Cores.verde : Cores.textoFraco),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              inteligente
                  ? '$quantosFora app(s) vão por fora do Tor — bancos, governo, mensageiros, redes sociais e outros que recusam a rede Tor. Todo o resto passa pelo Tor.\n\n'
                      'A chave de cada app (ligada = pela rede Tor) muda a rota dele. O que vai por fora continua cifrado pelo próprio app.'
                  : 'Só os apps que você escolher vão por fora. Todo o resto passa pela rede Tor — bancos, gov.br e WhatsApp costumam não funcionar assim.',
              style: const TextStyle(color: Cores.textoFraco, height: 1.4, fontSize: 13),
            ),
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(onPressed: () => widget.irPara(3), child: const Text('Ajustar categorias')),
            ),
          ],
        ),
      ),
    );
  }
}
