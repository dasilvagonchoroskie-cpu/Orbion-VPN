import 'package:flutter/material.dart';

/// Paleta do Orbion: fundo escuro com neon (mesma identidade da versão 5).
class Cores {
  static const fundo = Color(0xFF0B0F14);
  static const superficie = Color(0xFF121821);
  static const cartao = Color(0xFF161E29);
  static const borda = Color(0xFF243041);
  static const ciano = Color(0xFF00E5FF);
  static const magenta = Color(0xFFFF2BD6);
  static const roxo = Color(0xFFBC13FE);
  static const verde = Color(0xFF00FF88);
  static const amarelo = Color(0xFFFFC233);
  static const vermelho = Color(0xFFFF3B30);
  static const texto = Color(0xFFE8EEF5);
  static const textoFraco = Color(0xFF8A99AD);
}

ThemeData temaOrbion() {
  final esquema = const ColorScheme.dark(
    primary: Cores.ciano,
    onPrimary: Color(0xFF00222A),
    secondary: Cores.magenta,
    onSecondary: Colors.black,
    surface: Cores.superficie,
    onSurface: Cores.texto,
    error: Cores.vermelho,
  );
  return ThemeData(
    useMaterial3: true,
    brightness: Brightness.dark,
    colorScheme: esquema,
    scaffoldBackgroundColor: Cores.fundo,
    cardTheme: const CardThemeData(
      color: Cores.cartao,
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.all(Radius.circular(16)),
        side: BorderSide(color: Cores.borda),
      ),
    ),
    appBarTheme: const AppBarTheme(
      backgroundColor: Cores.fundo,
      foregroundColor: Cores.texto,
      elevation: 0,
      centerTitle: false,
    ),
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: Cores.superficie,
      indicatorColor: Cores.ciano.withValues(alpha: 0.16),
      labelTextStyle: WidgetStateProperty.resolveWith(
        (s) => TextStyle(
          fontSize: 12,
          fontWeight: s.contains(WidgetState.selected) ? FontWeight.w700 : FontWeight.w500,
          color: s.contains(WidgetState.selected) ? Cores.ciano : Cores.textoFraco,
        ),
      ),
      iconTheme: WidgetStateProperty.resolveWith(
        (s) => IconThemeData(color: s.contains(WidgetState.selected) ? Cores.ciano : Cores.textoFraco),
      ),
    ),
    switchTheme: SwitchThemeData(
      thumbColor: WidgetStateProperty.resolveWith(
        (s) => s.contains(WidgetState.selected) ? Cores.ciano : Cores.textoFraco,
      ),
      trackColor: WidgetStateProperty.resolveWith(
        (s) => s.contains(WidgetState.selected) ? Cores.ciano.withValues(alpha: 0.35) : Cores.borda,
      ),
    ),
    snackBarTheme: const SnackBarThemeData(
      backgroundColor: Cores.cartao,
      contentTextStyle: TextStyle(color: Cores.texto),
      behavior: SnackBarBehavior.floating,
    ),
    dividerColor: Cores.borda,
  );
}
