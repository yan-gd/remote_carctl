import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:patrol_console/core/api_client.dart';
import 'package:patrol_console/ui/app.dart';

http.Response _json(Object body, int status) => http.Response(
  jsonEncode(body),
  status,
  headers: <String, String>{'content-type': 'application/json; charset=utf-8'},
);

ApiClient _api(MockClient client) =>
    ApiClient(baseUrl: 'http://api.test', httpClient: client);

void main() {
  testWidgets('login requires a username and a password', (
    WidgetTester tester,
  ) async {
    final api = _api(
      MockClient(
        (http.Request request) async => _json(<String, Object?>{}, 500),
      ),
    );

    await tester.pumpWidget(PatrolApp(apiClient: api));
    await tester.pump();

    await tester.tap(find.widgetWithText(FilledButton, '登录'));
    await tester.pump();

    expect(find.text('请输入用户名'), findsOneWidget);
    expect(find.text('请输入密码'), findsOneWidget);
    expect(find.text('SCOUT 远程巡检控制台'), findsOneWidget);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('a failed login shows the backend message and stays on login', (
    WidgetTester tester,
  ) async {
    final api = _api(
      MockClient(
        (http.Request request) async => _json(<String, Object?>{
          'code': 'LOGIN_FAILED',
          'message': '用户名或密码错误',
        }, 401),
      ),
    );

    await tester.pumpWidget(PatrolApp(apiClient: api));
    await tester.pump();
    await tester.enterText(find.byType(TextFormField).at(0), 'demo_operator');
    await tester.enterText(find.byType(TextFormField).at(1), 'wrong-password');
    await tester.tap(find.widgetWithText(FilledButton, '登录'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));

    expect(find.text('用户名或密码错误'), findsOneWidget);
    expect(find.text('SCOUT 远程巡检控制台'), findsOneWidget);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('a successful login opens the dashboard with live status', (
    WidgetTester tester,
  ) async {
    final client = MockClient((http.Request request) async {
      switch (request.url.path) {
        case '/api/v1/auth/login':
          return _json(<String, Object?>{
            'code': 'SUCCESS',
            'message': 'success',
            'data': <String, Object?>{
              'token': 'jwt-token',
              'tokenType': 'Bearer',
              'expiresIn': 3600,
              'user': <String, Object?>{
                'id': 1,
                'username': 'demo_operator',
                'role': 'OPERATOR',
                'createdAt': '2026-10-23T06:00:00Z',
              },
            },
          }, 200);
        case '/api/v1/robot':
          return _json(<String, Object?>{
            'code': 'SUCCESS',
            'message': 'success',
            'data': <String, Object?>{
              'robotId': 'SCOUT-001',
              'name': 'SCOUT MINI Pro Kit',
              'model': 'SCOUT MINI',
              'commands': <String>[
                'FORWARD',
                'BACKWARD',
                'LEFT',
                'RIGHT',
                'STOP',
              ],
              'online': true,
              'mqttConnected': true,
              'lastSeenAt': '2026-10-23T06:00:00Z',
            },
          }, 200);
        case '/api/v1/robot/status':
          return _json(<String, Object?>{
            'code': 'SUCCESS',
            'message': 'success',
            'data': <String, Object?>{
              'robotId': 'SCOUT-001',
              'online': true,
              'motionState': 'STOPPED',
              'batteryPct': null,
              'batteryVoltage': 25.2,
              'linearVelocity': 0.0,
              'angularVelocity': 0.0,
              'position': <String, Object?>{'x': 1.5, 'y': 2.0, 'yaw': 0.25},
              'mqttConnected': true,
              'chassisConnected': true,
              'lastSeenAt': '2026-10-23T06:00:00Z',
              'telemetryAt': '2026-10-23T06:00:00Z',
              'lastAck': null,
            },
          }, 200);
        default:
          return _json(<String, Object?>{
            'code': 'NOT_FOUND',
            'message': '接口不存在',
          }, 404);
      }
    });
    final api = _api(client);

    await tester.pumpWidget(PatrolApp(apiClient: api));
    await tester.pump();
    await tester.enterText(find.byType(TextFormField).at(0), 'demo_operator');
    await tester.enterText(find.byType(TextFormField).at(1), 'secret-password');
    await tester.tap(find.widgetWithText(FilledButton, '登录'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));
    await tester.pump(const Duration(milliseconds: 200));

    expect(find.text('机器人总览'), findsOneWidget);
    expect(find.text('SCOUT MINI Pro Kit'), findsOneWidget);
    expect(find.text('在线'), findsOneWidget);

    await tester.pumpWidget(const SizedBox.shrink());
  });
}
