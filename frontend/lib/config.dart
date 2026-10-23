/// Compile-time configuration.
///
/// Production builds are served from the same origin as `/api`, so [apiBaseUrl] is
/// empty by default and the browser resolves requests against the page origin.
/// Local development overrides it with
/// `--dart-define=API_BASE_URL=http://127.0.0.1:8080`.
class AppConfig {
  AppConfig._();

  static const String apiBaseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: '',
  );

  static const Duration requestTimeout = Duration(seconds: 12);
  static const Duration pollInterval = Duration(seconds: 1);
  static const Duration holdPeriod = Duration(milliseconds: 200);

  /// Returns a human readable problem when the configured API origin is unsafe.
  ///
  /// Browsers cannot protect a bearer token sent over plain HTTP on the public
  /// internet, so only loopback HTTP is accepted outside of same-origin HTTPS.
  static String? get apiBaseUrlProblem {
    final value = apiBaseUrl.trim();
    if (value.isEmpty) return null;
    final uri = Uri.tryParse(value);
    if (uri == null || !uri.hasScheme || !uri.hasAuthority) {
      return 'API_BASE_URL 不是有效的地址：$value';
    }
    if (uri.scheme != 'https' && uri.scheme != 'http') {
      return 'API_BASE_URL 只支持 http(s)：$value';
    }
    final loopback =
        uri.host == 'localhost' || uri.host == '127.0.0.1' || uri.host == '::1';
    if (uri.scheme == 'http' && !loopback) {
      return '生产环境必须通过 HTTPS 访问后端，当前 API_BASE_URL 为明文 HTTP。';
    }
    if (uri.path.isNotEmpty && uri.path != '/') {
      return 'API_BASE_URL 不能带路径，请只填写协议、主机和端口。';
    }
    return null;
  }
}
