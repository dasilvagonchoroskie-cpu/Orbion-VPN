import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:fortaleza_licenca/fortaleza_licenca.dart';

import 'controlador.dart';
import 'legal.dart';
import 'tema.dart';
import 'telas/apps.dart';
import 'telas/config.dart';
import 'telas/painel.dart';
import 'telas/registros.dart';

/// Versão mostrada na Central, ao lado da licença deste aparelho.
const versaoOrbion = '6.3.0';

/// Trava pela Central de Licenças. LIGADA por padrão: o Orbion é vendido só
/// no site, então todo APK sai travado. Se um dia ele for para a Play Store
/// (precisa de CNPJ), é só compilar com --dart-define=LICENCA_CENTRAL=false.
const licencaCentral = bool.fromEnvironment('LICENCA_CENTRAL', defaultValue: true);

final _licenca = ControleLicenca(const ConfiguracaoLicenca(
  appId: 'orbion',
  nomeApp: 'Orbion VPN',
  versaoApp: versaoOrbion,
));

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  registrarLicencas();
  SystemChrome.setSystemUIOverlayStyle(const SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    statusBarIconBrightness: Brightness.light,
    systemNavigationBarColor: Cores.superficie,
    systemNavigationBarIconBrightness: Brightness.light,
  ));
  runApp(const OrbionApp());
}

class OrbionApp extends StatelessWidget {
  const OrbionApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Orbion VPN',
        debugShowCheckedModeBanner: false,
        theme: temaOrbion(),
        // No APK de venda direta, o Orbion só abre no aparelho liberado pela
        // Central de Licenças. Fora isso, o portão mostra "Pedir liberação".
        home: licencaCentral
            ? PortaoLicenca(controle: _licenca, icone: Icons.shield, child: const Inicio())
            : const Inicio(),
      );
}

class Inicio extends StatefulWidget {
  const Inicio({super.key});

  @override
  State<Inicio> createState() => _InicioState();
}

/// O que a tela mostra: carregando, boas-vindas (aceite) ou o app.
enum _Fase { carregando, boasVindas, app }

class _InicioState extends State<Inicio> with WidgetsBindingObserver {
  final ControladorOrbion _c = ControladorOrbion();
  int _aba = 0;
  _Fase _fase = _Fase.carregando;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    // Só redesenha esta raiz quando a fase muda (o status muda a cada
    // segundo e cada aba já se redesenha sozinha).
    _c.addListener(_conferirFase);
    _c.iniciar();
  }

  void _conferirFase() {
    final fase = !_c.carregado
        ? _Fase.carregando
        : _c.consentimentoCompleto
            ? _Fase.app
            : _Fase.boasVindas;
    if (fase != _fase && mounted) setState(() => _fase = fase);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _c.retomar();
      _c.voltouParaFrente();
    } else if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.hidden ||
        state == AppLifecycleState.detached) {
      _c.pausar();
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _c.removeListener(_conferirFase);
    _c.dispose();
    super.dispose();
  }

  void _irPara(int aba) => setState(() => _aba = aba);

  @override
  Widget build(BuildContext context) {
    switch (_fase) {
      case _Fase.carregando:
        return const Scaffold(body: Center(child: CircularProgressIndicator()));
      case _Fase.boasVindas:
        return TelaBoasVindas(c: _c);
      case _Fase.app:
        break;
    }
    final telas = [
      TelaPainel(c: _c, irPara: _irPara, visivel: _aba == 0),
      TelaApps(c: _c, irPara: _irPara),
      TelaRegistros(c: _c, visivel: _aba == 2),
      TelaConfig(c: _c, irPara: _irPara),
    ];
    return Scaffold(
      body: SafeArea(child: IndexedStack(index: _aba, children: telas)),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _aba,
        onDestinationSelected: _irPara,
        destinations: const [
          NavigationDestination(icon: Icon(Icons.shield_outlined), selectedIcon: Icon(Icons.shield), label: 'Painel'),
          NavigationDestination(icon: Icon(Icons.apps_outlined), selectedIcon: Icon(Icons.apps), label: 'Apps'),
          NavigationDestination(icon: Icon(Icons.receipt_long_outlined), selectedIcon: Icon(Icons.receipt_long), label: 'Registros'),
          NavigationDestination(icon: Icon(Icons.tune_outlined), selectedIcon: Icon(Icons.tune), label: 'Config'),
        ],
      ),
    );
  }
}
