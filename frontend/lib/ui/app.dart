import 'package:flutter/material.dart';

import '../config.dart';
import '../core/api_client.dart';
import '../state/app_state.dart';
import 'pages/login_page.dart';
import 'pages/shell_page.dart';
import 'theme.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const PatrolApp());
}

class PatrolApp extends StatefulWidget {
  const PatrolApp({super.key, this.apiClient});

  /// Test hook: lets widget tests inject an [ApiClient] backed by a mock HTTP
  /// client. Production passes nothing.
  final ApiClient? apiClient;

  @override
  State<PatrolApp> createState() => _PatrolAppState();
}

class _PatrolAppState extends State<PatrolApp> {
  late final AppState _state = AppState(api: widget.apiClient);

  @override
  void dispose() {
    _state.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final configProblem = AppConfig.apiBaseUrlProblem;
    return AppScope(
      state: _state,
      child: MaterialApp(
        title: 'SCOUT 远程巡检控制台',
        debugShowCheckedModeBanner: false,
        theme: buildLightTheme(),
        darkTheme: buildDarkTheme(),
        themeMode: ThemeMode.system,
        home: configProblem != null
            ? ConfigErrorPage(message: configProblem)
            : AnimatedBuilder(
                animation: _state,
                builder: (BuildContext context, Widget? _) =>
                    _state.signedIn ? const MainShell() : const LoginPage(),
              ),
      ),
    );
  }
}

/// Provides the session state to every page.
class AppScope extends InheritedNotifier<AppState> {
  const AppScope({super.key, required AppState state, required super.child})
    : super(notifier: state);

  static AppState of(BuildContext context) {
    final scope = context.dependOnInheritedWidgetOfExactType<AppScope>();
    assert(scope != null, 'AppScope is missing above this widget');
    return scope!.notifier!;
  }
}

class ConfigErrorPage extends StatelessWidget {
  const ConfigErrorPage({super.key, required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 520),
          child: Card(
            child: Padding(
              padding: const EdgeInsets.all(28),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: <Widget>[
                  Icon(
                    Icons.gpp_bad_outlined,
                    size: 44,
                    color: theme.colorScheme.error,
                  ),
                  const SizedBox(height: 16),
                  Text('不安全的访问配置', style: theme.textTheme.titleLarge),
                  const SizedBox(height: 8),
                  Text(
                    message,
                    textAlign: TextAlign.center,
                    style: theme.textTheme.bodyMedium,
                  ),
                  const SizedBox(height: 12),
                  Text(
                    '请使用 HTTPS 域名访问，或仅在开发时通过 --dart-define=API_BASE_URL 指向本机。',
                    textAlign: TextAlign.center,
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
