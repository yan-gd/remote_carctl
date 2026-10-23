import 'dart:async';

import 'package:fake_async/fake_async.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:patrol_console/core/api_exception.dart';
import 'package:patrol_console/core/hold_controller.dart';

void main() {
  test('sends immediately and repeats every period until stopped', () {
    fakeAsync((FakeAsync async) {
      final sent = <String>[];
      final hold = HoldController(
        send: (String command) async => sent.add(command),
        period: const Duration(milliseconds: 200),
      );

      hold.start('FORWARD');
      async.flushMicrotasks();
      expect(sent, <String>['FORWARD']);

      async.elapse(const Duration(milliseconds: 650));
      async.flushMicrotasks();
      expect(sent, <String>['FORWARD', 'FORWARD', 'FORWARD', 'FORWARD']);

      hold.stop();
      async.flushMicrotasks();
      expect(sent.last, 'STOP');
      expect(hold.active, isFalse);

      async.elapse(const Duration(seconds: 1));
      expect(sent.last, 'STOP');
      hold.dispose();
    });
  });

  test('stop waits for an in-flight keepalive before sending STOP', () {
    fakeAsync((FakeAsync async) {
      final gate = Completer<void>();
      final sent = <String>[];
      var first = true;
      final hold = HoldController(
        send: (String command) {
          sent.add(command);
          if (command == 'FORWARD') {
            if (first) {
              first = false;
              return Future<void>.value();
            }
            return gate.future;
          }
          return Future<void>.value();
        },
        period: const Duration(milliseconds: 50),
      );

      hold.start('FORWARD');
      async.flushMicrotasks();
      async.elapse(const Duration(milliseconds: 50));
      async.flushMicrotasks();
      expect(sent, <String>['FORWARD', 'FORWARD']);

      bool? stopped;
      hold.stop().then((bool ok) => stopped = ok);
      async.flushMicrotasks();
      expect(stopped, isNull);
      expect(sent.last, 'FORWARD');

      gate.complete();
      async.flushMicrotasks();
      expect(sent.last, 'STOP');
      expect(stopped, isTrue);
      hold.dispose();
    });
  });

  test('a rejected first command clears the hold and reports the error', () {
    fakeAsync((FakeAsync async) {
      final errors = <ApiExceptionLike>[];
      final hold = HoldController(
        send: (String command) async => throw const ApiException(
          503,
          'MQTT_UNAVAILABLE',
          'MQTT服务不可用，命令未下发',
        ),
        onError: errors.add,
      );

      hold.start('FORWARD');
      async.flushMicrotasks();

      expect(hold.active, isFalse);
      expect(errors, hasLength(1));
      expect(errors.single.code, 'MQTT_UNAVAILABLE');
      expect(errors.single.message, contains('MQTT'));
      hold.dispose();
    });
  });

  test('force stop sends STOP even without an active hold', () {
    fakeAsync((FakeAsync async) {
      final sent = <String>[];
      final hold = HoldController(
        send: (String command) async => sent.add(command),
      );
      bool? result;
      hold.stop(force: true).then((bool ok) => result = ok);
      async.flushMicrotasks();
      expect(sent, <String>['STOP']);
      expect(result, isTrue);
      hold.dispose();
    });
  });

  test('disposed controller ignores new holds', () {
    fakeAsync((FakeAsync async) {
      final sent = <String>[];
      final hold = HoldController(
        send: (String command) async => sent.add(command),
      );
      hold.dispose();
      hold.start('FORWARD');
      async.flushMicrotasks();
      expect(sent, isEmpty);
    });
  });
}
