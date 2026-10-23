import 'dart:async';

import 'package:flutter/foundation.dart';

import '../config.dart';
import '../core/api_client.dart';
import '../core/api_exception.dart';
import '../models/models.dart';

/// Polls the robot endpoints for the signed-in session.
///
/// The backend deliberately returns `null` for unavailable telemetry, so this
/// store keeps the last successful snapshot but marks it stale on failures;
/// it never fabricates values.
class RobotStore extends ChangeNotifier {
  RobotStore(this._api);

  final ApiClient _api;

  RobotInfo? _info;
  RobotStatus? _status;
  String? _error;
  DateTime? _updatedAt;
  Timer? _timer;
  bool _inFlight = false;
  bool _disposed = false;
  int _ticks = 0;

  RobotInfo? get info => _info;
  RobotStatus? get status => _status;
  String? get error => _error;
  DateTime? get updatedAt => _updatedAt;
  bool get hasData => _status != null;

  void start() {
    if (_disposed) return;
    _timer?.cancel();
    unawaited(_loadInfo());
    unawaited(refreshNow());
    _timer = Timer.periodic(AppConfig.pollInterval, (_) {
      _ticks++;
      unawaited(refreshNow());
      if (_ticks % 30 == 0) unawaited(_loadInfo());
    });
  }

  Future<void> refreshNow() async {
    if (_disposed || _inFlight) return;
    _inFlight = true;
    try {
      final status = await _api.robotStatus();
      if (_disposed) return;
      _status = status;
      _updatedAt = DateTime.now();
      _error = null;
    } on ApiException catch (error) {
      if (_disposed) return;
      _error = error.message;
    } catch (_) {
      if (_disposed) return;
      _error = '状态刷新失败，请稍后重试。';
    } finally {
      _inFlight = false;
      _notify();
    }
  }

  Future<void> _loadInfo() async {
    if (_disposed) return;
    try {
      final info = await _api.robotInfo();
      if (_disposed) return;
      _info = info;
      _notify();
    } on ApiException {
      // Non-fatal: the status call reports connectivity problems.
    }
  }

  Future<ControlResult> sendCommand(String command) => _api.control(command);

  /// Best-effort STOP used on logout; the robot watchdog is the real fallback.
  Future<void> stopMotion() async {
    try {
      await _api.control('STOP');
    } on ApiException {
      // The bridge watchdog stops the chassis when commands stop arriving.
    }
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _timer?.cancel();
    _timer = null;
    super.dispose();
  }
}
