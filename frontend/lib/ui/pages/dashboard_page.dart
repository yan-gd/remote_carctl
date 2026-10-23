import 'package:flutter/material.dart';

import '../../models/models.dart';
import '../../state/robot_store.dart';
import '../app.dart';
import '../formatters.dart';
import '../widgets/common.dart';

class DashboardPage extends StatelessWidget {
  const DashboardPage({super.key});

  @override
  Widget build(BuildContext context) {
    final app = AppScope.of(context);
    final store = app.robot;
    if (store == null) return const SizedBox.shrink();
    return AnimatedBuilder(
      animation: store,
      builder: (BuildContext context, Widget? _) =>
          _DashboardBody(store: store),
    );
  }
}

class _DashboardBody extends StatelessWidget {
  const _DashboardBody({required this.store});

  final RobotStore store;

  @override
  Widget build(BuildContext context) {
    final status = store.status;
    final info = store.info;
    final compact = MediaQuery.sizeOf(context).width < 620;
    final padding = compact ? 16.0 : 24.0;

    if (status == null) {
      return SingleChildScrollView(
        padding: EdgeInsets.all(padding),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            const PageHeader(title: '机器人总览', subtitle: '等待第一份状态上报…'),
            const SizedBox(height: 16),
            if (store.error != null)
              ErrorPanel(
                message: store.error!,
                onRetry: () => store.refreshNow(),
              )
            else
              const LoadingBlock(label: '正在读取机器人状态…'),
          ],
        ),
      );
    }

    final theme = Theme.of(context);
    final stale =
        store.updatedAt != null &&
        DateTime.now().difference(store.updatedAt!) >
            const Duration(seconds: 5);

    return SingleChildScrollView(
      padding: EdgeInsets.all(padding),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          PageHeader(
            title: '机器人总览',
            subtitle: '自动每秒刷新 · 最近更新 ${formatRelative(store.updatedAt)}',
            actions: <Widget>[
              IconButton(
                tooltip: '立即刷新',
                onPressed: () => store.refreshNow(),
                icon: const Icon(Icons.refresh),
              ),
            ],
          ),
          if (store.error != null) ...<Widget>[
            const SizedBox(height: 12),
            NoticeBanner(
              message: '状态刷新失败：${store.error}',
              tone: theme.colorScheme.error,
              icon: Icons.error_outline,
            ),
          ] else if (stale) ...<Widget>[
            const SizedBox(height: 12),
            const NoticeBanner(
              message: '数据超过 5 秒没有更新，当前展示的可能是旧快照。',
              tone: Colors.orange,
              icon: Icons.hourglass_bottom,
            ),
          ],
          const SizedBox(height: 16),
          SectionCard(
            title: info?.name ?? 'SCOUT MINI',
            subtitle:
                '${info?.model ?? 'SCOUT MINI'} · ${status.robotId.isEmpty ? (info?.robotId ?? '—') : status.robotId}',
            icon: Icons.smart_toy_outlined,
            trailing: Wrap(
              spacing: 8,
              runSpacing: 8,
              alignment: WrapAlignment.end,
              children: <Widget>[
                StatusPill(
                  label: status.online ? '在线' : '离线',
                  color: status.online ? Colors.green : Colors.orange,
                  icon: status.online ? Icons.wifi : Icons.wifi_off,
                ),
                StatusPill(
                  label: status.mqttConnected ? 'MQTT 已连接' : 'MQTT 未连接',
                  color: status.mqttConnected ? Colors.green : Colors.red,
                  icon: Icons.cloud_queue,
                ),
                StatusPill(
                  label: switch (status.chassisConnected) {
                    true => '底盘已连接',
                    false => '底盘无数据',
                    null => '底盘状态未知',
                  },
                  color: switch (status.chassisConnected) {
                    true => Colors.green,
                    false => Colors.orange,
                    null => Colors.blueGrey,
                  },
                  icon: Icons.memory,
                ),
              ],
            ),
            child: Wrap(
              spacing: 28,
              runSpacing: 14,
              children: <Widget>[
                _KeyValue(
                  label: '运动状态',
                  value: motionLabel(status.motionState),
                ),
                _KeyValue(
                  label: '最近心跳',
                  value: formatRelative(status.lastSeenAt),
                ),
                _KeyValue(
                  label: '遥测时间',
                  value: formatRelative(status.telemetryAt),
                ),
                _KeyValue(
                  label: '允许命令',
                  value: info?.commands.isEmpty ?? true
                      ? 'FORWARD / BACKWARD / LEFT / RIGHT / STOP'
                      : info!.commands.join(' / '),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          Wrap(
            spacing: 12,
            runSpacing: 12,
            children: <Widget>[
              MetricCard(
                label: '运动状态',
                value: motionLabel(status.motionState),
                icon: Icons.directions_run,
                tone: status.motionState == 'STOPPED'
                    ? theme.colorScheme.primary
                    : Colors.orange,
                caption: '桥接节点当前输出的命令',
              ),
              MetricCard(
                label: '电池电压',
                value: formatNumber(status.batteryVoltage, unit: 'V'),
                icon: Icons.battery_charging_full,
                tone: Colors.teal,
                caption: '来自底盘 /scout_status',
              ),
              MetricCard(
                label: '线速度',
                value: formatNumber(status.linearVelocity, unit: 'm/s'),
                icon: Icons.speed,
                caption: '底盘实测值，未上报为 —',
              ),
              MetricCard(
                label: '角速度',
                value: formatNumber(status.angularVelocity, unit: 'rad/s'),
                icon: Icons.rotate_right,
                caption: '底盘实测值，未上报为 —',
              ),
            ],
          ),
          const SizedBox(height: 16),
          _DetailsRow(status: status),
          const SizedBox(height: 16),
          const NoticeBanner(
            message: '遥测只在机器人在线且数据新鲜时展示，null 不补值；位置为轮式里程计累计值，会漂移，不是全局定位。',
            icon: Icons.info_outline,
          ),
        ],
      ),
    );
  }
}

class _DetailsRow extends StatelessWidget {
  const _DetailsRow({required this.status});

  final RobotStatus status;

  @override
  Widget build(BuildContext context) {
    final position = _PositionCard(position: status.position);
    final ack = _AckCard(ack: status.lastAck);
    final wide = MediaQuery.sizeOf(context).width >= 860;
    if (!wide) {
      return Column(
        children: <Widget>[position, const SizedBox(height: 16), ack],
      );
    }
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        Expanded(child: position),
        const SizedBox(width: 16),
        Expanded(child: ack),
      ],
    );
  }
}

class _PositionCard extends StatelessWidget {
  const _PositionCard({required this.position});

  final Position? position;

  @override
  Widget build(BuildContext context) {
    return SectionCard(
      title: '里程计位姿',
      subtitle: '轮式里程计，单位：米 / 弧度',
      icon: Icons.my_location,
      child: Column(
        children: <Widget>[
          _DetailRow(
            label: 'X',
            value: formatNumber(position?.x, unit: 'm'),
          ),
          _DetailRow(
            label: 'Y',
            value: formatNumber(position?.y, unit: 'm'),
          ),
          _DetailRow(
            label: '航向角 yaw',
            value: formatNumber(position?.yaw, unit: 'rad'),
          ),
        ],
      ),
    );
  }
}

class _AckCard extends StatelessWidget {
  const _AckCard({required this.ack});

  final AckInfo? ack;

  @override
  Widget build(BuildContext context) {
    final value = ack;
    return SectionCard(
      title: '最近执行回执',
      subtitle: 'APPLIED 表示桥接已写入 /cmd_vel，不代表底盘物理动作',
      icon: Icons.receipt_long_outlined,
      child: value == null
          ? const EmptyState(
              title: '尚未收到执行回执',
              message: '下发控制命令后，小车侧的回执会显示在这里。',
              icon: Icons.receipt_long_outlined,
            )
          : Column(
              children: <Widget>[
                _DetailRow(
                  label: '命令',
                  value: motionLabel(value.command),
                  trailing: value.result == 'APPLIED'
                      ? const StatusPill(
                          label: '已执行',
                          color: Colors.green,
                          icon: Icons.check_circle_outline,
                        )
                      : const StatusPill(
                          label: '已拒绝',
                          color: Colors.orange,
                          icon: Icons.block,
                        ),
                ),
                _DetailRow(label: '序号 seq', value: '${value.seq}'),
                _DetailRow(label: '原因', value: value.reason ?? '—'),
                _DetailRow(
                  label: '收到时间',
                  value: formatDateTime(value.receivedAt),
                ),
              ],
            ),
    );
  }
}

class _KeyValue extends StatelessWidget {
  const _KeyValue({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: <Widget>[
        Text(
          label,
          style: theme.textTheme.bodySmall?.copyWith(
            color: theme.colorScheme.onSurfaceVariant,
          ),
        ),
        const SizedBox(height: 3),
        Text(
          value,
          style: theme.textTheme.bodyMedium?.copyWith(
            fontWeight: FontWeight.w600,
          ),
        ),
      ],
    );
  }
}

class _DetailRow extends StatelessWidget {
  const _DetailRow({required this.label, required this.value, this.trailing});

  final String label;
  final String value;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        children: <Widget>[
          SizedBox(
            width: 96,
            child: Text(
              label,
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ),
          Expanded(
            child: Text(
              value,
              style: theme.textTheme.bodyMedium?.copyWith(
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          ?trailing,
        ],
      ),
    );
  }
}
