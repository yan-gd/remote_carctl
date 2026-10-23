import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../config.dart';
import '../models/inspection.dart';
import '../models/models.dart';
import 'api_exception.dart';

/// Thin, typed wrapper around the exp3 REST API.
///
/// Security notes:
/// * the JWT stays in memory and is sent only in the `Authorization` header;
/// * it is never written to a URL, localStorage or a log;
/// * error messages from the backend are surfaced verbatim (they never contain
///   credentials), while transport failures get a generic message.
class ApiClient {
  ApiClient({String? baseUrl, http.Client? httpClient, Duration? timeout})
    : _baseUrl = _normalizeBase(baseUrl ?? AppConfig.apiBaseUrl),
      _client = httpClient ?? http.Client(),
      _timeout = timeout ?? AppConfig.requestTimeout;

  final String _baseUrl;
  final http.Client _client;
  final Duration _timeout;

  /// Called once per session when an authenticated request returns HTTP 401.
  void Function()? onUnauthorized;

  String? _token;
  bool _unauthorizedNotified = false;

  set token(String? value) {
    _token = value;
    _unauthorizedNotified = false;
  }

  /// `POST /api/v1/auth/login`, then keeps the returned JWT in memory.
  Future<LoginResult> login(String username, String password) async {
    final data = _asMap(
      await _request(
        'POST',
        '/api/v1/auth/login',
        body: <String, dynamic>{'username': username, 'password': password},
        authenticated: false,
      ),
    );
    final token = data['token'];
    if (token is! String || token.isEmpty) {
      throw const ApiException(500, 'BAD_RESPONSE', '登录响应缺少令牌。');
    }
    _token = token;
    _unauthorizedNotified = false;
    final rawUser = data['user'];
    final user = rawUser is Map<String, dynamic>
        ? UserView.fromJson(rawUser)
        : await me();
    return LoginResult(token: token, user: user);
  }

  Future<UserView> me() async =>
      UserView.fromJson(_asMap(await _request('GET', '/api/v1/auth/me')));

  Future<RobotInfo> robotInfo() async =>
      RobotInfo.fromJson(_asMap(await _request('GET', '/api/v1/robot')));

  Future<RobotStatus> robotStatus() async => RobotStatus.fromJson(
    _asMap(await _request('GET', '/api/v1/robot/status')),
  );

  Future<ControlResult> control(String command) async => ControlResult.fromJson(
    _asMap(
      await _request(
        'POST',
        '/api/v1/robot/control',
        body: <String, dynamic>{'command': command},
      ),
    ),
  );

  Future<PageResult<AlarmItem>> alarms({
    int page = 1,
    int size = 20,
    String? level,
    String? status,
  }) async => PageResult<AlarmItem>.fromJson(
    _asMap(
      await _request(
        'GET',
        '/api/v1/alarms',
        query: _query(<String, Object?>{
          'page': page,
          'size': size,
          'level': level,
          'status': status,
        }),
      ),
    ),
    AlarmItem.fromJson,
  );

  Future<AlarmItem> acknowledgeAlarm(int id) async => AlarmItem.fromJson(
    _asMap(await _request('PATCH', '/api/v1/alarms/$id/ack')),
  );

  Future<PageResult<PatrolRoute>> routes({
    int page = 1,
    int size = 20,
  }) async => PageResult<PatrolRoute>.fromJson(
    _asMap(
      await _request(
        'GET',
        '/api/v1/routes',
        query: _query(<String, Object?>{'page': page, 'size': size}),
      ),
    ),
    PatrolRoute.fromJson,
  );

  Future<PatrolRoute> route(int id) async => PatrolRoute.fromJson(
    _asMap(await _request('GET', '/api/v1/routes/$id')),
  );

  Future<PatrolRoute> createRoute(RouteInput input) async =>
      PatrolRoute.fromJson(
        _asMap(
          await _request('POST', '/api/v1/routes', body: input.toJson()),
        ),
      );

  Future<PatrolRoute> updateRoute(int id, RouteInput input) async =>
      PatrolRoute.fromJson(
        _asMap(
          await _request('PUT', '/api/v1/routes/$id', body: input.toJson()),
        ),
      );

  Future<void> deleteRoute(int id) async {
    await _request('DELETE', '/api/v1/routes/$id');
  }

  Future<PageResult<PatrolTask>> tasks({
    int page = 1,
    int size = 20,
    String? status,
  }) async => PageResult<PatrolTask>.fromJson(
    _asMap(
      await _request(
        'GET',
        '/api/v1/tasks',
        query: _query(<String, Object?>{
          'page': page,
          'size': size,
          'status': status,
        }),
      ),
    ),
    PatrolTask.fromJson,
  );

  Future<PatrolTask> task(int id) async => PatrolTask.fromJson(
    _asMap(await _request('GET', '/api/v1/tasks/$id')),
  );

  Future<PatrolTask> createTask(TaskInput input) async => PatrolTask.fromJson(
    _asMap(await _request('POST', '/api/v1/tasks', body: input.toJson())),
  );

  Future<PatrolTask> updateTask(int id, TaskInput input) async =>
      PatrolTask.fromJson(
        _asMap(
          await _request('PUT', '/api/v1/tasks/$id', body: input.toJson()),
        ),
      );

  Future<void> deleteTask(int id) async {
    await _request('DELETE', '/api/v1/tasks/$id');
  }

  Future<PageResult<ControlLogItem>> controlLogs({
    int page = 1,
    int size = 20,
    String? operation,
  }) async => PageResult<ControlLogItem>.fromJson(
    _asMap(
      await _request(
        'GET',
        '/api/v1/control-logs',
        query: _query(<String, Object?>{
          'page': page,
          'size': size,
          'operation': operation,
        }),
      ),
    ),
    ControlLogItem.fromJson,
  );

  Future<Object?> _request(
    String method,
    String path, {
    Object? body,
    Map<String, String>? query,
    bool authenticated = true,
  }) async {
    final headers = <String, String>{'Accept': 'application/json'};
    if (body != null) {
      headers['Content-Type'] = 'application/json; charset=utf-8';
    }
    final token = _token;
    if (authenticated && token != null) {
      headers['Authorization'] = 'Bearer $token';
    }

    var uri = Uri.parse(_baseUrl.isEmpty ? path : '$_baseUrl$path');
    if (query != null && query.isNotEmpty) {
      uri = uri.replace(
        queryParameters: <String, String>{...uri.queryParameters, ...query},
      );
    }

    http.Response response;
    try {
      final request = http.Request(method, uri)..headers.addAll(headers);
      if (body != null) {
        request.body = jsonEncode(body);
      }
      response = await _client
          .send(request)
          .then(http.Response.fromStream)
          .timeout(_timeout);
    } on TimeoutException {
      throw const ApiException(0, 'NETWORK_TIMEOUT', '请求超时，请检查网络后重试。');
    } on http.ClientException {
      throw const ApiException(0, 'NETWORK_ERROR', '无法连接服务器，请检查网络后重试。');
    } catch (_) {
      throw const ApiException(0, 'NETWORK_ERROR', '网络请求失败，请稍后重试。');
    }

    Object? decoded;
    try {
      decoded = response.bodyBytes.isEmpty
          ? null
          : jsonDecode(utf8.decode(response.bodyBytes));
    } on FormatException {
      decoded = null;
    }
    final envelope = decoded is Map<String, dynamic> ? decoded : null;

    if (response.statusCode < 200 || response.statusCode >= 300) {
      if (response.statusCode == 401 && authenticated) {
        _notifyUnauthorized();
      }
      final rawCode = envelope?['code'];
      final rawMessage = envelope?['message'];
      throw ApiException(
        response.statusCode,
        rawCode is String && rawCode.isNotEmpty
            ? rawCode
            : 'HTTP_${response.statusCode}',
        rawMessage is String && rawMessage.isNotEmpty
            ? rawMessage
            : _fallbackMessage(response.statusCode),
      );
    }

    if (envelope == null) {
      throw const ApiException(500, 'BAD_RESPONSE', '服务器返回的数据格式不正确。');
    }
    final code = envelope['code'];
    if (code is String && code != 'SUCCESS') {
      final message = envelope['message'];
      throw ApiException(
        500,
        code,
        message is String && message.isNotEmpty ? message : '操作失败。',
      );
    }
    return envelope['data'];
  }

  void _notifyUnauthorized() {
    if (_unauthorizedNotified) return;
    _unauthorizedNotified = true;
    onUnauthorized?.call();
  }

  static Map<String, dynamic> _asMap(Object? data) {
    if (data is Map<String, dynamic>) return data;
    throw const ApiException(500, 'BAD_RESPONSE', '服务器返回的数据格式不正确。');
  }

  static Map<String, String> _query(Map<String, Object?> values) {
    final result = <String, String>{};
    values.forEach((key, value) {
      if (value != null) result[key] = '$value';
    });
    return result;
  }

  static String _normalizeBase(String value) {
    var base = value.trim();
    while (base.endsWith('/')) {
      base = base.substring(0, base.length - 1);
    }
    return base;
  }

  static String _fallbackMessage(int status) => switch (status) {
    400 => '请求参数不正确。',
    401 => '登录状态无效或已过期。',
    403 => '当前账号无权执行此操作。',
    404 => '请求的资源不存在。',
    409 => '当前状态下无法执行该操作。',
    415 => '请求格式不受支持。',
    429 => '请求过于频繁，请稍后再试。',
    503 => '服务暂时不可用，请稍后重试。',
    _ => '请求失败（HTTP $status）。',
  };
}
