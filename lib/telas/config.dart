import 'package:flutter/material.dart';

import '../controlador.dart';
import '../motor.dart';
import '../tema.dart';
import '../util.dart';
import '../widgets/comuns.dart';

class TelaConfig extends StatefulWidget {
  final ControladorOrbion c;
  final ValueChanged<int> irPara;
  const TelaConfig({super.key, required this.c, required this.irPara});

  @override
  State<TelaConfig> createState() => _TelaConfigState();
}

class _TelaConfigState extends State<TelaConfig> {
  List<ItemDiagnostico>? _diagnostico;
  bool _verificando = false;
  InfoMotor _info = const InfoMotor();
  ResumoRotas? _rotas;
  final TextEditingController _bridges = TextEditingController();
  final TextEditingController _novoSite = TextEditingController();
  bool _bridgesCarregadas = false;
  double? _rotacaoArrastando;
  int _ultimoRetorno = 0;
  bool _mostrarEmOrdem = false;
  final Set<String> _categoriasAbertas = {};

  ControladorOrbion get c => widget.c;

  /// Só estas mudanças precisam reconectar; o resto vale na hora.
  static const _exigemReconexao = {'usarBridges', 'bridges', 'contornoAutomatico'};

  @override
  void initState() {
    super.initState();
    _verificar();
    _carregarRotas();
    Motor.infoMotor().then((i) {
      if (mounted) setState(() => _info = i);
    }).catchError((_) {});
  }

  @override
  void dispose() {
    _bridges.dispose();
    _novoSite.dispose();
    super.dispose();
  }

  Future<void> _verificar() async {
    setState(() => _verificando = true);
    try {
      final d = await Motor.diagnostico();
      if (mounted) setState(() => _diagnostico = d);
    } catch (_) {
    } finally {
      if (mounted) setState(() => _verificando = false);
    }
  }

  Future<void> _carregarRotas() async {
    try {
      final r = await Motor.rotas();
      if (mounted) setState(() => _rotas = r);
    } catch (_) {}
  }

  Future<void> _salvar(Map<String, Object?> m) async {
    await c.salvar(m);
    if (!mounted) return;
    if (!c.status.ligada) return;
    if (m.keys.any(_exigemReconexao.contains)) {
      mostrarAviso(context, 'Vale a partir da próxima conexão.', acao: 'Reconectar agora', aoTocar: () => c.reconectar());
    } else {
      mostrarAviso(context, 'Pronto. Já está valendo.');
    }
  }

  Future<void> _salvarRotas(Map<String, Object?> m) async {
    await _salvar(m);
    await _carregarRotas();
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: c,
      builder: (context, _) {
        final cfg = c.config;
        // Voltou de uma tela do Android (bateria, VPN, marca...): confere de
        // novo, para o que já foi resolvido sumir sozinho.
        if (c.retornos != _ultimoRetorno) {
          _ultimoRetorno = c.retornos;
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (mounted && !_verificando) _verificar();
            if (mounted) _carregarRotas();
          });
        }
        if (!_bridgesCarregadas && c.carregado) {
          _bridges.text = cfg.bridges;
          _bridgesCarregadas = true;
        }
        return ListView(
          padding: const EdgeInsets.fromLTRB(18, 12, 18, 30),
          children: [
            const Text('CONFIGURAÇÕES', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900, letterSpacing: 2)),
            ..._secaoDiagnostico(),
            ..._secaoRotaInteligente(cfg),
            ..._secaoCriptografia(cfg),
            ..._secaoSeguranca(cfg),
            ..._secaoAutomatico(cfg),
            ..._secaoTor(cfg),
            ..._secaoDns(),
            ..._secaoSobre(),
          ],
        );
      },
    );
  }

  // ------------------------------------------------------------------
  // Diagnóstico
  // ------------------------------------------------------------------

  List<Widget> _secaoDiagnostico() {
    final itens = _diagnostico;
    final pendentes = itens?.where((i) => i.pendente).toList() ?? const <ItemDiagnostico>[];
    final emOrdem = itens?.where((i) => !i.pendente).toList() ?? const <ItemDiagnostico>[];
    return [
      const TituloDeSecao('🩺 DIAGNÓSTICO E "MANTER O ORBION VIVO"'),
      if (itens == null)
        const Card(child: Padding(padding: EdgeInsets.all(20), child: Center(child: Text('Verificando...'))))
      else ...[
        if (pendentes.isEmpty)
          const Padding(
            padding: EdgeInsets.only(bottom: 10),
            child: LinhaInfo(
              icone: Icons.verified,
              corDoIcone: Cores.verde,
              titulo: 'Tudo certo',
              subtitulo: 'Nenhum ajuste pendente neste aparelho.',
            ),
          ),
        ...pendentes.map(_itemPendente),
        if (emOrdem.isNotEmpty) _cartaoEmOrdem(emOrdem),
      ],
      Align(
        alignment: Alignment.centerRight,
        child: TextButton.icon(
          onPressed: _verificando ? null : _verificar,
          icon: const Icon(Icons.refresh),
          label: Text(_verificando ? 'Verificando...' : 'Verificar de novo'),
        ),
      ),
    ];
  }

  Future<void> _executar(String acao) async {
    if (acao == 'ABRIR_APPS') {
      widget.irPara(1);
      return;
    }
    if (acao == 'ABRIR_REGISTROS') {
      widget.irPara(2);
      return;
    }
    final msg = await c.acao(acao);
    if (msg != null && mounted) mostrarAviso(context, msg);
  }

  /// Item ainda pendente: cartão com o(s) botão(ões) que resolvem e, quando o
  /// app não consegue conferir sozinho, o "Já fiz".
  Widget _itemPendente(ItemDiagnostico i) {
    final (cor, icone) = switch (i.gravidade) {
      Gravidade.grave => (Cores.vermelho, Icons.error),
      Gravidade.atencao => (Cores.amarelo, Icons.warning_amber),
      _ => (Cores.ciano, Icons.lightbulb_outline),
    };
    final temAcao = i.acao != 'NENHUMA' && i.botao.isNotEmpty;
    final temAcao2 = i.acao2 != 'NENHUMA' && i.botao2.isNotEmpty;
    return CartaoAviso(
      icone: icone,
      cor: cor,
      titulo: i.titulo,
      texto: i.situacao,
      botao: temAcao ? i.botao : null,
      aoTocarBotao: temAcao ? () => _executar(i.acao) : null,
      botaoExtra: temAcao2 ? i.botao2 : null,
      aoTocarExtra: temAcao2 ? () => _executar(i.acao2) : null,
      botaoSecundario: i.dispensar.isNotEmpty ? i.dispensar : null,
      iconeSecundario: Icons.check,
      aoTocarSecundario: i.dispensar.isEmpty
          ? null
          : () async {
              await c.confirmar(i.id);
              await _verificar();
              if (mounted) mostrarAviso(context, 'Pronto. Esse aviso não aparece mais.');
            },
    );
  }

  /// Tudo o que já está resolvido fica recolhido num cartão só.
  Widget _cartaoEmOrdem(List<ItemDiagnostico> itens) {
    final algumConfirmado = itens.any((i) => i.confirmadoPelaPessoa);
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 6, 8, 6),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.check_circle, color: Cores.verde),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    '${itens.length} ${itens.length == 1 ? 'item em ordem' : 'itens em ordem'}',
                    style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 15),
                  ),
                ),
                TextButton(
                  onPressed: () => setState(() => _mostrarEmOrdem = !_mostrarEmOrdem),
                  child: Text(_mostrarEmOrdem ? 'Ocultar' : 'Ver'),
                ),
              ],
            ),
            if (_mostrarEmOrdem) ...[
              for (final i in itens)
                Padding(
                  padding: const EdgeInsets.fromLTRB(36, 4, 8, 6),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(i.titulo, style: const TextStyle(fontWeight: FontWeight.w600)),
                      Text(i.situacao, style: const TextStyle(color: Cores.textoFraco, fontSize: 12.5, height: 1.3)),
                    ],
                  ),
                ),
              if (algumConfirmado)
                Padding(
                  padding: const EdgeInsets.only(left: 26),
                  child: TextButton(
                    onPressed: () async {
                      await c.desfazerConfirmacoes();
                      await _verificar();
                    },
                    child: const Text('Mostrar de novo os avisos que marquei como feitos'),
                  ),
                ),
            ],
          ],
        ),
      ),
    );
  }

  // ------------------------------------------------------------------
  // Rota inteligente
  // ------------------------------------------------------------------

  List<Widget> _secaoRotaInteligente(Config cfg) {
    final r = _rotas;
    return [
      const TituloDeSecao('🧠 ROTA INTELIGENTE'),
      LinhaChave(
        icone: Icons.alt_route,
        titulo: 'Rota inteligente',
        subtitulo: cfg.rotaInteligente
            ? 'Ligada: quem recusa o Tor vai por fora, sozinho'
            : 'Desligada: tudo pelo Tor, menos o que você escolher',
        valor: cfg.rotaInteligente,
        aoMudar: (v) => _salvarRotas({'rotaInteligente': v}),
        explicacao: 'Bancos, gov.br, WhatsApp e redes sociais recusam conexões da rede Tor — e neles você já entra com seu nome, '
            'então o Tor não esconderia quem você é. Com a rota inteligente, esses apps e sites vão por fora do Tor, e todo o resto '
            'continua pelo Tor. Abaixo você escolhe quais categorias.',
      ),
      if (cfg.rotaInteligente) ...[
        const SizedBox(height: 8),
        if (r == null)
          const Card(child: Padding(padding: EdgeInsets.all(20), child: Center(child: Text('Carregando categorias...'))))
        else
          for (final cat in r.categorias) _linhaCategoria(cat, cfg),
        const SizedBox(height: 8),
        LinhaChave(
          icone: Icons.auto_fix_high,
          titulo: 'Desviar sozinho quem recusa o Tor',
          subtitulo: cfg.desvioAutomatico ? 'Ligado: sites que falham pelo Tor vão por fora' : 'Desligado: só as categorias e a sua lista',
          valor: cfg.desvioAutomatico,
          aoMudar: (v) => _salvarRotas({'desvioAutomatico': v}),
          explicacao: 'Se um endereço falhar 3 vezes seguidas pela rede Tor, o Orbion para de insistir por um tempo que vai dobrando '
              '(30 s, 1 min, 2 min... até 10 min) — isso poupa bateria e evita que um app teimoso sobrecarregue o Tor. Com esta chave ligada, '
              'um SITE nessa situação vai por fora do Tor (só com HTTPS) até a VPN ser desligada. Você vê e desfaz logo abaixo.',
        ),
        if (r != null && r.desviadosAuto.isNotEmpty) _cartaoDesviadosAuto(r),
        if (r != null && r.pausados.isNotEmpty) _cartaoPausados(r),
      ],
      const SizedBox(height: 8),
      _cartaoSites(cfg),
      const SizedBox(height: 8),
      const LinhaInfo(
        icone: Icons.verified_user,
        corDoIcone: Cores.verde,
        titulo: 'O que continua protegido',
        subtitulo: 'Mesmo no que vai por fora do Tor',
        explicacao: '• Apps e sites desviados continuam com a criptografia deles (HTTPS; de ponta a ponta nos mensageiros).\n'
            '• Endereço sem criptografia (http://) nunca sai por fora: vai pelo Tor.\n'
            '• Os nomes dos sites desviados são consultados por DNS criptografado.\n'
            '• Rastreadores continuam bloqueados também nos sites desviados.\n'
            '• O Orbion não esconde do Android nem dos apps que é uma VPN: a separação é feita às claras, pelo próprio Android.',
      ),
      const SizedBox(height: 8),
      const LinhaInfo(
        icone: Icons.lan,
        titulo: 'IPv6 tratado automaticamente',
        subtitulo: 'Quem passa pelo túnel usa IPv4. Quem está fora usa IPv6 à vontade.',
        explicacao: 'O tráfego IPv6 é capturado pelo túnel — então não vaza — e recusado na hora, o que faz o aplicativo tentar por IPv4 em milissegundos, sem você perceber.',
      ),
    ];
  }

  Widget _linhaCategoria(CategoriaRota cat, Config cfg) {
    final aberta = _categoriasAbertas.contains(cat.id);
    final apps = cat.apps;
    final textoApps = apps.isEmpty
        ? 'Nenhum app desta categoria neste celular (os sites continuam valendo).'
        : 'Neste celular: ${apps.take(5).join(', ')}${apps.length > 5 ? ' e mais ${apps.length - 5}' : ''}.';
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: () => setState(() => aberta ? _categoriasAbertas.remove(cat.id) : _categoriasAbertas.add(cat.id)),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 10, 8, 10),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(cat.titulo, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 14.5)),
                        const SizedBox(height: 2),
                        Text(cat.resumo, style: const TextStyle(color: Cores.textoFraco, fontSize: 12.5)),
                      ],
                    ),
                  ),
                  Switch(
                    value: cat.ligada,
                    onChanged: (v) {
                      final desligadas = {...cfg.categoriasDesligadas};
                      if (v) {
                        desligadas.remove(cat.id);
                      } else {
                        desligadas.add(cat.id);
                      }
                      _salvarRotas({'categoriasDesligadas': desligadas.toList()});
                    },
                  ),
                ],
              ),
              const SizedBox(height: 4),
              Text(textoApps, style: TextStyle(color: cat.ligada ? Cores.amarelo : Cores.textoFraco, fontSize: 12)),
              if (aberta) ...[
                const SizedBox(height: 6),
                Text(
                  '${cat.porque}\n\n${cat.ligada ? 'Ligada: esses apps e ${cat.dominios} endereços de site vão por fora do Tor.' : 'Desligada: tudo isso passa pela rede Tor.'}',
                  style: const TextStyle(color: Cores.textoFraco, fontSize: 12.5, height: 1.4),
                ),
              ] else
                const Text('Toque para ver o motivo', style: TextStyle(color: Cores.textoFraco, fontSize: 11)),
            ],
          ),
        ),
      ),
    );
  }

  Widget _cartaoDesviadosAuto(ResumoRotas r) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(children: [
              Icon(Icons.auto_fix_high, color: Cores.amarelo),
              SizedBox(width: 12),
              Expanded(child: Text('Desviados sozinhos nesta conexão', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 15))),
            ]),
            const SizedBox(height: 6),
            const Text(
              'Estes sites recusaram a rede Tor e estão indo por fora até você desligar a VPN. Fixe os que você usa sempre, ou mande de volta para o Tor.',
              style: TextStyle(color: Cores.textoFraco, fontSize: 12.5, height: 1.4),
            ),
            for (final d in r.desviadosAuto.take(12))
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        d.categoria == null ? d.dominio : '${d.dominio}  (${d.categoria})',
                        style: const TextStyle(fontSize: 13.5),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    TextButton(
                      onPressed: () async {
                        await c.rotaDeDominio(d.dominio, 'direto');
                        await _carregarRotas();
                      },
                      child: const Text('Fixar'),
                    ),
                    TextButton(
                      onPressed: () async {
                        await c.rotaDeDominio(d.dominio, 'tor');
                        await _carregarRotas();
                      },
                      child: const Text('Pelo Tor'),
                    ),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _cartaoPausados(ResumoRotas r) {
    final linhas = r.pausados.take(6).map((p) => '• ${p.destino} — ${p.falhas} falha(s), nova tentativa em ${p.segundos}s').join('\n');
    return CartaoAviso(
      icone: Icons.pause_circle_outline,
      cor: Cores.ciano,
      titulo: 'Em pausa agora (${r.pausados.length})',
      texto: 'Endereços que falharam seguidas vezes pela rede Tor. O Orbion espera antes de tentar de novo, para não gastar bateria nem sobrecarregar o Tor:\n\n$linhas',
    );
  }

  Widget _cartaoSites(Config cfg) {
    Future<void> adicionar(String rota) async {
      final d = normalizarDominio(_novoSite.text);
      if (d == null) {
        mostrarAviso(context, 'Digite só o endereço do site. Exemplo: meubanco.com.br');
        return;
      }
      await c.rotaDeDominio(d, rota);
      _novoSite.clear();
      await _carregarRotas();
      if (mounted) mostrarAviso(context, rota == 'direto' ? '$d vai sempre por fora do Tor.' : '$d vai sempre pela rede Tor.');
    }

    Widget chip(String d, Color cor) => InputChip(
          label: Text(d, style: TextStyle(color: cor, fontSize: 12.5)),
          side: BorderSide(color: cor.withValues(alpha: 0.6)),
          backgroundColor: cor.withValues(alpha: 0.08),
          onDeleted: () async {
            await c.rotaDeDominio(d, 'padrao');
            await _carregarRotas();
          },
        );

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(children: [
              Icon(Icons.language, color: Cores.ciano),
              SizedBox(width: 12),
              Expanded(child: Text('Sites por sua conta', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 15))),
            ]),
            const SizedBox(height: 6),
            const Text(
              'Um site não abre pelo Tor? Mande ele sempre por fora. Quer um site sempre pelo Tor, mesmo sendo de uma categoria desviada? Também dá. '
              'Vale para o navegador e para os apps que passam pelo Tor. Dica: em Registros, toque num endereço para fazer isso direto.',
              style: TextStyle(color: Cores.textoFraco, fontSize: 12.5, height: 1.4),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: _novoSite,
              keyboardType: TextInputType.url,
              decoration: InputDecoration(
                hintText: 'Ex.: meubanco.com.br',
                filled: true,
                fillColor: Colors.black,
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
              ),
            ),
            const SizedBox(height: 8),
            Wrap(
              spacing: 10,
              runSpacing: 6,
              children: [
                FilledButton(onPressed: () => adicionar('direto'), child: const Text('Sempre por fora')),
                OutlinedButton(onPressed: () => adicionar('tor'), child: const Text('Sempre pelo Tor')),
              ],
            ),
            if (cfg.dominiosDiretos.isNotEmpty) ...[
              const SizedBox(height: 12),
              const Text('Sempre por fora do Tor:', style: TextStyle(fontWeight: FontWeight.w600)),
              const SizedBox(height: 6),
              Wrap(spacing: 6, runSpacing: 6, children: [for (final d in cfg.dominiosDiretos) chip(d, Cores.amarelo)]),
            ],
            if (cfg.dominiosSempreTor.isNotEmpty) ...[
              const SizedBox(height: 12),
              const Text('Sempre pela rede Tor:', style: TextStyle(fontWeight: FontWeight.w600)),
              const SizedBox(height: 6),
              Wrap(spacing: 6, runSpacing: 6, children: [for (final d in cfg.dominiosSempreTor) chip(d, Cores.verde)]),
            ],
          ],
        ),
      ),
    );
  }

  // ------------------------------------------------------------------
  // Demais seções
  // ------------------------------------------------------------------

  List<Widget> _secaoCriptografia(Config cfg) {
    final minutos = (_rotacaoArrastando ?? cfg.intervaloRotacaoSegundos / 60).round().clamp(1, 60).toInt();
    return [
      const TituloDeSecao('🔐 CRIPTOGRAFIA'),
      const LinhaInfo(
        icone: Icons.enhanced_encryption,
        titulo: 'Criptografia da rede Tor',
        subtitulo: 'Três camadas, uma por relé, mais TLS entre eles',
        fim: Selo('SEMPRE ATIVO', Cores.verde),
      ),
      const SizedBox(height: 8),
      Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Row(children: [
                Icon(Icons.autorenew, color: Cores.ciano),
                SizedBox(width: 14),
                Text('Rotação de identidade', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 15)),
              ]),
              const SizedBox(height: 4),
              Padding(
                padding: const EdgeInsets.only(left: 38),
                child: Text('Troca o caminho de saída a cada $minutos minutos', style: const TextStyle(color: Cores.textoFraco)),
              ),
              Slider(
                value: minutos.toDouble(),
                min: 1,
                max: 60,
                divisions: 59,
                label: '$minutos min',
                onChanged: (v) => setState(() => _rotacaoArrastando = v),
                onChangeEnd: (v) async {
                  await _salvar({'intervaloRotacaoSegundos': v.round() * 60});
                  if (mounted) setState(() => _rotacaoArrastando = null);
                },
              ),
              const Padding(
                padding: EdgeInsets.only(left: 8),
                child: Text(
                  'Trocar com mais frequência dificulta ligar suas atividades entre si. Em compensação, cada caminho novo leva alguns segundos para ser montado.\n\nPara trocar agora, use o botão Nova identidade no Painel.',
                  style: TextStyle(color: Cores.textoFraco, fontSize: 12.5, height: 1.4),
                ),
              ),
            ],
          ),
        ),
      ),
    ];
  }

  List<Widget> _secaoSeguranca(Config cfg) => [
        const TituloDeSecao('🛡️ SEGURANÇA'),
        const LinhaInfo(
          icone: Icons.radar,
          titulo: 'Proteção contra varredura de portas',
          subtitulo: 'Atrás do Tor não existe conexão de entrada para escanear',
          fim: Selo('SEMPRE ATIVO', Cores.verde),
        ),
        const SizedBox(height: 8),
        LinhaChave(
          icone: Icons.block,
          titulo: 'Bloqueio de rastreadores',
          subtitulo: cfg.bloquearRastreadores
              ? 'Ativo: barra domínios de anúncio e rastreamento'
              : 'Desligado: os rastreadores passam normalmente',
          valor: cfg.bloquearRastreadores,
          aoMudar: (v) => _salvar({'bloquearRastreadores': v}),
          explicacao: 'A lista é curta e conservadora de propósito: só domínios que existem apenas para rastrear. Domínio que também serve login ou conteúdo fica de fora, porque bloquear quebraria o aplicativo.',
        ),
        const SizedBox(height: 8),
        LinhaChave(
          icone: Icons.power_settings_new,
          titulo: 'Kill Switch',
          subtitulo: cfg.killSwitch ? 'Ativo: bloqueia o que depende do Tor se ele cair' : 'Desligado: o tráfego sai sem proteção se o Tor cair',
          valor: cfg.killSwitch,
          aoMudar: (v) => _salvar({'killSwitch': v}),
          explicacao: 'Com ele ligado, o túnel também segura o tráfego enquanto o Tor ainda está conectando — nada sai sem proteção nem no começo. '
              'O que vai por fora pela rota inteligente (banco, WhatsApp...) continua funcionando nesse meio-tempo.',
        ),
      ];

  List<Widget> _secaoAutomatico(Config cfg) => [
        const TituloDeSecao('⚡ AUTOMÁTICO'),
        const LinhaInfo(
          icone: Icons.restart_alt,
          corDoIcone: Cores.verde,
          titulo: 'Religa sozinha',
          subtitulo: 'Se o sistema do celular fechar a VPN, ela volta',
          fim: Selo('SEMPRE ATIVO', Cores.verde),
          explicacao: 'A VPN roda separada da tela: fechar o app ou limpar os apps recentes não desliga a proteção. Se o sistema do celular derrubar a VPN, '
              'ela é religada pelo próprio Android, por um vigia que confere a cada 15 minutos e quando você abre o app. O motivo de cada queda fica em Registros → Eventos.',
        ),
        const SizedBox(height: 8),
        LinhaChave(
          icone: Icons.touch_app,
          titulo: 'Conectar ao abrir o app',
          subtitulo: cfg.conectarAoAbrir ? 'Liga sozinho quando você abre o Orbion' : 'Você toca em Conectar manualmente',
          valor: cfg.conectarAoAbrir,
          aoMudar: (v) => c.salvar({'conectarAoAbrir': v}),
        ),
        const SizedBox(height: 8),
        LinhaChave(
          icone: Icons.phone_android,
          titulo: 'Conectar ao ligar o celular',
          subtitulo: cfg.conectarNoBoot ? 'Liga sempre que o celular ligar' : 'Só volta sozinha se estava ligada',
          valor: cfg.conectarNoBoot,
          aoMudar: (v) => c.salvar({'conectarNoBoot': v}),
          explicacao: 'Se a VPN estava ligada quando o celular desligou, ela volta sozinha de qualquer forma. Esta chave faz ela ligar mesmo se estava desligada. '
              'Só funciona depois que você autorizar a VPN ao menos uma vez.',
        ),
        const SizedBox(height: 8),
        LinhaInfo(
          icone: Icons.all_inclusive,
          titulo: 'VPN sempre ativa (Always-on VPN)',
          subtitulo: c.avisos.sempreAtiva == true ? 'Ligada: o Android religa o Orbion sozinho' : 'Configurada nas opções de VPN do Android',
          corDoIcone: c.avisos.sempreAtiva == true ? Cores.verde : Cores.ciano,
          fim: TextButton(onPressed: () => Motor.executarAcao('ABRIR_VPN'), child: const Text('Abrir')),
          explicacao: 'Na tela do Android, toque na engrenagem ao lado de Orbion VPN e ligue "VPN sempre ativa" (Always-on VPN). Se quiser que NADA saia sem a VPN, ligue também "Bloquear conexões sem VPN" (Block connections without VPN).',
        ),
      ];

  List<Widget> _secaoTor(Config cfg) {
    final linhas = linhasDeBridge(_bridges.text);
    final temObfs4 = linhas.any((l) => l.toLowerCase().startsWith('obfs4'));
    return [
      const TituloDeSecao('🧅 REDE TOR'),
      LinhaChave(
        icone: Icons.alt_route,
        titulo: 'Contornar bloqueio automaticamente',
        subtitulo: cfg.contornoAutomatico
            ? 'Ativo: se a entrada normal falhar, tenta Snowflake e obfs4'
            : 'Desligado: só a entrada normal',
        valor: cfg.contornoAutomatico,
        aoMudar: (v) => _salvar({'contornoAutomatico': v}),
        explicacao: 'Algumas redes (empresas, escolas, alguns países) bloqueiam o Tor. Com isto ligado, o Orbion percebe o bloqueio e tenta sozinho os caminhos disfarçados, e lembra o que funcionou para a próxima vez.',
      ),
      const SizedBox(height: 8),
      LinhaInfo(
        icone: Icons.ac_unit,
        titulo: 'Snowflake (voluntários rotativos)',
        subtitulo: _info.snowflakeDisponivel ? 'Disponível neste aparelho' : 'Indisponível nesta versão',
        corDoIcone: _info.snowflakeDisponivel ? Cores.verde : Cores.textoFraco,
      ),
      const SizedBox(height: 8),
      LinhaInfo(
        icone: Icons.masks,
        titulo: 'Ofuscação (obfs4)',
        subtitulo: _info.obfs4Disponivel
            ? 'Disponível  •  ${_info.obfs4Embutidas} bridges públicas embutidas'
            : 'Indisponível nesta versão',
        corDoIcone: _info.obfs4Disponivel ? Cores.verde : Cores.textoFraco,
      ),
      const SizedBox(height: 8),
      LinhaChave(
        icone: Icons.vpn_lock,
        titulo: 'Usar as minhas bridges',
        subtitulo: cfg.usarBridges ? 'Ativo: entra primeiro pelas bridges abaixo' : 'Só ative se o Tor NÃO conectar na sua rede',
        valor: cfg.usarBridges,
        aoMudar: (v) => _salvar({'usarBridges': v}),
        explicacao: 'A maioria das pessoas NÃO precisa disto: o contorno automático já usa bridges públicas. Bridges próprias servem quando as públicas também foram bloqueadas.',
      ),
      if (cfg.usarBridges) ...[
        const SizedBox(height: 8),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('Suas bridges', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 15)),
                const SizedBox(height: 6),
                const Text(
                  'COMO CONSEGUIR AS SUAS — escolha um dos três:\n\n'
                  '1. Acesse bridges.torproject.org, escolha o tipo obfs4, resolva a figurinha e copie as linhas.\n\n'
                  '2. Envie um e-mail para bridges@torproject.org com o assunto: get transport obfs4 (só de Gmail ou Riseup).\n\n'
                  '3. No Telegram, converse com @GetBridgesBot.\n\n'
                  'Depois cole aqui, uma por linha.',
                  style: TextStyle(color: Cores.textoFraco, fontSize: 12.5, height: 1.4),
                ),
                const SizedBox(height: 10),
                TextField(
                  controller: _bridges,
                  maxLines: 6,
                  minLines: 3,
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                  decoration: InputDecoration(
                    hintText: 'Cole aqui. Exemplo do formato:\nobfs4 192.0.2.1:9001 ABC123 cert=XYZ iat-mode=0',
                    filled: true,
                    fillColor: Colors.black,
                    border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
                  ),
                  onChanged: (_) => setState(() {}),
                ),
                const SizedBox(height: 8),
                if (linhas.isEmpty)
                  const Text(
                    '⚠ Nenhuma bridge informada. Enquanto este campo estiver vazio, o Orbion usa as bridges públicas embutidas.',
                    style: TextStyle(color: Cores.amarelo, fontSize: 12.5),
                  )
                else if (temObfs4 && !_info.obfs4Disponivel)
                  const Text(
                    '⚠ Você colou bridges obfs4, mas o obfs4 não está disponível nesta versão. Essas linhas serão ignoradas.',
                    style: TextStyle(color: Cores.vermelho, fontSize: 12.5),
                  )
                else
                  Text('${linhas.length} bridge(s) reconhecida(s).', style: const TextStyle(color: Cores.verde, fontSize: 12.5)),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 10,
                  children: [
                    FilledButton(
                      onPressed: () async {
                        await _salvar({'bridges': _bridges.text});
                        if (context.mounted && !c.status.ligada) mostrarAviso(context, 'Bridges salvas.');
                      },
                      child: const Text('Salvar bridges'),
                    ),
                    TextButton(
                      onPressed: () => Motor.executarAcao('ABRIR_PONTES'),
                      child: const Text('Abrir bridges.torproject.org'),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ],
    ];
  }

  List<Widget> _secaoDns() => const [
        TituloDeSecao('🌐 DNS'),
        LinhaInfo(
          icone: Icons.dns,
          titulo: 'Resolução de nomes pelo Tor',
          subtitulo: 'Nenhuma consulta de DNS sai do aparelho em claro',
          fim: Selo('SEMPRE ATIVO', Cores.verde),
          explicacao: 'Quando um aplicativo pergunta o endereço de um site, o Orbion responde na hora com um número interno, sem consultar ninguém. '
              'O NOME do site segue pela rede Tor, e quem descobre o endereço de verdade é a saída do Tor — o mesmo princípio do SOCKS4a.\n\n'
              'Por isso não existe escolha de servidor de DNS aqui: escolher um servidor externo seria justamente entregar a lista dos sites que você visita. '
              'Só os sites desviados pela rota inteligente são consultados fora do Tor, e por DNS criptografado.',
        ),
      ];

  List<Widget> _secaoSobre() => [
        const TituloDeSecao('ℹ️ SOBRE'),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('⚡ Orbion VPN v${_info.versaoApp}', style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
                const SizedBox(height: 4),
                const Text('Rede Tor + rota inteligente + Kill Switch', style: TextStyle(color: Cores.textoFraco)),
                const Divider(height: 22),
                _linhaSobre('Motor Tor', _info.versaoTor.isEmpty ? '...' : _info.versaoTor),
                _linhaSobre('Aparelho', '${_info.fabricante} ${_info.modelo}'),
                if (_info.marca.isNotEmpty) _linhaSobre('Ajustes para', _info.marca),
                _linhaSobre('Android', _info.android),
                _linhaSobre('Arquitetura', _info.abi),
                const SizedBox(height: 10),
                const Text('Fortaleza Digital Security', style: TextStyle(color: Cores.ciano, fontWeight: FontWeight.w700)),
              ],
            ),
          ),
        ),
      ];

  Widget _linhaSobre(String a, String b) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 3),
        child: Row(
          children: [
            SizedBox(width: 120, child: Text(a, style: const TextStyle(color: Cores.textoFraco))),
            Expanded(child: Text(b)),
          ],
        ),
      );
}
