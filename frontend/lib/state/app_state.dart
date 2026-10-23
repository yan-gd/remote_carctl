import 'package:flutter/foundation.dart';

import '../core/api_client.dart';
import '../models/models.dart';
import 'robot_store.dart';

/// Session state: the signed-in user, the JWT inside [ApiClient] and the robot
/// poller. The token lives only in memory; a page reload signs the user out.
class AppState extends ChangeNotifier {
  AppState({ApiClient? api}) : api = api ?? ApiClient() {
    this.api.onUnauthorized = _handleUnauthorized;
  }

  final ApiClient api;

  UserView? _user;
  RobotStore? _robot;
  bool _busy = false;
  String? _sessionNotice;
  bool _disposed = false;

  UserView? get user => _user;
  RobotStore? get robot => _robot;
  bool get signedIn => _user != null;
  bool get busy => _busy;
  String? get sessionNotice => _sessionNotice;
  bool get canControlMotion => _user?.canControlMotion ?? false;
  bool get canStop => _user?.canStop ?? false;

  Future<void> login(String username, String password) async {
    if (_disposed || _busy) return;
    _busy = true;
    _sessionNotice = null;
    _notify();
    try {
      final result = await api.login(username, password);
      if (_disposed) return;
      _user = result.user;
      _robot = RobotStore(api)..start();
    } catch (_) {
      api.token = null;
      rethrow;
    } finally {
      _busy = false;
      _notify();
    }
  }

  /// Clears the session. While the token is still valid a STOP is attempted
  /// before it is discarded, so signing out never leaves the robot moving.
  Future<void> logout() async {
    final store = _robot;
    final hadControl = canStop;
    _robot = null;
    _user = null;
    _sessionNotice = null;
    _notify();
    if (store != null) {
      if (hadControl) await store.stopMotion();
      store.dispose();
    }
    api.token = null;
  }

  void _handleUnauthorized() {
    if (_disposed || _user == null) return;
    _robot?.dispose();
    _robot = null;
    _user = null;
    api.token = null;
    _sessionNotice = '登录状态已过期，请重新登录。';
    _notify();
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _robot?.dispose();
    _robot = null;
    super.dispose();
  }
}
