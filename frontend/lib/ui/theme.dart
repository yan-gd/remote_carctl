import 'package:flutter/material.dart';

const Color _seed = Color(0xFF0F766E);

ThemeData buildLightTheme() => _theme(Brightness.light);

ThemeData buildDarkTheme() => _theme(Brightness.dark);

ThemeData _theme(Brightness brightness) {
  final scheme = ColorScheme.fromSeed(seedColor: _seed, brightness: brightness);
  final dark = brightness == Brightness.dark;
  final border = dark ? Colors.white12 : const Color(0xFFE1E8E4);
  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    brightness: brightness,
    fontFamily: 'PatrolSans',
    scaffoldBackgroundColor: dark
        ? const Color(0xFF0B1220)
        : const Color(0xFFF4F7F5),
    appBarTheme: AppBarThemeData(
      centerTitle: false,
      elevation: 0,
      scrolledUnderElevation: 0,
      backgroundColor: dark ? const Color(0xFF0F172A) : const Color(0xFFF4F7F5),
      foregroundColor: scheme.onSurface,
      titleTextStyle: TextStyle(
        fontFamily: 'PatrolSans',
        fontSize: 18,
        fontWeight: FontWeight.w700,
        color: scheme.onSurface,
      ),
    ),
    cardTheme: CardThemeData(
      margin: EdgeInsets.zero,
      elevation: 0,
      color: dark ? const Color(0xFF111A2B) : Colors.white,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: BorderSide(color: border),
      ),
    ),
    inputDecorationTheme: InputDecorationThemeData(
      filled: true,
      fillColor: dark ? const Color(0xFF0B1220) : const Color(0xFFF8FAF9),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: BorderSide(color: border),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: BorderSide(color: border),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: BorderSide(color: scheme.primary, width: 1.6),
      ),
      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
    ),
    dividerTheme: DividerThemeData(color: border, space: 1),
    snackBarTheme: const SnackBarThemeData(behavior: SnackBarBehavior.floating),
    navigationRailTheme: NavigationRailThemeData(
      backgroundColor: dark ? const Color(0xFF0F172A) : Colors.white,
      indicatorColor: scheme.primaryContainer,
      labelType: NavigationRailLabelType.all,
    ),
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: dark ? const Color(0xFF0F172A) : Colors.white,
      indicatorColor: scheme.primaryContainer,
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: const Size(0, 48),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
    ),
  );
}

/// Lightens a semantic colour in dark mode so text stays readable.
Color readableTone(BuildContext context, Color color) {
  if (Theme.of(context).brightness != Brightness.dark) return color;
  return Color.lerp(color, Colors.white, 0.35) ?? color;
}
