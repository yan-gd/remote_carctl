import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/api_exception.dart';
import '../../core/hold_controller.dart';
import '../../state/app_state.dart';
import '../../state/robot_store.dart';
import '../app.dart';
import '../formatters.dart';
import '../theme.dart';
import '../widgets/common.dart';

class ControlPage extends StatefulWidget {
  const ControlPage({super.key});

  @override
  State<ControlPage> createState() => _ControlPageState();
}

class _ControlPageState extends State<ControlPage> with WidgetsBindingObserver {
  AppState? _app;
  HoldController? _hold;
  String? _active;
  bool _stopping = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final app = AppScope.of(context);
    if (_app == app) return;
    _hold?.dispose();
    _app = app;
    _hold = HoldController(
      send: (String command) {
        final store = app.robot;
        if (store == null) {
          return Future<void>.error(
            const ApiException(401, 'UNAUTHORIZED', '会话已结束，请重新登录。'),
          );
        }
        return store.sendCommand(command);
      },
      onError: _handleError,
    );
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _hold?.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) return;
    // Hiding the tab or losing focus must not leave the robot driving.
    if (_active != null) {
      setState(() => _active = null);
      unawaited(_hold?.stop());
    }
  }

  void _handleError(ApiExceptionLike error) {
    if (!mounted) return;
    if (_active != null) setState(() => _active = null);
    _showMessage(error.message, error: true);
  }

  Future<void> _start(String command) async {
    final app = _app;
    final store = app?.robot;
    if (app == null || store == null || _hold == null) return;
    if (_active != null) return;
    if (!app.canControlMotion) {
      _showMessage('当前角色只能执行急停（STOP）。', error: true);
      return;
    }
    if (store.status?.online != true) {
      _showMessage('机器人不在线，运动命令会被后端拒绝。', error: true);
      return;
    }
    setState(() => _active = command);
    await _hold!.start(command);
    if (!mounted) return;
    if (!_hold!.active && _active == command) {
      setState(() => _active = null);
    }
  }

  Future<void> _end() async {
    if (_active == null) return;
    setState(() => _active = null);
    await _hold?.stop();
  }

  Future<void> _emergencyStop() async {
    if (_stopping || _hold == null) return;
    setState(() {
      _stopping = true;
      _active = null;
    });
    final ok = await _hold!.stop(force: true);
    if (!mounted) return;
    setState(() => _stopping = false);
    _showMessage(
      ok ? '已发送 STOP 急停命令。' : 'STOP 发送失败，小车侧看门狗会在 0.6 秒内自动停车。',
      error: !ok,
    );
  }

  void _showMessage(String message, {bool error = false}) {
    if (!mounted) return;
    final theme = Theme.of(context);
    final messenger = ScaffoldMessenger.of(context);
    messenger.hideCurrentSnackBar();
    messenger.showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: error ? theme.colorScheme.error : null,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final app = AppScope.of(context);
    final store = app.robot;
    if (store == null) return const SizedBox.shrink();
    return AnimatedBuilder(
      animation: store,
      builder: (BuildContext context, Widget? _) =>
          _buildBody(context, app, store),
    );
  }

  Widget _buildBody(BuildContext context, AppState app, RobotStore store) {
    final theme = Theme.of(context);
    final status = store.status;
    final compact = MediaQuery.sizeOf(context).width < 620;
    final padding = compact ? 16.0 : 24.0;
    final online = status?.online == true;
    final canMove = app.canControlMotion && online;
    final canStop = app.canStop;
    final ack = status?.lastAck;

    return SingleChildScrollView(
      padding: EdgeInsets.all(padding),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          PageHeader(
            title: '远程控制',
            subtitle: '按住方向键行驶，松开即停；每条命令都会经后端鉴权和 MQTT 下发。',
            actions: <Widget>[
              if (status != null)
                StatusPill(
                  label: online ? '在线' : '离线',
                  color: online ? Colors.green : Colors.orange,
                  icon: online ? Icons.wifi : Icons.wifi_off,
                ),
            ],
          ),
          if (!canStop) ...<Widget>[
            const SizedBox(height: 12),
            const NoticeBanner(
              message: '当前角色为只读，不能下发控制命令。',
              tone: Colors.orange,
              icon: Icons.visibility_outlined,
            ),
          ] else if (!app.canControlMotion) ...<Widget>[
            const SizedBox(height: 12),
            const NoticeBanner(
              message: '监控员只能执行急停（STOP），运动按钮已禁用。',
              tone: Colors.blue,
              icon: Icons.security_outlined,
            ),
          ],
          if (status != null && !online) ...<Widget>[
            const SizedBox(height: 12),
            NoticeBanner(
              message: '机器人离线：运动命令会被后端以 409 拒绝，STOP 仍会发布。请等待心跳恢复后再试。',
              tone: theme.colorScheme.error,
              icon: Icons.link_off,
            ),
          ],
          const SizedBox(height: 16),
          if (status == null)
            const LoadingBlock(label: '正在读取机器人状态…')
          else ...<Widget>[
            SectionCard(
              title: '当前状态',
              subtitle: '状态每秒自动刷新',
              icon: Icons.sensors,
              child: Wrap(
                spacing: 8,
                runSpacing: 8,
                children: <Widget>[
                  StatusPill(
                    label: '运动：${motionLabel(status.motionState)}',
                    color: status.motionState == 'STOPPED'
                        ? theme.colorScheme.primary
                        : Colors.orange,
                    icon: Icons.directions_run,
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
                  if (ack != null)
                    StatusPill(
                      label:
                          '回执 ${ack.command} seq=${ack.seq} ${ackResultLabel(ack.result)}',
                      color: ack.result == 'APPLIED'
                          ? Colors.green
                          : Colors.orange,
                      icon: Icons.receipt_long_outlined,
                    ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            Align(
              alignment: Alignment.centerLeft,
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 620),
                child: _DirectionPad(
                  canMove: canMove,
                  active: _active,
                  canStop: canStop,
                  stopping: _stopping,
                  onStart: (String command) => unawaited(_start(command)),
                  onEnd: () => unawaited(_end()),
                  onStop: () => unawaited(_emergencyStop()),
                ),
              ),
            ),
            const SizedBox(height: 16),
            SectionCard(
              title: '安全说明',
              icon: Icons.shield_outlined,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  _Bullet(
                    text: '按住按钮期间每 200 ms 续发一次命令；松开、切换标签页或退出登录都会先发送 STOP。',
                  ),
                  _Bullet(text: '小车侧看门狗在 0.6 秒收不到命令时自动停车，不依赖本页面或网络。'),
                  _Bullet(text: '后端会校验角色、命令白名单、MQTT 连接和在线状态；离线运动命令返回 409。'),
                  _Bullet(text: '回执 APPLIED 只表示命令已写入 /cmd_vel，不代表底盘已经完成物理动作。'),
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _DirectionPad extends StatelessWidget {
  const _DirectionPad({
    required this.canMove,
    required this.active,
    required this.canStop,
    required this.stopping,
    required this.onStart,
    required this.onEnd,
    required this.onStop,
  });

  final bool canMove;
  final String? active;
  final bool canStop;
  final bool stopping;
  final ValueChanged<String> onStart;
  final VoidCallback onEnd;
  final VoidCallback onStop;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (BuildContext context, BoxConstraints constraints) {
        final ratio = constraints.maxWidth < 480 ? 1.15 : 1.75;
        return GridView.count(
          crossAxisCount: 3,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          mainAxisSpacing: 12,
          crossAxisSpacing: 12,
          childAspectRatio: ratio,
          children: <Widget>[
            const SizedBox.shrink(),
            _HoldButton(
              command: 'FORWARD',
              label: '前进',
              icon: Icons.arrow_upward,
              accent: Colors.teal,
              enabled: canMove && (active == null || active == 'FORWARD'),
              onStart: onStart,
              onEnd: onEnd,
            ),
            const SizedBox.shrink(),
            _HoldButton(
              command: 'LEFT',
              label: '左转',
              icon: Icons.turn_left,
              accent: Colors.blue,
              enabled: canMove && (active == null || active == 'LEFT'),
              onStart: onStart,
              onEnd: onEnd,
            ),
            _StopButton(
              enabled: canStop && !stopping,
              loading: stopping,
              onPressed: onStop,
            ),
            _HoldButton(
              command: 'RIGHT',
              label: '右转',
              icon: Icons.turn_right,
              accent: Colors.blue,
              enabled: canMove && (active == null || active == 'RIGHT'),
              onStart: onStart,
              onEnd: onEnd,
            ),
            const SizedBox.shrink(),
            _HoldButton(
              command: 'BACKWARD',
              label: '后退',
              icon: Icons.arrow_downward,
              accent: Colors.teal,
              enabled: canMove && (active == null || active == 'BACKWARD'),
              onStart: onStart,
              onEnd: onEnd,
            ),
            const SizedBox.shrink(),
          ],
        );
      },
    );
  }
}

class _HoldButton extends StatefulWidget {
  const _HoldButton({
    required this.command,
    required this.label,
    required this.icon,
    required this.accent,
    required this.enabled,
    required this.onStart,
    required this.onEnd,
  });

  final String command;
  final String label;
  final IconData icon;
  final Color accent;
  final bool enabled;
  final ValueChanged<String> onStart;
  final VoidCallback onEnd;

  @override
  State<_HoldButton> createState() => _HoldButtonState();
}

class _HoldButtonState extends State<_HoldButton> {
  bool _pressed = false;

  @override
  void didUpdateWidget(covariant _HoldButton oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!widget.enabled && _pressed) {
      _pressed = false;
    }
  }

  void _down(PointerDownEvent event) {
    if (!widget.enabled) return;
    setState(() => _pressed = true);
    widget.onStart(widget.command);
  }

  void _up(PointerUpEvent event) {
    if (!_pressed) return;
    setState(() => _pressed = false);
    widget.onEnd();
  }

  void _cancel(PointerCancelEvent event) {
    if (!_pressed) return;
    setState(() => _pressed = false);
    widget.onEnd();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final tone = readableTone(context, widget.accent);
    final disabled = theme.disabledColor;
    final foreground = _pressed
        ? Colors.white
        : (widget.enabled ? tone : disabled);
    final background = _pressed
        ? tone
        : tone.withValues(alpha: widget.enabled ? 0.12 : 0.05);
    return Semantics(
      button: true,
      enabled: widget.enabled,
      label: '${widget.label}，按住行驶，松开停车',
      child: Listener(
        onPointerDown: _down,
        onPointerUp: _up,
        onPointerCancel: _cancel,
        child: MouseRegion(
          cursor: widget.enabled
              ? SystemMouseCursors.click
              : SystemMouseCursors.forbidden,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 120),
            decoration: BoxDecoration(
              color: background,
              borderRadius: BorderRadius.circular(16),
              border: Border.all(
                color: tone.withValues(alpha: widget.enabled ? 0.45 : 0.15),
                width: _pressed ? 2 : 1,
              ),
            ),
            child: Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: <Widget>[
                  Icon(widget.icon, size: 28, color: foreground),
                  const SizedBox(height: 6),
                  Text(
                    widget.label,
                    style: TextStyle(
                      color: foreground,
                      fontWeight: FontWeight.w700,
                      fontSize: 14,
                    ),
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

class _StopButton extends StatelessWidget {
  const _StopButton({
    required this.enabled,
    required this.loading,
    required this.onPressed,
  });

  final bool enabled;
  final bool loading;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    final red = readableTone(context, Colors.red);
    return Semantics(
      button: true,
      enabled: enabled,
      label: '急停 STOP',
      child: Material(
        color: enabled ? red : red.withValues(alpha: 0.25),
        borderRadius: BorderRadius.circular(16),
        child: InkWell(
          onTap: enabled ? onPressed : null,
          borderRadius: BorderRadius.circular(16),
          child: Center(
            child: loading
                ? const SizedBox(
                    width: 26,
                    height: 26,
                    child: CircularProgressIndicator(
                      strokeWidth: 2.4,
                      color: Colors.white,
                    ),
                  )
                : Column(
                    mainAxisSize: MainAxisSize.min,
                    children: <Widget>[
                      const Icon(
                        Icons.pan_tool_alt,
                        size: 30,
                        color: Colors.white,
                      ),
                      const SizedBox(height: 6),
                      Text(
                        '急停',
                        style: TextStyle(
                          color: Colors.white,
                          fontWeight: FontWeight.w800,
                          fontSize: 15,
                          shadows: <Shadow>[
                            Shadow(color: Colors.black26, blurRadius: 2),
                          ],
                        ),
                      ),
                    ],
                  ),
          ),
        ),
      ),
    );
  }
}

class _Bullet extends StatelessWidget {
  const _Bullet({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Padding(
            padding: const EdgeInsets.only(top: 6, right: 8),
            child: Container(
              width: 6,
              height: 6,
              decoration: BoxDecoration(
                color: theme.colorScheme.primary,
                shape: BoxShape.circle,
              ),
            ),
          ),
          Expanded(child: Text(text, style: theme.textTheme.bodySmall)),
        ],
      ),
    );
  }
}
