import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/api_exception.dart';
import '../../models/models.dart';
import '../app.dart';
import '../formatters.dart';
import '../theme.dart';
import '../widgets/common.dart';

class LogsPage extends StatefulWidget {
  const LogsPage({super.key});

  @override
  State<LogsPage> createState() => _LogsPageState();
}

class _LogsPageState extends State<LogsPage> {
  static const int _pageSize = 20;
  static const Map<String, String> _operations = <String, String>{
    '': '全部操作',
    'ROBOT_CONTROL': '机器人控制',
    'LOGIN': '登录',
    'REGISTER': '注册',
    'ROUTE_CREATE': '创建路线',
    'ROUTE_UPDATE': '修改路线',
    'ROUTE_DELETE': '删除路线',
    'TASK_CREATE': '创建任务',
    'TASK_UPDATE': '修改任务',
    'TASK_DELETE': '删除任务',
    'ALARM_ACK': '确认告警',
    'BUSINESS_FAILURE': '业务失败',
  };

  final List<ControlLogItem> _items = <ControlLogItem>[];
  bool _started = false;
  bool _loading = false;
  String? _error;
  int _page = 1;
  int _total = 0;
  String _operation = '';

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_started) return;
    _started = true;
    unawaited(_load(page: 1));
  }

  Future<void> _load({int? page}) async {
    if (!mounted) return;
    final app = AppScope.of(context);
    setState(() {
      _loading = true;
      _error = null;
      if (page != null) _page = page;
    });
    try {
      final result = await app.api.controlLogs(
        page: _page,
        size: _pageSize,
        operation: _operation.isEmpty ? null : _operation,
      );
      if (!mounted) return;
      setState(() {
        _items
          ..clear()
          ..addAll(result.items);
        _total = result.total;
        _loading = false;
      });
    } on ApiException catch (error) {
      if (!mounted) return;
      setState(() {
        _error = error.message;
        _loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _error = '日志加载失败，请稍后重试。';
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final app = AppScope.of(context);
    final role = app.user?.role;
    final ownOnly = role == 'OPERATOR' || role == 'MONITOR';
    final compact = MediaQuery.sizeOf(context).width < 620;
    final padding = compact ? 16.0 : 24.0;
    return RefreshIndicator(
      onRefresh: () => _load(),
      child: SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: EdgeInsets.all(padding),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            PageHeader(
              title: '操作日志',
              subtitle: '登录、路线/任务变更、告警确认和机器人控制都会留下审计记录，不含密码或 Token。',
              actions: <Widget>[
                IconButton(
                  tooltip: '刷新',
                  onPressed: _loading ? null : () => _load(),
                  icon: const Icon(Icons.refresh),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Card(
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 16,
                  vertical: 10,
                ),
                child: Wrap(
                  spacing: 20,
                  runSpacing: 8,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: <Widget>[
                    _OperationSelect(
                      value: _operation,
                      options: _operations,
                      onChanged: (String value) {
                        setState(() => _operation = value);
                        unawaited(_load(page: 1));
                      },
                    ),
                    Text(
                      ownOnly ? '当前角色仅显示本人日志' : '当前角色可查看全部日志',
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                ),
              ),
            ),
            if (_loading) ...<Widget>[
              const SizedBox(height: 8),
              const LinearProgressIndicator(minHeight: 2),
            ],
            if (_error != null) ...<Widget>[
              const SizedBox(height: 12),
              ErrorPanel(message: _error!, onRetry: () => _load()),
            ],
            const SizedBox(height: 12),
            if (_items.isEmpty && !_loading)
              const EmptyState(
                title: '没有操作日志',
                message: '调整筛选条件，或先执行一次路线、任务、告警或机器人操作。',
                icon: Icons.receipt_long_outlined,
              )
            else
              ..._items.map(
                (ControlLogItem item) => Padding(
                  padding: const EdgeInsets.only(bottom: 10),
                  child: _LogCard(item: item),
                ),
              ),
            if (_items.isNotEmpty)
              Pager(
                page: _page,
                total: _total,
                busy: _loading,
                onPrevious: _page > 1 ? () => _load(page: _page - 1) : null,
                onNext: _page * _pageSize < _total
                    ? () => _load(page: _page + 1)
                    : null,
              ),
          ],
        ),
      ),
    );
  }
}

class _LogCard extends StatelessWidget {
  const _LogCard({required this.item});

  final ControlLogItem item;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final color = item.failed ? Colors.red : Colors.green;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            CircleAvatar(
              radius: 18,
              backgroundColor: readableTone(
                context,
                color,
              ).withValues(alpha: 0.12),
              child: Icon(
                item.failed ? Icons.error_outline : Icons.check,
                size: 18,
                color: readableTone(context, color),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Expanded(
                        child: Text(
                          '${operationLabel(item.operation)} · ${item.operation}',
                          style: theme.textTheme.titleSmall?.copyWith(
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      StatusPill(
                        label: logResultLabel(item.result),
                        color: color,
                      ),
                    ],
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '${item.actorName} · ${formatDateTime(item.occurredAt)}',
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text('目标：${item.target}', style: theme.textTheme.bodySmall),
                  if (item.error != null && item.error!.isNotEmpty) ...<Widget>[
                    const SizedBox(height: 4),
                    Text(
                      '错误码：${item.error}',
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: theme.colorScheme.error,
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _OperationSelect extends StatelessWidget {
  const _OperationSelect({
    required this.value,
    required this.options,
    required this.onChanged,
  });

  final String value;
  final Map<String, String> options;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: <Widget>[
        Text(
          '操作',
          style: theme.textTheme.bodySmall?.copyWith(
            color: theme.colorScheme.onSurfaceVariant,
          ),
        ),
        const SizedBox(width: 6),
        DropdownButtonHideUnderline(
          child: DropdownButton<String>(
            value: value,
            isDense: true,
            borderRadius: BorderRadius.circular(12),
            items: options.entries
                .map(
                  (MapEntry<String, String> entry) => DropdownMenuItem<String>(
                    value: entry.key,
                    child: Text(entry.value),
                  ),
                )
                .toList(growable: false),
            onChanged: (String? selected) {
              if (selected != null) onChanged(selected);
            },
          ),
        ),
      ],
    );
  }
}
