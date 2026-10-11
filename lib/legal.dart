import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'controlador.dart';
import 'motor.dart';
import 'tema.dart';
import 'util.dart';
import 'widgets/comuns.dart';

/// Política de Privacidade, Termos de Uso, o aviso de uso da VPN (exigido
/// pela Play Store) e as licenças dos componentes de código aberto.

const String assetPrivacidade = 'assets/legal/privacidade.md';
const String assetTermos = 'assets/legal/termos.md';

/// Texto do aviso em destaque sobre o uso da VPN (VpnService). A Play Store
/// exige que ele apareça dentro do app, separado de outros avisos, e que a
/// pessoa concorde antes de a VPN ler qualquer tráfego.
const List<String> textoDoAvisoVpn = [
  'Para proteger a sua conexão, o Orbion cria uma VPN neste aparelho, usando o recurso VpnService do Android. Com a VPN ligada:',
  '• O tráfego de internet dos aplicativos passa pelo Orbion, que o envia criptografado pela rede Tor.',
  '• Para escolher o caminho de cada conexão e bloquear rastreadores, o Orbion lê, aqui no aparelho, o endereço de destino de cada conexão (o nome do site e a porta). O conteúdo das suas mensagens, páginas e arquivos não é lido.',
  '• Bancos, governo, mensageiros e outros serviços que recusam o Tor vão direto, por fora do Tor (rota inteligente). Você pode mudar isso quando quiser.',
  '• Nada disso é enviado para nós nem guardado fora do aparelho. A lista de sites recentes fica só na memória e é apagada quando a VPN desliga.',
  'Ao conectar pela primeira vez, o Android vai pedir a sua permissão para criar a VPN.',
];

/// Registra as licenças do motor (Tor, túnel, Snowflake...) na tela padrão de
/// licenças do Flutter. Os textos entram no app na hora da compilação.
void registrarLicencas() {
  LicenseRegistry.addLicense(() async* {
    try {
      final manifesto = await AssetManifest.loadFromAssetBundle(rootBundle);
      final arquivos = manifesto
          .listAssets()
          .where((a) => a.startsWith('assets/licencas/') && a.endsWith('.txt') && !a.endsWith('LEIA-ME.txt'))
          .toList()
        ..sort();
      for (final a in arquivos) {
        yield LicenseEntryWithLineBreaks([nomeDoComponente(a)], await rootBundle.loadString(a));
      }
    } catch (_) {
      // Sem as licenças do motor, a tela mostra ao menos as do Flutter.
    }
  });
}

void abrirLicencas(BuildContext context, String versao) {
  showLicensePage(
    context: context,
    applicationName: 'Orbion VPN',
    applicationVersion: versao,
    applicationLegalese: '© 2026 Fortaleza Digital Security\nInclui Tor, hev-socks5-tunnel, lyrebird, Snowflake e outros '
        'componentes de código aberto, cada um com a sua licença.',
  );
}

void abrirDocumento(BuildContext context, {required String titulo, required String asset, required String acaoOnline}) {
  Navigator.of(context).push(MaterialPageRoute(
    builder: (_) => TelaDocumento(titulo: titulo, asset: asset, acaoOnline: acaoOnline),
  ));
}

void abrirPrivacidade(BuildContext context) =>
    abrirDocumento(context, titulo: 'Política de Privacidade', asset: assetPrivacidade, acaoOnline: 'ABRIR_PRIVACIDADE_ONLINE');

void abrirTermos(BuildContext context) =>
    abrirDocumento(context, titulo: 'Termos de Uso', asset: assetTermos, acaoOnline: 'ABRIR_TERMOS_ONLINE');

void abrirAvisoVpn(BuildContext context) {
  Navigator.of(context).push(MaterialPageRoute(
    builder: (_) => Scaffold(
      appBar: AppBar(title: const Text('Como o Orbion usa a VPN')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
        children: [for (final p in textoDoAvisoVpn) _paragrafoDoAviso(p)],
      ),
    ),
  ));
}

Widget _paragrafoDoAviso(String p) => Padding(
      padding: EdgeInsets.only(bottom: 12, left: p.startsWith('•') ? 4 : 0),
      child: Text(p, style: const TextStyle(height: 1.45, fontSize: 15)),
    );

// ---------------------------------------------------------------------------
// Leitor dos documentos (funciona sem internet)
// ---------------------------------------------------------------------------

class TelaDocumento extends StatelessWidget {
  final String titulo;
  final String asset;
  final String acaoOnline;
  const TelaDocumento({super.key, required this.titulo, required this.asset, required this.acaoOnline});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(titulo)),
      body: FutureBuilder<String>(
        future: rootBundle.loadString(asset),
        builder: (context, snap) {
          if (snap.hasError) {
            return const Center(child: Text('Não foi possível abrir o documento.', style: TextStyle(color: Cores.textoFraco)));
          }
          if (!snap.hasData) return const Center(child: CircularProgressIndicator());
          final blocos = lerDocumento(snap.data!);
          return SelectionArea(
            child: ListView(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
              children: [
                for (final b in blocos) _bloco(b),
                const SizedBox(height: 12),
                Align(
                  alignment: Alignment.centerLeft,
                  child: TextButton.icon(
                    onPressed: () => Motor.executarAcao(acaoOnline),
                    icon: const Icon(Icons.open_in_new, size: 18),
                    label: const Text('Ver a versão publicada no site'),
                  ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _bloco(BlocoDeTexto b) => switch (b.tipo) {
        TipoDeBloco.titulo => Padding(
            padding: const EdgeInsets.only(top: 4, bottom: 8),
            child: Text(b.texto, style: const TextStyle(fontSize: 21, fontWeight: FontWeight.w900, height: 1.25)),
          ),
        TipoDeBloco.secao => Padding(
            padding: const EdgeInsets.only(top: 18, bottom: 6),
            child: Text(b.texto, style: const TextStyle(fontSize: 16.5, fontWeight: FontWeight.w800, color: Cores.ciano)),
          ),
        TipoDeBloco.subsecao => Padding(
            padding: const EdgeInsets.only(top: 10, bottom: 4),
            child: Text(b.texto, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
          ),
        TipoDeBloco.item => Padding(
            padding: const EdgeInsets.only(left: 4, bottom: 6),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Padding(
                  padding: EdgeInsets.only(top: 1, right: 8),
                  child: Text('•', style: TextStyle(color: Cores.ciano, fontSize: 15)),
                ),
                Expanded(child: Text(b.texto, style: const TextStyle(height: 1.45, fontSize: 14.5))),
              ],
            ),
          ),
        TipoDeBloco.paragrafo => Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: Text(b.texto, style: const TextStyle(height: 1.45, fontSize: 14.5)),
          ),
      };
}

// ---------------------------------------------------------------------------
// Boas-vindas: aceite dos termos e aviso de uso da VPN (primeiro uso)
// ---------------------------------------------------------------------------

class TelaBoasVindas extends StatefulWidget {
  final ControladorOrbion c;
  const TelaBoasVindas({super.key, required this.c});

  @override
  State<TelaBoasVindas> createState() => _TelaBoasVindasState();
}

class _TelaBoasVindasState extends State<TelaBoasVindas> {
  bool _aceitouTermos = false;
  bool _salvando = false;
  bool _recusouVpn = false;

  ControladorOrbion get c => widget.c;

  Future<void> _salvar(Map<String, Object?> m) async {
    setState(() => _salvando = true);
    try {
      await c.salvar(m);
    } catch (_) {
      if (mounted) mostrarAviso(context, 'Não foi possível salvar agora. Tente de novo.');
    } finally {
      if (mounted) setState(() => _salvando = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: c,
      builder: (context, _) {
        final faltamTermos = c.config.termosAceitos < versaoDosTermos;
        return Scaffold(
          body: SafeArea(child: faltamTermos ? _etapaTermos() : _etapaAvisoVpn()),
        );
      },
    );
  }

  Widget _ponto(IconData icone, String titulo, String texto) => Padding(
        padding: const EdgeInsets.only(bottom: 14),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icone, color: Cores.ciano, size: 26),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(titulo, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 15.5)),
                  const SizedBox(height: 3),
                  Text(texto, style: const TextStyle(color: Cores.textoFraco, height: 1.4)),
                ],
              ),
            ),
          ],
        ),
      );

  Widget _etapaTermos() {
    final versaoAntiga = c.config.termosAceitos > 0;
    return ListView(
      padding: const EdgeInsets.fromLTRB(22, 28, 22, 28),
      children: [
        const Icon(Icons.shield, color: Cores.ciano, size: 54),
        const SizedBox(height: 14),
        Text(
          versaoAntiga ? 'Os termos do Orbion mudaram' : 'Bem-vindo ao Orbion VPN',
          textAlign: TextAlign.center,
          style: const TextStyle(fontSize: 23, fontWeight: FontWeight.w900),
        ),
        const SizedBox(height: 6),
        Text(
          versaoAntiga
              ? 'Leia a nova versão e confirme para continuar.'
              : 'Privacidade pela rede Tor, com rota inteligente para o que recusa o Tor.',
          textAlign: TextAlign.center,
          style: const TextStyle(color: Cores.textoFraco, height: 1.4),
        ),
        const SizedBox(height: 26),
        _ponto(Icons.visibility_off, 'Não coletamos seus dados',
            'Sem cadastro, sem anúncios e sem rastreadores. Não temos servidores que recebam o seu tráfego.'),
        _ponto(Icons.phone_android, 'Tudo fica no seu aparelho',
            'Suas configurações e os registros de funcionamento ficam só aqui. Você apaga quando quiser.'),
        _ponto(Icons.alt_route, 'Você no controle',
            'Escolha a qualquer momento o caminho de cada app e de cada site.'),
        const SizedBox(height: 6),
        OutlinedButton.icon(
          onPressed: () => abrirPrivacidade(context),
          icon: const Icon(Icons.privacy_tip_outlined, size: 18),
          label: const Text('Ler a Política de Privacidade'),
        ),
        const SizedBox(height: 8),
        OutlinedButton.icon(
          onPressed: () => abrirTermos(context),
          icon: const Icon(Icons.gavel_outlined, size: 18),
          label: const Text('Ler os Termos de Uso'),
        ),
        const SizedBox(height: 14),
        CheckboxListTile(
          value: _aceitouTermos,
          onChanged: (v) => setState(() => _aceitouTermos = v ?? false),
          controlAffinity: ListTileControlAffinity.leading,
          contentPadding: EdgeInsets.zero,
          title: const Text('Li e aceito os Termos de Uso e a Política de Privacidade.'),
        ),
        const SizedBox(height: 10),
        SizedBox(
          height: 52,
          child: FilledButton(
            onPressed: _aceitouTermos && !_salvando ? () => _salvar({'termosAceitos': versaoDosTermos}) : null,
            child: _salvando
                ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('Continuar', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w800)),
          ),
        ),
      ],
    );
  }

  Widget _etapaAvisoVpn() {
    return ListView(
      padding: const EdgeInsets.fromLTRB(22, 28, 22, 28),
      children: [
        const Icon(Icons.vpn_lock, color: Cores.ciano, size: 52),
        const SizedBox(height: 14),
        const Text(
          'Como o Orbion usa a VPN do Android',
          textAlign: TextAlign.center,
          style: TextStyle(fontSize: 22, fontWeight: FontWeight.w900),
        ),
        const SizedBox(height: 20),
        for (final p in textoDoAvisoVpn) _paragrafoDoAviso(p),
        const SizedBox(height: 10),
        if (_recusouVpn)
          const CartaoAviso(
            icone: Icons.info_outline,
            cor: Cores.amarelo,
            titulo: 'Sem a sua concordância, a VPN não liga',
            texto: 'O Orbion só consegue proteger a sua conexão com a VPN ligada. Você pode concordar quando quiser, '
                'ou fechar o app agora.',
          ),
        SizedBox(
          height: 52,
          child: FilledButton(
            onPressed: _salvando ? null : () => _salvar({'avisoVpnAceito': versaoDoAvisoVpn}),
            child: _salvando
                ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('Concordo e continuar', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w800)),
          ),
        ),
        const SizedBox(height: 8),
        if (!_recusouVpn)
          TextButton(onPressed: () => setState(() => _recusouVpn = true), child: const Text('Agora não'))
        else
          TextButton(onPressed: () => Motor.executarAcao('FECHAR_APP'), child: const Text('Fechar o app')),
      ],
    );
  }
}
