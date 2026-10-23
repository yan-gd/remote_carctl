import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:patrol_console/core/api_client.dart';
import 'package:patrol_console/core/api_exception.dart';

http.Response _json(Object body, int status) => http.Response(
  jsonEncode(body),
  status,
  headers: <String, String>{'content-type': 'application/json; charset=utf-8'},
);

void main() {
  test('login posts credentials and returns the in-memory token', () async {
    late http.Request captured;
    final client = MockClient((http.Request request) async {
      captured = request;
      return _json(<String, Object?>{
        'code': 'SUCCESS',
        'message': 'success',
        'data': <String, Object?>{
          'token': 'header.payload.signature',
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
    });
    final api = ApiClient(baseUrl: 'http://api.test', httpClient: client);

    final result = await api.login('demo_operator', 'secret-password');

    expect(captured.method, 'POST');
    expect(captured.url.toString(), 'http://api.test/api/v1/auth/login');
    expect(captured.headers['Content-Type'], contains('application/json'));
    expect(captured.headers.containsKey('Authorization'), isFalse);
    final body = jsonDecode(captured.body) as Map<String, dynamic>;
    expect(body['username'], 'demo_operator');
    expect(result.token, 'header.payload.signature');
    expect(result.user.role, 'OPERATOR');
    expect(result.user.canControlMotion, isTrue);
  });

  test('authenticated requests carry the bearer token', () async {
    late http.Request captured;
    final client = MockClient((http.Request request) async {
      captured = request;
      return _json(<String, Object?>{
        'code': 'SUCCESS',
        'message': 'success',
        'data': <String, Object?>{
          'robotId': 'SCOUT-001',
          'online': false,
          'motionState': 'UNKNOWN',
          'batteryPct': null,
          'batteryVoltage': null,
          'linearVelocity': null,
          'angularVelocity': null,
          'position': <String, Object?>{'x': null, 'y': null, 'yaw': null},
          'mqttConnected': true,
          'chassisConnected': null,
          'lastSeenAt': null,
          'telemetryAt': null,
          'lastAck': null,
        },
      }, 200);
    });
    final api = ApiClient(baseUrl: 'http://api.test', httpClient: client);
    api.token = 'jwt';

    final status = await api.robotStatus();

    expect(captured.headers['Authorization'], 'Bearer jwt');
    expect(status.motionState, 'UNKNOWN');
    expect(status.position?.x, isNull);
    expect(status.chassisConnected, isNull);
  });

  test('backend error codes and messages are preserved', () async {
    final client = MockClient(
      (http.Request request) async => _json(<String, Object?>{
        'code': 'ROBOT_OFFLINE',
        'message': '机器人离线，拒绝运动命令',
        'data': null,
      }, 409),
    );
    final api = ApiClient(baseUrl: 'http://api.test', httpClient: client);
    api.token = 'jwt';

    await expectLater(
      api.control('FORWARD'),
      throwsA(
        isA<ApiException>()
            .having((ApiException e) => e.status, 'status', 409)
            .having((ApiException e) => e.code, 'code', 'ROBOT_OFFLINE')
            .having((ApiException e) => e.message, 'message', contains('离线')),
      ),
    );
  });

  test('a 401 on an authenticated call triggers onUnauthorized once', () async {
    var unauthorized = 0;
    final client = MockClient(
      (http.Request request) async => _json(<String, Object?>{
        'code': 'TOKEN_EXPIRED',
        'message': 'Token 已过期',
      }, 401),
    );
    final api = ApiClient(baseUrl: 'http://api.test', httpClient: client)
      ..onUnauthorized = () => unauthorized++;
    api.token = 'expired';

    await expectLater(api.robotStatus(), throwsA(isA<ApiException>()));
    await expectLater(api.robotInfo(), throwsA(isA<ApiException>()));

    expect(unauthorized, 1);
  });

  test(
    'a failed login does not trigger the session-expired callback',
    () async {
      var unauthorized = 0;
      final client = MockClient(
        (http.Request request) async => _json(<String, Object?>{
          'code': 'LOGIN_FAILED',
          'message': '用户名或密码错误',
        }, 401),
      );
      final api = ApiClient(baseUrl: 'http://api.test', httpClient: client)
        ..onUnauthorized = () => unauthorized++;

      await expectLater(
        api.login('demo_operator', 'wrong'),
        throwsA(isA<ApiException>()),
      );
      expect(unauthorized, 0);
    },
  );

  test('alarm queries pass filters and parse a page', () async {
    late http.Request captured;
    final client = MockClient((http.Request request) async {
      captured = request;
      return _json(<String, Object?>{
        'code': 'SUCCESS',
        'message': 'success',
        'data': <String, Object?>{
          'items': <Object?>[
            <String, Object?>{
              'id': 7,
              'title': '小车离线',
              'description': 'LWT',
              'level': 'CRITICAL',
              'status': 'UNACKNOWLEDGED',
              'source': 'SCOUT-001',
              'occurredAt': '2026-10-23T06:00:00Z',
              'acknowledgedBy': null,
              'acknowledgedAt': null,
            },
          ],
          'total': 21,
          'page': 2,
          'size': 20,
        },
      }, 200);
    });
    final api = ApiClient(baseUrl: 'http://api.test', httpClient: client);
    api.token = 'jwt';

    final page = await api.alarms(page: 2, level: 'CRITICAL');

    expect(captured.url.path, '/api/v1/alarms');
    expect(captured.url.queryParameters['page'], '2');
    expect(captured.url.queryParameters['size'], '20');
    expect(captured.url.queryParameters['level'], 'CRITICAL');
    expect(captured.url.queryParameters.containsKey('status'), isFalse);
    expect(page.total, 21);
    expect(page.items.single.title, '小车离线');
    expect(page.hasNext, isFalse);
  });

  test('network failures become status 0 ApiExceptions', () async {
    final client = MockClient(
      (http.Request request) async =>
          throw http.ClientException('connection refused'),
    );
    final api = ApiClient(baseUrl: 'http://api.test', httpClient: client);

    await expectLater(
      api.login('demo_operator', 'secret'),
      throwsA(
        isA<ApiException>().having(
          (ApiException e) => e.isNetworkFailure,
          'isNetworkFailure',
          isTrue,
        ),
      ),
    );
  });
}
