import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../controlador.dart';
import '../motor.dart';
import '../tema.dart';
import '../widgets/comuns.dart';

/// Escolha de quais aplicativos ficam FORA do túnel (split tunneling).
class TelaApps extends StatefulWidget {
  final ControladorOrbion c;
  const TelaApps({super.key, required this.c});

  @override
  State<TelaApps> createState() => _TelaAppsState();
}

class _TelaAppsState extends State<TelaApps> {
  List<AppInstalado> _apps = [];
  final Map<String, Uint8List> _icones = {};
  final Set<String> _pedindoIcone = {};
  List<Suspeita> _suspeitas = [];
  List<AppProblematico> _problematicos = [];
  bool _carregando = true;
  bool _mostrarSistema = false;
  String _busca = '';

  ControladorOrbion get c => widget.c;

  @override
  void initState() {
    super.initState();
    _carregar();
  }

  Future<void> _carregar() async {
    setState(() => _carregando = true);
    try {
      final apps = await Motor.listarApps(incluirSistema: _mostrarSistema);
      final suspeitas = await Motor.suspeitas();
      final problematicos = await Motor.appsProblematicos();
      if (!mounted) return;
      setState(() {
        _apps = apps;
        _suspeitas = suspeitas;
        _problematicos = problematicos;
      });
    } catch (_) {
      // Mantém o que já tinha.
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
    });
  }

  bool get _mudancaPendente {
    final s = c.status;
    if (!s.ligada) return false;
    final desejado = c.config.escolherApps ? c.config.appsForaDoTunel : <String>{};
    final atual = s.appsForaNaSessao.toSet();
    return desejado.length != atual.length || !desejado.containsAll(atual);
  }

  Future<void> _trocarModo(bool escolher) async {
    final ok = await confirmar(
      context,
      titulo: escolher ? 'Ativar escolha por aplicativo?' : 'Proteger todos os aplicativos?',
      texto: escolher
          ? 'Você poderá tirar aplicativos da proteção individualmente.'
          : 'Todos voltam a passar pelo Tor, inclusive os que você tinha tirado.',
    );
    if (!ok) return;
    await c.salvar({'escolherApps': escolher});
  }

  Future<void> _alternarApp(AppInstalado app, bool protegido) async {
    final ok = await confirmar(
      context,
      titulo: protegido ? 'Devolver ${app.nome} à proteção?' : 'Tirar ${app.nome} da proteção?',
      texto: protegido
          ? 'Esse aplicativo voltará a passar pelo Tor.\n\nSe ele bloquear conexões pelo Tor, pode parar de funcionar.'
          : 'Esse aplicativo passará a usar a internet SEM o Tor, com o seu IP real visível para os servidores dele.\n\nOs demais continuam protegidos.',
      sim: protegido ? 'Devolver' : 'Tirar da proteção',
    );
    if (!ok) return;
    final fora = {...c.config.appsForaDoTunel};
    if (protegido) {
      fora.remove(app.pacote);
    } else {
      fora.add(app.pacote);
    }
    await c.salvar({'appsForaDoTunel': fora.toList()});
  }

  Future<void> _aplicar() async {
    final erro = await c.reconectar();
    if (!mounted) return;
    mostrarAviso(context, erro ?? 'Reconectando para aplicar a nova lista de apps...');
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: c,
      builder: (context, _) {
        final escolher = c.config.escolherApps;
        final fora = c.config.appsForaDoTunel;
        final filtrados = _apps
            .where((a) =>
                _busca.isEmpty ||
                a.nome.toLowerCase().contains(_busca.toLowerCase()) ||
                a.pacote.toLowerCase().contains(_busca.toLowerCase()))
            .toList();

        return RefreshIndicator(
          onRefresh: _carregar,
          child: CustomScrollView(
            slivers: [
              SliverPadding(
                padding: const EdgeInsets.fromLTRB(18, 12, 18, 0),
                sliver: SliverList(
                  delegate: SliverChildListDelegate([
                    const Text('APPS NO TÚNEL', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900, letterSpacing: 2)),
                    const SizedBox(height: 4),
                    const Text('Quem passa pela rede Tor e quem fica de fora',
                        style: TextStyle(color: Cores.textoFraco)),
                    const SizedBox(height: 16),
                    SegmentedButton<bool>(
                      segments: const [
                        ButtonSegment(value: false, label: Text('TUDO PELO TOR'), icon: Icon(Icons.shield)),
                        ButtonSegment(value: true, label: Text('ESCOLHER APPS'), icon: Icon(Icons.checklist)),
                      ],
                      selected: {escolher},
                      onSelectionChanged: (v) => _trocarModo(v.first),
                    ),
                    const SizedBox(height: 14),
                    if (_mudancaPendente)
                      CartaoAviso(
                        icone: Icons.sync_problem,
                        cor: Cores.amarelo,
                        titulo: '⚠ Suas mudanças ainda NÃO estão valendo',
                        texto: 'A lista de apps só é aplicada quando o túnel é criado. Enquanto você não reconectar, vale a lista de quando a VPN foi ligada.\n\nA internet fica fora do ar por alguns segundos.',
                        botao: 'Aplicar agora  •  reconecta sozinho',
                        aoTocarBotao: _aplicar,
                      ),
                    if (_suspeitas.isNotEmpty) _cartaoSuspeitas(),
                    if (_problematicos.isNotEmpty && !c.dispensado('apps:problematicos')) _cartaoProblematicos(),
                    if (escolher) ...[
                      Text(
                        fora.isEmpty
                            ? 'Desligue a chave dos apps que NÃO devem passar pelo Tor. Eles vão navegar sem proteção, com seu IP real.'
                            : '${fora.length} app(s) fora do túnel. Eles navegam sem proteção, com seu IP real.',
                        style: const TextStyle(color: Cores.textoFraco, height: 1.4),
                      ),
                      const SizedBox(height: 12),
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
                      const SizedBox(height: 6),
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
                    ] else
                      const LinhaInfo(
                        icone: Icons.verified_user,
                        titulo: 'Todos os aplicativos protegidos',
                        subtitulo: 'Todo o tráfego passa pela rede Tor.',
                        corDoIcone: Cores.verde,
                        explicacao: 'Para tirar algum aplicativo da proteção (por exemplo, um app de banco que não funcione pelo Tor), toque em ESCOLHER APPS.',
                      ),
                  ]),
                ),
              ),
              if (escolher && _carregando)
                const SliverToBoxAdapter(
                  child: Padding(padding: EdgeInsets.all(30), child: Center(child: CircularProgressIndicator())),
                ),
              if (escolher && !_carregando)
                SliverPadding(
                  padding: const EdgeInsets.fromLTRB(10, 0, 10, 24),
                  sliver: SliverList(
                    delegate: SliverChildBuilderDelegate(
                      (context, i) {
                        final app = filtrados[i];
                        _pedirIcones(filtrados.skip(i).take(20).map((a) => a.pacote).toList());
                        final protegido = !fora.contains(app.pacote);
                        final icone = _icones[app.pacote];
                        return ListTile(
                          leading: icone != null
                              ? Image.memory(icone, width: 40, height: 40, gaplessPlayback: true)
                              : const SizedBox(width: 40, height: 40, child: Icon(Icons.android, color: Cores.textoFraco)),
                          title: Text(app.nome, maxLines: 1, overflow: TextOverflow.ellipsis),
                          subtitle: Text(
                            protegido ? 'Protegido pelo Tor' : 'Fora do túnel  •  IP real',
                            style: TextStyle(color: protegido ? Cores.verde : Cores.amarelo, fontSize: 12.5),
                          ),
                          trailing: Switch(value: protegido, onChanged: (v) => _alternarApp(app, v)),
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

  Widget _cartaoSuspeitas() {
    final linhas = _suspeitas.map((s) {
      final quem = s.servico != null ? '${s.servico} (${s.motivo})' : s.servidor;
      return '• $quem — ${s.tentativas} tentativas de reconexão';
    }).join('\n');
    return CartaoAviso(
      icone: Icons.report_problem,
      cor: Cores.amarelo,
      titulo: 'Detectamos aplicativos apanhando',
      texto: '$linhas\n\nConexão que reinicia sem parar é sinal de recusa do outro lado, não de lentidão.\n\n'
          'Se o serviço estiver no catálogo do Orbion, o desvio automático já resolve. Se não estiver e o aplicativo continuar sem funcionar, aí sim vale tirá-lo do túnel na lista abaixo.',
    );
  }

  Widget _cartaoProblematicos() {
    final nomes = _problematicos.map((p) => '• ${p.nome}: ${p.motivo}').join('\n');
    return CartaoAviso(
      icone: Icons.info_outline,
      cor: Cores.ciano,
      titulo: '${_problematicos.length} app(s) instalados que bloqueiam Tor',
      texto: '$nomes\n\n✓ Você não precisa fazer nada. Com o "desvio para serviços que barram o Tor" ligado (aba Config), o Orbion desvia sozinho apenas os endereços que esses serviços exigem.\n\n'
          'Tirar o aplicativo inteiro do túnel só vale se ele continuar sem funcionar mesmo assim.',
      botaoSecundario: 'Entendi, não avisar mais',
      aoTocarSecundario: () => c.dispensarAviso('apps:problematicos'),
    );
  }
}
