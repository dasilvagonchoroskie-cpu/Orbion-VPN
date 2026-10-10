import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'controlador.dart';
import 'tema.dart';
import 'telas/apps.dart';
import 'telas/config.dart';
import 'telas/painel.dart';
import 'telas/registros.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
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
        home: const Inicio(),
      );
}

class Inicio extends StatefulWidget {
  const Inicio({super.key});

  @override
  State<Inicio> createState() => _InicioState();
}

class _InicioState extends State<Inicio> with WidgetsBindingObserver {
  final ControladorOrbion _c = ControladorOrbion();
  int _aba = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _c.iniciar();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _c.voltouParaFrente();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _c.dispose();
    super.dispose();
  }

  void _irPara(int aba) => setState(() => _aba = aba);

  @override
  Widget build(BuildContext context) {
    final telas = [
      TelaPainel(c: _c, irPara: _irPara),
      TelaApps(c: _c),
      TelaRegistros(c: _c),
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
