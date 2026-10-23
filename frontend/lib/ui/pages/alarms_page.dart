import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/api_exception.dart';
import '../../models/models.dart';
import '../app.dart';
import '../formatters.dart';
import '../theme.dart';
import '../widgets/common.dart';

class AlarmsPage extends StatefulWidget {
  const AlarmsPage({super.key});

  @override
  State<AlarmsPage> createState() => _AlarmsPageState();
}

class _AlarmsPageState extends State<AlarmsPage> {
  static const int _pageSize = 20;
  static const Map<String, String> _levels = <String, String>{
    '': '全部级别',
    'CRITICAL': '严重',
    'WARNING': '警告',
    'INFO': '提示',
  };
  static const Map<String, String> _statuses = <String, String>{
    '': '全部状态',
    'UNACKNOWLEDGED': '未确认',
    'ACKNOWLEDGED': '已确认',
  };

  final List<AlarmItem> _items = <AlarmItem>[];
  bool _started = false;
  bool _loading = false;
  String? _error;
  int _page = 1;
  int _total = 0;
  String _level = '';
  String _status = '';
  bool _acking = false;

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
      final result = await app.api.alarms(
        page: _page,
        size: _pageSize,
        level: _level.isEmpty ? null : _level,
        status: _status.isEmpty ? null : _status,
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
        _error = '告警加载失败，请稍后重试。';
        _loading = false;
      });
    }
  }

  Future<void> _acknowledge(AlarmItem alarm) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (BuildContext context) => AlertDialog(
        title: const Text('确认告警'),
        content: Text('确认“${alarm.title}”？确认后会记录确认人和时间。'),
        actions: <Widget>[
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('确认'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    final api = AppScope.of(context).api;
    setState(() => _acking = true);
    try {
      await api.acknowledgeAlarm(alarm.id);
      if (!mounted) return;
      _showMessage('告警已确认。');
      await _load();
    } on ApiException catch (error) {
      if (!mounted) return;
      _showMessage(error.message, error: true);
    } finally {
      if (mounted) setState(() => _acking = false);
    }
  }

  void _showMessage(String message, {bool error = false}) {
    if (!mounted) return;
    final messenger = ScaffoldMessenger.of(context);
    messenger.hideCurrentSnackBar();
    messenger.showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: error ? Theme.of(context).colorScheme.error : null,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final app = AppScope.of(context);
    final canAck = app.user?.role == 'MONITOR';
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
              title: '告警',
              subtitle: '设备离线、MQTT 断开、电量过低等告警按状态跳变记录一次。',
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
                    _FilterSelect(
                      label: '级别',
                      value: _level,
                      options: _levels,
                      onChanged: (String value) {
                        setState(() => _level = value);
                        unawaited(_load(page: 1));
                      },
                    ),
                    _FilterSelect(
                      label: '状态',
                      value: _status,
                      options: _statuses,
                      onChanged: (String value) {
                        setState(() => _status = value);
                        unawaited(_load(page: 1));
                      },
                    ),
                    if (canAck)
                      const Text('监控员可确认未处理告警', style: TextStyle(fontSize: 12)),
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
                title: '没有符合条件的告警',
                message: '调整筛选条件，或等待设备状态变化。',
                icon: Icons.notifications_off_outlined,
              )
            else
              ..._items.map(
                (AlarmItem alarm) => Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: _AlarmCard(
                    alarm: alarm,
                    canAck: canAck && !_acking,
                    onAck: () => unawaited(_acknowledge(alarm)),
                  ),
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

class _AlarmCard extends StatelessWidget {
  const _AlarmCard({
    required this.alarm,
    required this.canAck,
    required this.onAck,
  });

  final AlarmItem alarm;
  final bool canAck;
  final VoidCallback onAck;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final levelColor = switch (alarm.level) {
      'CRITICAL' => Colors.red,
      'WARNING' => Colors.orange,
      _ => Colors.blue,
    };
    final icon = switch (alarm.level) {
      'CRITICAL' => Icons.dangerous_outlined,
      'WARNING' => Icons.warning_amber_outlined,
      _ => Icons.info_outline,
    };
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Padding(
                  padding: const EdgeInsets.only(top: 2),
                  child: Icon(icon, color: readableTone(context, levelColor)),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Text(
                        alarm.title,
                        style: theme.textTheme.titleSmall?.copyWith(
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        '${alarm.source} · ${formatDateTime(alarm.occurredAt)}',
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 8),
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: <Widget>[
                    StatusPill(
                      label: levelLabel(alarm.level),
                      color: levelColor,
                    ),
                    StatusPill(
                      label: alarmStatusLabel(alarm.status),
                      color: alarm.unacknowledged
                          ? Colors.orange
                          : Colors.green,
                    ),
                  ],
                ),
              ],
            ),
            if (alarm.description != null &&
                alarm.description!.isNotEmpty) ...<Widget>[
              const SizedBox(height: 10),
              Text(alarm.description!, style: theme.textTheme.bodyMedium),
            ],
            const SizedBox(height: 8),
            Row(
              children: <Widget>[
                if (alarm.acknowledgedAt != null)
                  Expanded(
                    child: Text(
                      '已由 #${alarm.acknowledgedBy ?? '—'} 确认 · '
                      '${formatDateTime(alarm.acknowledgedAt)}',
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    ),
                  )
                else
                  const Spacer(),
                if (canAck && alarm.unacknowledged)
                  TextButton.icon(
                    onPressed: onAck,
                    icon: const Icon(Icons.task_alt, size: 18),
                    label: const Text('确认'),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _FilterSelect extends StatelessWidget {
  const _FilterSelect({
    required this.label,
    required this.value,
    required this.options,
    required this.onChanged,
  });

  final String label;
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
          label,
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
