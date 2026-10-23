# MQTT 通信协议（exp3）

后端（`exp3/backend`，Eclipse Paho Java 1.2.5）与小车桥接节点（`exp3/robot-bridge`，paho-mqtt 1.5+/2.x）之间的约定。两端都只认本文档，ROS 细节只存在于桥接节点内部（需求 TBD-001：上层 MQTT 接口不随 ROS 版本变化）。

- 协议：MQTT 3.1.1，载荷为 UTF-8 JSON。
- Broker：任何符合规范的 Broker。部署推荐 Mosquitto；本仓库的自动化测试用 amqtt 0.12.1。
- 机器人编号 `{id}`：默认 `SCOUT-001`。后端 `patrol.robot.id` 与桥接节点 `robot_id` 必须一致，只能包含字母、数字、`_`、`-`（不能含 `/`、`+`、`#`）。

## 1. Topic

| Topic | 方向 | 发布 QoS | retain | 频率 | 用途 |
| --- | --- | --- | --- | --- | --- |
| `robot/{id}/control` | 后端 → 小车 | 1 | 否 | 按需；按住按钮时约 5 Hz | 运动/停止命令 |
| `robot/{id}/status` | 小车 → 后端 | 0 | 否 | 2 Hz（`status_period`） | 运动状态与遥测 |
| `robot/{id}/heartbeat` | 小车 → 后端 | 0；遗嘱与退出通知为 1 | 否 | 1 Hz（`heartbeat_period`） | 在线检测 |
| `robot/{id}/control/ack` | 小车 → 后端 | 0 | 否 | 每条命令一条 | 执行回执 |

- 两端都用 **clean session**，断线期间的消息不排队，重连后不会补发旧命令。
- 任何消息都**不保留**（retain=false）。桥接节点丢弃带 retain 标志的控制消息；后端丢弃订阅时收到的保留消息（只有实时消息才算数）。
- 后端以 QoS 1 订阅三个上行 Topic（实际 QoS 取发布与订阅的较小值）；桥接节点以 QoS 1 订阅 `control`。

## 2. 消息格式

字段名使用需求文档 A.4.7/A.4.8 的 snake_case。后端只接受不超过 8 KB 的上行消息，桥接节点只接受不超过 4 KB 的控制消息。消息中如带 `robot_id` 且与本机编号不同，一律忽略。`timestamp` 是发送方的 Unix 毫秒，**只作记录**：两端时钟不保证同步，任何判断都不依赖它。

### 2.1 控制 `robot/{id}/control`

```json
{"robot_id": "SCOUT-001", "command": "FORWARD", "seq": 12, "session": "7d0c6f0e-3b9a-4a8e-9a55-0f1d2b6c1e42", "timestamp": 1780022123000}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `command` | 是 | `FORWARD`、`BACKWARD`、`LEFT`、`RIGHT`、`STOP`，区分大小写 |
| `seq` | 否 | 非负整数；同一 `session` 内严格递增 |
| `session` | 否 | 后端本次运行的 UUID；后端重启后变化，桥接节点据此重新开始计数 |
| `robot_id` | 否 | 目标机器人 |
| `timestamp` | 否 | 后端受理时间（毫秒），仅记录 |

后端发出的消息总带有全部字段。手工调试时可以只发 `{"command": "STOP"}`；这类没有 `seq`/`session` 的消息总会被接受，不参与去重。

命令到速度的映射只在桥接节点配置（后端从不发送速度）：

| 命令 | `linear.x` | `angular.z` | 默认值 |
| --- | --- | --- | --- |
| FORWARD | +v | 0 | v = 0.3 m/s（上限 1.0） |
| BACKWARD | −v | 0 | |
| LEFT | 0 | +ω（原地左转，REP-103） | ω = 0.5 rad/s（上限 1.5） |
| RIGHT | 0 | −ω | |
| STOP | 0 | 0 | |

### 2.2 状态 `robot/{id}/status`

```json
{
  "robot_id": "SCOUT-001",
  "motion_state": "FORWARD",
  "battery_pct": null,
  "battery_voltage": 25.4,
  "linear_velocity": 0.29,
  "angular_velocity": 0.0,
  "position": {"x": 1.5, "y": -2.0, "yaw": 0.25},
  "chassis_connected": true,
  "source": "ros1",
  "timestamp": 1780022123000
}
```

| 字段 | 来源 | 说明 |
| --- | --- | --- |
| `motion_state` | 桥接节点 | 桥接节点当前输出的运动命令：`STOPPED`/`FORWARD`/`BACKWARD`/`LEFT`/`RIGHT`；看门狗触发后为 `STOPPED` |
| `battery_pct` | — | 松灵驱动不提供荷电状态（`/BMS_status` 的 SOC 赋值在驱动中被注释），固定为 `null` |
| `battery_voltage` | `/scout_status.battery_voltage` | V |
| `linear_velocity` / `angular_velocity` | `/scout_status`，没有时用 `/odom` 的 twist | 底盘反馈的实测值 |
| `position` | `/odom` | 轮式里程计累计位姿，会漂移，不是全局定位 |
| `chassis_connected` | 桥接节点 | `true`/`false`：`chassis_timeout`（1 s）内是否收到 `/scout_status`；`null`：未安装 `scout_msgs`，无法判断 |
| `source` | 桥接节点 | `ros1`、`ros2` 或 `sim`（桌面仿真，数据不是来自硬件） |

超过 `chassis_timeout` 未更新的值一律发 `null`；不估算、不补值。需求文档 A.4.8 之外新增的字段有 `battery_voltage`、`chassis_connected`、`source`。

### 2.3 心跳 `robot/{id}/heartbeat`

```json
{"robot_id": "SCOUT-001", "online": true, "chassis_connected": true, "ros": "ros1", "watchdog_trips": 0, "timestamp": 1780022123000}
```

| 情形 | 载荷 | QoS |
| --- | --- | --- |
| 正常心跳（1 Hz；连上 Broker 时立即发一次） | 如上，`online: true` | 0 |
| **遗嘱（LWT）**：连接时登记，桥接节点崩溃、断电或网络中断时由 Broker 代发 | `{"robot_id": "SCOUT-001", "online": false, "reason": "LWT"}` | 1 |
| 正常退出（Ctrl+C、SIGTERM） | `{"robot_id": "SCOUT-001", "online": false, "reason": "SHUTDOWN", "timestamp": …}` | 1 |

进程被杀或 TCP 连接断开时，Broker 会立即发布遗嘱；如果是网络静默中断，Broker 要等 1.5 × keepalive（默认 5 s，即约 7.5 s）才会发。这段时间内，后端的 3 s 心跳超时会先判定离线。

### 2.4 回执 `robot/{id}/control/ack`

```json
{"robot_id": "SCOUT-001", "seq": 12, "session": "7d0c6f0e-…", "command": "FORWARD", "result": "APPLIED", "reason": null, "timestamp": 1780022123000}
```

- `APPLIED`：命令已写入 `/cmd_vel`，**不代表底盘已经动作**（需求 TBD-007：端到端确认需要底盘支持）。
- `REJECTED` + `reason: "INVALID_COMMAND"`：命令不在白名单内，此时 `command` 为 `null`。
- 重复或乱序的命令直接丢弃，不回执，以免覆盖真实结果。
- 后端只接受本次 `session` 的回执，展示在 `GET /api/v1/robot/status` 的 `lastAck` 中。

## 3. 安全机制

### 3.1 按住即走：保活与看门狗

```text
客户端（按住）  POST FORWARD ──200ms── POST FORWARD ──200ms── POST FORWARD    （松开）POST STOP
后端                 │ seq=11              │ seq=12              │ seq=13                │ seq=14
桥接节点        /cmd_vel 20 Hz：(0.3, 0) (0.3, 0) (0.3, 0) …                         立即 (0, 0) ×1 + 10 帧 (0, 0)，之后不再发布
                     └── 每收到一条就重置 0.6 s 看门狗；超过 0.6 s 没有新命令 → 自动切换为 STOP
```

- 看门狗超时 `watchdog_timeout` 默认 0.6 s，配置上限 1.0 s（需求 PER-006 ≤ 1 s）；按 20 Hz 检查，实际停车时刻 ≤ 0.65 s。
- 停止后只补发 10 帧零速度，随后不再占用 `/cmd_vel`，不影响遥控器或其他节点。
- 底盘驱动本身是否有指令超时停车没有查到权威资料，**安全只依赖桥接节点的看门狗**。

### 3.2 STOP 优先

- 桥接节点收到 STOP 后，在 MQTT 回调线程里立即发布零速度，不等下一个控制周期。
- 后端对 STOP 不做在线检查（离线时也照常发布），MONITOR 角色可以随时下发 STOP（FR-MON-004）。
- 桥接节点与 Broker 断线时立即停车；后端与 Broker 断线时所有命令返回 503，不排队、不补发。

### 3.3 顺序与去重

- 后端在同一把锁内分配 `seq` 并调用发布，保证线路上的顺序与 `seq` 一致。命令以 QoS 1 发送，并等待 Broker 的 PUBACK（最长 `publish-timeout-ms` = 1 s），超时返回 503。
- 桥接节点按 `session` 记录最后的 `seq`，丢弃 `seq` 不增大的消息（QoS 1 重复投递、乱序）。`session` 变化表示后端已重启，从新编号重新开始。

### 3.4 在线判定与告警（后端）

在线 = 后端已连接 Broker **且** 最近一次心跳为 `online: true` **且** 在 `heartbeat-timeout-ms`（3 s）之内收到（以后端接收时间为准）。后端断线重连后，必须等新的心跳才算在线。

| 告警 | 级别 | `source` | 触发（状态跳变时只生成一次，持续状态不重复） |
| --- | --- | --- | --- |
| MQTT连接断开 | CRITICAL | `MQTT` | 后端与 Broker 断开，或启动后首次连接失败 |
| 小车离线 | CRITICAL | 机器人编号 | 心跳超时，或收到遗嘱（`LWT`） |
| 小车离线 | WARNING | 机器人编号 | 桥接节点正常退出（`SHUTDOWN`） |
| 电量过低 | WARNING | 机器人编号 | 百分比低于 `low-battery-pct`（默认 20%，有百分比时优先），或电压低于 `low-battery-voltage`（默认关闭）；分别有 5% / 0.3 V 回差 |
| 底盘无状态数据 | WARNING | 机器人编号 | 在线但 `chassis_connected` 变为 `false` |

后端自身断线引起的“看不到小车”只计一条 MQTT 告警，不再追加小车离线告警。心跳、状态和回执都不写数据库，只有告警和人工控制操作会落库（FR-COM-002、DB-003）。

## 4. 手工调试

```bash
# 观察全部机器人消息
mosquitto_sub -h <broker> -t 'robot/SCOUT-001/#' -v

# 不经后端直接发一条前进命令：约 0.6 s 后被看门狗停下
mosquitto_pub -h <broker> -t robot/SCOUT-001/control -q 1 -m '{"command":"FORWARD"}'
mosquitto_pub -h <broker> -t robot/SCOUT-001/control -q 1 -m '{"command":"STOP"}'
```

## 5. 与需求文档 TBD 项的对应

| TBD | 内容 | 本项目的处理 | 状态 |
| --- | --- | --- | --- |
| TBD-001 | ROS1/ROS2 | 桥接节点同时提供 rospy 与 rclpy 适配器，`--ros auto` 按环境自动选择 | 代码完成，❓未在实车运行 |
| TBD-002 | 底盘运动 Topic | `/cmd_vel`（scout_ros 与 scout_ros2 源码均订阅该话题），可在 `ros.cmd_vel_topic` 修改 | 已核对源码 |
| TBD-003 | 运动消息类型 | `geometry_msgs/Twist`（两版驱动源码均使用 `linear.x`、`angular.z`）；话题名可配置 | 已核对源码 |
| TBD-004 | 电量来源 | 只有 `/scout_status.battery_voltage`；百分比为 `null` | 已核对源码 |
| TBD-005 | 速度来源 | `/scout_status` 的线速度、角速度 | 已核对源码 |
| TBD-006 | 位姿来源 | `/odom` 轮式里程计 | 已核对源码 |
| TBD-007 | 执行 ACK | 桥接节点级回执（`APPLIED` = 已写入 `/cmd_vel`） | 已实现 |
| TBD-008 | 控制/保活频率 | 建议每 200 ms 重发一次；`/cmd_vel` 20 Hz | ❓需实车确认 |
| TBD-009 | Watchdog 超时 | 0.6 s，可配置 0.1–1.0 s | ❓需实车测量停车距离 |
