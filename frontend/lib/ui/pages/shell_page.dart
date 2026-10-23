import 'package:flutter/material.dart';

import '../../state/robot_store.dart';
import '../app.dart';
import '../formatters.dart';
import '../widgets/common.dart';
import 'alarms_page.dart';
import 'control_page.dart';
import 'dashboard_page.dart';
import 'logs_page.dart';

class MainShell extends StatefulWidget {
  const MainShell({super.key});

  @override
  State<MainShell> createState() => _MainShellState();
}

class _MainShellState extends State<MainShell> {
  static const List<Widget> _pages = <Widget>[
    DashboardPage(),
    ControlPage(),
    AlarmsPage(),
    LogsPage(),
  ];

  int _index = 0;

  Future<void> _confirmLogout() async {
    final app = AppScope.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (BuildContext context) => AlertDialog(
        title: const Text('退出登录'),
        content: const Text('退出时会尝试向小车发送 STOP，并停止状态刷新。'),
        actions: <Widget>[
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('退出'),
          ),
        ],
      ),
    );
    if (confirmed == true) {
      if (mounted) ScaffoldMessenger.of(context).clearSnackBars();
      await app.logout();
    }
  }

  @override
  Widget build(BuildContext context) {
    final app = AppScope.of(context);
    final user = app.user;
    final store = app.robot;
    final wide = MediaQuery.sizeOf(context).width >= 900;
    final veryWide = MediaQuery.sizeOf(context).width >= 1180;

    return Scaffold(
      appBar: AppBar(
        titleSpacing: 20,
        title: Row(
          children: <Widget>[
            Icon(
              Icons.precision_manufacturing_outlined,
              size: 22,
              color: Theme.of(context).colorScheme.primary,
            ),
            const SizedBox(width: 8),
            const Flexible(
              child: Text('SCOUT 巡检控制台', overflow: TextOverflow.ellipsis),
            ),
          ],
        ),
        actions: <Widget>[
          if (store != null && wide)
            AnimatedBuilder(
              animation: store,
              builder: (BuildContext context, Widget? _) =>
                  _ConnectionPill(store: store),
            ),
          const SizedBox(width: 10),
          if (user != null)
            _AccountMenu(
              username: user.username,
              role: user.role,
              compact: !veryWide,
              onLogout: _confirmLogout,
            ),
          const SizedBox(width: 10),
        ],
      ),
      body: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          if (wide)
            NavigationRail(
              selectedIndex: _index,
              onDestinationSelected: (int index) =>
                  setState(() => _index = index),
              extended: veryWide,
              minExtendedWidth: 190,
              labelType: veryWide
                  ? NavigationRailLabelType.none
                  : NavigationRailLabelType.all,
              leading: Padding(
                padding: const EdgeInsets.only(top: 8, bottom: 12),
                child: CircleAvatar(
                  radius: 20,
                  backgroundColor: Theme.of(
                    context,
                  ).colorScheme.primaryContainer,
                  child: Icon(
                    Icons.smart_toy_outlined,
                    color: Theme.of(context).colorScheme.onPrimaryContainer,
                  ),
                ),
              ),
              destinations: const <NavigationRailDestination>[
                NavigationRailDestination(
                  icon: Icon(Icons.space_dashboard_outlined),
                  selectedIcon: Icon(Icons.space_dashboard),
                  label: Text('总览'),
                ),
                NavigationRailDestination(
                  icon: Icon(Icons.gamepad_outlined),
                  selectedIcon: Icon(Icons.gamepad),
                  label: Text('控制'),
                ),
                NavigationRailDestination(
                  icon: Icon(Icons.notifications_outlined),
                  selectedIcon: Icon(Icons.notifications),
                  label: Text('告警'),
                ),
                NavigationRailDestination(
                  icon: Icon(Icons.receipt_long_outlined),
                  selectedIcon: Icon(Icons.receipt_long),
                  label: Text('日志'),
                ),
              ],
            ),
          if (wide) const VerticalDivider(width: 1),
          Expanded(
            child: IndexedStack(index: _index, children: _pages),
          ),
        ],
      ),
      bottomNavigationBar: wide
          ? null
          : NavigationBar(
              selectedIndex: _index,
              onDestinationSelected: (int index) =>
                  setState(() => _index = index),
              destinations: const <NavigationDestination>[
                NavigationDestination(
                  icon: Icon(Icons.space_dashboard_outlined),
                  selectedIcon: Icon(Icons.space_dashboard),
                  label: '总览',
                ),
                NavigationDestination(
                  icon: Icon(Icons.gamepad_outlined),
                  selectedIcon: Icon(Icons.gamepad),
                  label: '控制',
                ),
                NavigationDestination(
                  icon: Icon(Icons.notifications_outlined),
                  selectedIcon: Icon(Icons.notifications),
                  label: '告警',
                ),
                NavigationDestination(
                  icon: Icon(Icons.receipt_long_outlined),
                  selectedIcon: Icon(Icons.receipt_long),
                  label: '日志',
                ),
              ],
            ),
    );
  }
}

class _ConnectionPill extends StatelessWidget {
  const _ConnectionPill({required this.store});

  final RobotStore store;

  @override
  Widget build(BuildContext context) {
    final status = store.status;
    if (status == null) {
      if (store.error != null) {
        return const StatusPill(
          label: '连接异常',
          color: Colors.red,
          icon: Icons.cloud_off_outlined,
        );
      }
      return const StatusPill(
        label: '连接中',
        color: Colors.blueGrey,
        icon: Icons.sync,
      );
    }
    if (status.online) {
      return const StatusPill(
        label: '机器人在线',
        color: Colors.green,
        icon: Icons.wifi,
      );
    }
    return StatusPill(
      label: store.error == null ? '机器人离线' : '状态刷新失败',
      color: Colors.orange,
      icon: Icons.wifi_off,
    );
  }
}

class _AccountMenu extends StatelessWidget {
  const _AccountMenu({
    required this.username,
    required this.role,
    required this.compact,
    required this.onLogout,
  });

  final String username;
  final String role;
  final bool compact;
  final VoidCallback onLogout;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final initial = username.isEmpty ? '?' : username.substring(0, 1);
    return PopupMenuButton<String>(
      tooltip: '账号与退出',
      onSelected: (String value) {
        if (value == 'logout') onLogout();
      },
      itemBuilder: (BuildContext context) => <PopupMenuEntry<String>>[
        PopupMenuItem<String>(
          enabled: false,
          child: Text('$username · ${roleLabel(role)}'),
        ),
        const PopupMenuDivider(),
        const PopupMenuItem<String>(
          value: 'logout',
          child: ListTile(
            contentPadding: EdgeInsets.zero,
            leading: Icon(Icons.logout),
            title: Text('退出登录'),
          ),
        ),
      ],
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            CircleAvatar(
              radius: 14,
              backgroundColor: theme.colorScheme.primaryContainer,
              child: Text(
                initial.toUpperCase(),
                style: TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w700,
                  color: theme.colorScheme.onPrimaryContainer,
                ),
              ),
            ),
            if (!compact) ...<Widget>[
              const SizedBox(width: 8),
              Text(
                username,
                style: theme.textTheme.bodyMedium?.copyWith(
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
            const Icon(Icons.arrow_drop_down),
          ],
        ),
      ),
    );
  }
}
