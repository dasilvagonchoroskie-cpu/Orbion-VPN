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
  final TextEditingController _bridges = TextEditingController();
  bool _bridgesCarregadas = false;
  double? _rotacaoArrastando;
  int _ultimoRetorno = 0;
  bool _mostrarEmOrdem = false;

  ControladorOrbion get c => widget.c;

  @override
  void initState() {
    super.initState();
    _verificar();
    Motor.infoMotor().then((i) {
      if (mounted) setState(() => _info = i);
    });
  }

  @override
  void dispose() {
    _bridges.dispose();
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

  Future<void> _salvar(Map<String, Object?> m) async {
    await c.salvar(m);
    if (!mounted) return;
    if (c.status.ligada) {
      mostrarAviso(context, 'Vale a partir da próxima conexão.', acao: 'Reconectar agora', aoTocar: () => c.reconectar());
    }
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: c,
      builder: (context, _) {
        final cfg = c.config;
        // Voltou de uma tela do Android (bateria, VPN, Xiaomi...): confere de
        // novo, para o que já foi resolvido sumir sozinho.
        if (c.retornos != _ultimoRetorno) {
          _ultimoRetorno = c.retornos;
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (mounted && !_verificando) _verificar();
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
            ..._secaoCriptografia(cfg),
            ..._secaoSeguranca(cfg),
            ..._secaoCompatibilidade(cfg),
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
          subtitulo: cfg.killSwitch ? 'Ativo: bloqueia tudo se o Tor cair' : 'Desligado: o tráfego sai sem proteção se o Tor cair',
          valor: cfg.killSwitch,
          aoMudar: (v) => _salvar({'killSwitch': v}),
          explicacao: 'Com ele ligado, o túnel também segura o tráfego enquanto o Tor ainda está conectando — nada sai sem proteção nem no começo.',
        ),
      ];

  List<Widget> _secaoCompatibilidade(Config cfg) => [
        const TituloDeSecao('🌍 COMPATIBILIDADE'),
        LinhaChave(
          icone: Icons.alt_route,
          titulo: 'Desvio para serviços que barram o Tor',
          subtitulo: cfg.desvioDireto
              ? 'Ativo: apps bloqueados voltam a funcionar'
              : 'Desligado: Instagram, YouTube e outros não vão funcionar',
          valor: cfg.desvioDireto,
          aoMudar: (v) => _salvar({'desvioDireto': v}),
          explicacao: 'Alguns serviços recusam qualquer conexão vinda da rede Tor — Instagram, YouTube, TikTok, bancos. Com isto ligado, SOMENTE os endereços deles saem por fora do túnel. Todo o resto continua passando pelo Tor.\n\n'
              'O que sai por fora expõe o seu IP real para aquele serviço. A consulta de nomes continua criptografada, então o seu provedor não fica sabendo.',
        ),
        const SizedBox(height: 8),
        const LinhaInfo(
          icone: Icons.lan,
          titulo: 'IPv6 tratado automaticamente',
          subtitulo: 'Quem passa pelo túnel usa IPv4. Quem está fora usa IPv6 à vontade.',
          explicacao: 'O tráfego IPv6 é capturado pelo túnel — então não vaza — e recusado na hora, o que faz o aplicativo tentar por IPv4 em milissegundos, sem você perceber.',
        ),
      ];

  List<Widget> _secaoAutomatico(Config cfg) => [
        const TituloDeSecao('⚡ AUTOMÁTICO'),
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
          subtitulo: cfg.conectarNoBoot ? 'Protege desde a inicialização do aparelho' : 'Só conecta quando você abrir o app',
          valor: cfg.conectarNoBoot,
          aoMudar: (v) => c.salvar({'conectarNoBoot': v}),
          explicacao: 'Só funciona depois que você autorizar a VPN ao menos uma vez. Para proteção total, use também "VPN sempre ativa" abaixo.',
        ),
        const SizedBox(height: 8),
        LinhaInfo(
          icone: Icons.all_inclusive,
          titulo: 'VPN sempre ativa',
          subtitulo: c.avisos.sempreAtiva == true ? 'Ligada: o Android religa o Orbion sozinho' : 'Configurada nas opções de VPN do Android',
          corDoIcone: c.avisos.sempreAtiva == true ? Cores.verde : Cores.ciano,
          fim: TextButton(onPressed: () => Motor.executarAcao('ABRIR_VPN'), child: const Text('Abrir')),
          explicacao: 'Na tela do Android, toque na engrenagem ao lado de Orbion VPN e ligue "VPN sempre ativa". Se quiser que NADA saia sem a VPN, ligue também "Bloquear conexões sem VPN".',
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
          explicacao: 'Quando um aplicativo pergunta o endereço de um site, o Orbion responde na hora com um número interno, sem consultar ninguém. Quem descobre o endereço de verdade é a rede Tor, por dentro.\n\n'
              'Por isso não existe escolha de servidor de DNS aqui: escolher um servidor externo seria justamente entregar a lista dos sites que você visita.',
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
                const Text('Rede Tor + Kill Switch + DNS pelo Tor', style: TextStyle(color: Cores.textoFraco)),
                const Divider(height: 22),
                _linhaSobre('Motor Tor', _info.versaoTor.isEmpty ? '...' : _info.versaoTor),
                _linhaSobre('Aparelho', '${_info.fabricante} ${_info.modelo}'),
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
