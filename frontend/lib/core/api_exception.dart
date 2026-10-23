/// A failed backend call.
///
/// [status] is 0 when the request never reached the server (offline, timeout,
/// browser CORS failure). Messages come from the backend, which never echoes
/// request bodies or credentials.
class ApiException implements Exception {
  const ApiException(this.status, this.code, this.message);

  final int status;
  final String code;
  final String message;

  bool get isUnauthorized => status == 401;
  bool get isNetworkFailure => status == 0;

  @override
  String toString() => 'ApiException($status $code): $message';
}
