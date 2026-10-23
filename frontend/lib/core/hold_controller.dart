import 'dart:async';

import '../config.dart';
import 'api_exception.dart';

typedef CommandSender = Future<void> Function(String command);

/// "Press and hold" control loop.
///
/// While a motion command is held it is re-sent every [period] (200 ms) so the
/// robot-side watchdog keeps the chassis moving. Releasing the button — or any
/// failure — stops the loop and sends STOP. Requests never overlap: while one
/// keepalive is still in flight the next tick is skipped, so a slow link cannot
/// reorder a late FORWARD after STOP.
class HoldController {
  HoldController({
    required this.send,
    this.period = AppConfig.holdPeriod,
    this.onError,
  });

  final CommandSender send;
  final Duration period;
  final void Function(ApiExceptionLike error)? onError;

  Timer? _timer;
  String? _command;
  Future<void>? _pending;
  bool _disposed = false;

  bool get active => _command != null;
  String? get command => _command;

  /// Starts (or restarts) a hold. The first command is awaited so a rejected
  /// command is reported immediately instead of after 200 ms.
  Future<void> start(String command) async {
    if (_disposed || command == 'STOP') return;
    if (_command == command && _timer != null) return;
    _cancelTimer();
    _command = command;
    final first = send(command);
    _pending = first;
    try {
      await first;
    } catch (error) {
      _command = null;
      onError?.call(ApiExceptionLike.from(error));
      return;
    } finally {
      if (identical(_pending, first)) _pending = null;
    }
    _timer = Timer.periodic(period, (_) {
      unawaited(_tick(command));
    });
  }

  /// Stops the hold. With [force] a STOP is sent even when no hold was active
  /// (the emergency stop button). Returns whether STOP was delivered.
  Future<bool> stop({bool force = false}) async {
    final wasHolding = _command != null;
    _cancelTimer();
    _command = null;
    final pending = _pending;
    if (pending != null) {
      try {
        await pending;
      } catch (_) {
        // A failed keepalive is reported by _tick; STOP is still attempted.
      }
    }
    if (wasHolding || force) {
      try {
        await send('STOP');
        return true;
      } catch (error) {
        onError?.call(ApiExceptionLike.from(error));
        return false;
      }
    }
    return true;
  }

  /// Cancels timers and, if still moving, asks the robot to stop.
  void dispose() {
    if (_disposed) return;
    _disposed = true;
    final wasHolding = _command != null;
    _timer?.cancel();
    _timer = null;
    _command = null;
    if (wasHolding) {
      unawaited(send('STOP').catchError((Object _) {}));
    }
  }

  Future<void> _tick(String command) async {
    if (_disposed || _command != command || _pending != null) return;
    final future = send(command);
    _pending = future;
    try {
      await future;
    } catch (error) {
      _timer?.cancel();
      _timer = null;
      if (_command == command) _command = null;
      onError?.call(ApiExceptionLike.from(error));
    } finally {
      if (identical(_pending, future)) _pending = null;
    }
  }

  void _cancelTimer() {
    _timer?.cancel();
    _timer = null;
  }
}

/// Minimal error shape the UI can display; keeps the control loop testable
/// without pulling in Flutter dependencies.
class ApiExceptionLike {
  const ApiExceptionLike(this.message, {this.status, this.code});

  final String message;
  final int? status;
  final String? code;

  bool get unauthorized => status == 401;

  factory ApiExceptionLike.from(Object error) {
    if (error is ApiException) {
      return ApiExceptionLike(
        error.message,
        status: error.status,
        code: error.code,
      );
    }
    return const ApiExceptionLike('控制命令发送失败，请重试。');
  }
}
