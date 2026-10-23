import 'package:flutter/material.dart';

import '../../core/api_exception.dart';
import '../app.dart';

class LoginPage extends StatefulWidget {
  const LoginPage({super.key});

  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  final GlobalKey<FormState> _formKey = GlobalKey<FormState>();
  final TextEditingController _username = TextEditingController();
  final TextEditingController _password = TextEditingController();
  bool _obscure = true;
  bool _submitting = false;
  String? _error;

  @override
  void dispose() {
    _username.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_submitting) return;
    if (!(_formKey.currentState?.validate() ?? false)) return;
    final app = AppScope.of(context);
    setState(() {
      _submitting = true;
      _error = null;
    });
    try {
      await app.login(_username.text.trim(), _password.text);
    } on ApiException catch (error) {
      if (mounted) setState(() => _error = error.message);
    } catch (_) {
      if (mounted) setState(() => _error = '登录失败，请稍后重试。');
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final app = AppScope.of(context);
    final notice = app.sessionNotice;
    return Scaffold(
      body: DecoratedBox(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: theme.brightness == Brightness.dark
                ? const <Color>[Color(0xFF0B1220), Color(0xFF102A2A)]
                : const <Color>[Color(0xFFF4F7F5), Color(0xFFE4F1EC)],
          ),
        ),
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 430),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: <Widget>[
                  _BrandHeader(theme: theme),
                  const SizedBox(height: 24),
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(26),
                      child: AutofillGroup(
                        child: Form(
                          key: _formKey,
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: <Widget>[
                              Text(
                                '登录',
                                style: theme.textTheme.titleLarge?.copyWith(
                                  fontWeight: FontWeight.w700,
                                ),
                              ),
                              const SizedBox(height: 4),
                              Text(
                                '使用后端账号登录，不是 MQTT 的 robot 账号。',
                                style: theme.textTheme.bodySmall?.copyWith(
                                  color: theme.colorScheme.onSurfaceVariant,
                                ),
                              ),
                              if (notice != null) ...<Widget>[
                                const SizedBox(height: 16),
                                _InlineMessage(
                                  message: notice,
                                  color: theme.colorScheme.tertiary,
                                  icon: Icons.lock_clock_outlined,
                                ),
                              ],
                              if (_error != null) ...<Widget>[
                                const SizedBox(height: 16),
                                _InlineMessage(
                                  message: _error!,
                                  color: theme.colorScheme.error,
                                  icon: Icons.error_outline,
                                ),
                              ],
                              const SizedBox(height: 20),
                              TextFormField(
                                controller: _username,
                                enabled: !_submitting,
                                autofillHints: const <String>[
                                  AutofillHints.username,
                                ],
                                textInputAction: TextInputAction.next,
                                decoration: const InputDecoration(
                                  labelText: '用户名',
                                  prefixIcon: Icon(Icons.person_outline),
                                ),
                                validator: (String? value) =>
                                    value == null || value.trim().isEmpty
                                    ? '请输入用户名'
                                    : null,
                              ),
                              const SizedBox(height: 14),
                              TextFormField(
                                controller: _password,
                                enabled: !_submitting,
                                obscureText: _obscure,
                                autofillHints: const <String>[
                                  AutofillHints.password,
                                ],
                                textInputAction: TextInputAction.done,
                                onFieldSubmitted: (_) => _submit(),
                                decoration: InputDecoration(
                                  labelText: '密码',
                                  prefixIcon: const Icon(Icons.lock_outline),
                                  suffixIcon: IconButton(
                                    tooltip: _obscure ? '显示密码' : '隐藏密码',
                                    onPressed: _submitting
                                        ? null
                                        : () => setState(
                                            () => _obscure = !_obscure,
                                          ),
                                    icon: Icon(
                                      _obscure
                                          ? Icons.visibility_outlined
                                          : Icons.visibility_off_outlined,
                                    ),
                                  ),
                                ),
                                validator: (String? value) =>
                                    value == null || value.isEmpty
                                    ? '请输入密码'
                                    : null,
                              ),
                              const SizedBox(height: 22),
                              FilledButton(
                                onPressed: _submitting ? null : _submit,
                                child: _submitting
                                    ? const SizedBox(
                                        width: 20,
                                        height: 20,
                                        child: CircularProgressIndicator(
                                          strokeWidth: 2.2,
                                          color: Colors.white,
                                        ),
                                      )
                                    : const Text('登录'),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(height: 16),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: <Widget>[
                      Icon(
                        Icons.shield_outlined,
                        size: 14,
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                      const SizedBox(width: 6),
                      Flexible(
                        child: Text(
                          '令牌仅保存在当前页面内存，刷新后需重新登录；生产环境必须使用 HTTPS。',
                          textAlign: TextAlign.center,
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ),
                    ],
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

class _BrandHeader extends StatelessWidget {
  const _BrandHeader({required this.theme});

  final ThemeData theme;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: <Widget>[
        Container(
          width: 64,
          height: 64,
          decoration: BoxDecoration(
            color: theme.colorScheme.primary,
            borderRadius: BorderRadius.circular(18),
          ),
          child: Icon(
            Icons.precision_manufacturing_outlined,
            size: 34,
            color: theme.colorScheme.onPrimary,
          ),
        ),
        const SizedBox(height: 14),
        Text(
          'SCOUT 远程巡检控制台',
          style: theme.textTheme.headlineSmall?.copyWith(
            fontWeight: FontWeight.w700,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          'MQTT ↔ ROS 机器人控制 · exp3',
          style: theme.textTheme.bodySmall?.copyWith(
            color: theme.colorScheme.onSurfaceVariant,
          ),
        ),
      ],
    );
  }
}

class _InlineMessage extends StatelessWidget {
  const _InlineMessage({
    required this.message,
    required this.color,
    required this.icon,
  });

  final String message;
  final Color color;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: color.withValues(alpha: 0.25)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Icon(icon, size: 16, color: color),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              message,
              style: Theme.of(
                context,
              ).textTheme.bodySmall?.copyWith(color: color),
            ),
          ),
        ],
      ),
    );
  }
}
