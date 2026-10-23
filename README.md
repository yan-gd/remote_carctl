# exp3：经 MQTT 控制松灵 SCOUT MINI Pro Kit

后端通过 MQTT 向小车下发运动/停止命令，并接收心跳、状态与执行回执。exp3 由两部分组成：

| 目录 | 内容 | 运行位置 |
| --- | --- | --- |
| [`backend/`](backend) | 完整的后端：以 exp2 为基线（17 个接口全部保留），新增机器人接口 `/api/v1/robot`、`/robot/status`、`/robot/control` 和 MQTT 客户端 | 后端 PC（Windows，Java 17+） |
| [`robot-bridge/`](robot-bridge) | 与小车通信的代码：MQTT ↔ ROS 桥接节点，把命令转成 `/cmd_vel`，上报心跳与状态，带看门狗 | 小车工控机（ROS1 Noetic / ROS2 Foxy、Humble） |
| [`scripts/`](scripts) | `e2e_sim.py` 不接实车的端到端联调；`robot_cli.py` 命令行遥控（实车调试用）；`patrol_client.py` HTTP 客户端 | 后端 PC |
| [`docs/`](docs) | [MQTT 协议](docs/MQTT-PROTOCOL.md)、[部署与实车联调](docs/DEPLOYMENT.md)、[测试报告](docs/TEST-REPORT.md) | — |

接口细节见 [`backend/docs/API.md`](backend/docs/API.md)（第 5.18–5.20 节为机器人接口）。

## 1. 工作方式

```text
POST /api/v1/robot/control {"command":"FORWARD"}           每 200 ms 重发一次（按住）
   │ 校验：角色 → 命令白名单 → MQTT 已连接(503) → 运动命令要求在线(409)
   ▼
后端 ── robot/SCOUT-001/control（QoS1，不保留，带 seq/session）──▶ Broker ──▶ 桥接节点
                                                                              │ Twist → /cmd_vel（20 Hz）
后端 ◀── robot/SCOUT-001/heartbeat（1 Hz，遗嘱 = 离线）───────────── Broker ◀──┤ 0.6 s 无新命令 → 自动停车
后端 ◀── robot/SCOUT-001/status（2 Hz：运动状态、电压、速度、位姿）── Broker ◀──┤ 与 Broker 断线 → 立即停车
后端 ◀── robot/SCOUT-001/control/ack（每条命令的回执）────────────── Broker ◀──┘
   │
GET /api/v1/robot/status：在线（心跳 3 s 超时）、遥测（拿不到为 null）、最近回执
告警：MQTT 断开 / 小车离线 / 电量过低 / 底盘无数据（状态跳变时各记一次）
```

安全设计按“前端—后端—小车”分层：

- **后端**：JWT 与角色校验（OPERATOR 可发全部命令，MONITOR 只能 STOP）、命令白名单、MQTT 未连接时返回 503、离线时拒绝运动命令。STOP 不受在线状态限制，优先级最高。
- **小车侧看门狗**（≤ 1 s，需求 PER-006）：不依赖后端或网络，只要命令停止到达就停车。
- **MQTT 设计**：clean session，不保留消息，`seq` 去重，遥测不伪造，保活命令不逐条写日志。

## 2. 快速体验（不需要实车）

需要：JDK 17+、Maven、Python 3.7+，以及一个 MQTT Broker（推荐 Mosquitto；也可以 `pip install amqtt` 后运行 `amqtt`）。

```powershell
# 1) 构建后端
cd exp3\backend
mvn clean install

# 2) 启动 Broker（另开终端），例如：mosquitto -v

# 3) 启动后端（演示账号，另开终端）
.\scripts\Run-Demo.ps1 -MqttUrl tcp://127.0.0.1:1883

# 4) 启动桥接节点，用仿真底盘代替 ROS（另开终端）
cd exp3\robot-bridge
pip install -r requirements.txt
python scout_mqtt_bridge.py --ros sim

# 5) 控制（另开终端）：查看状态，按住前进 1.5 秒，然后松开
cd exp3
python scripts\robot_cli.py --user demo_operator status
python scripts\robot_cli.py --user demo_operator hold FORWARD 1.5
```

也可以打开 Swagger <http://localhost:8080/swagger-ui.html>，登录后调用“机器人控制”分组。完整的自动化联调（25 个检查步骤，包括断网、杀进程、Broker 重启）：

```powershell
python scripts\e2e_sim.py --broker-cmd "<启动 Broker 的命令>"
```

## 3. 上实车

按 [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) 操作：安装并配置 Broker（Mosquitto 2 需要显式配置 `listener`）→ 工控机上启动 CAN 和 `scout_base` → 拷贝 `robot-bridge`，修改 `config.yaml` 的 `mqtt.host` → `bash start_bridge.sh` → 先架空车轮，按检查清单逐项测试，再落地低速测试。

## 4. 验证结果（详见 [测试报告](docs/TEST-REPORT.md)）

| 项 | 结果 |
| --- | --- |
| 后端 `mvn clean test`（含真实 Broker 往返测试） | 98 项通过（exp2 原有 48 项 + 新增 50 项），指令覆盖率 96.3% |
| 后端双进程重启验证 | PASS |
| 桥接节点单元测试 | 41 项，Python 3.8 / paho 1.6.1 与 Python 3.13 / paho 2.1.0 均通过 |
| 端到端模拟联调 | 25 项，两套桥接环境均通过；遗嘱触发离线 ≤ 0.03 s，Broker 重启后约 1.6 s 自动恢复，松手后停车且不再移动 |
| 实车 | **未测试**：ROS 适配器只用替身模块验证过接线 |

## 5. 采用的技术决策

沿用此前在 `HANDOFF.md` 中确定的方案：

- 后端技术栈以 exp2 为准（Spring Boot 2.7.2 + COLA + SQLite），没有采用需求文档中的 FastAPI/MySQL。REST 字段沿用 exp2 的 camelCase 和 `ApiResponse` 外层，与需求文档 snake_case 的对应关系见 API.md 5.19 节。
- REST 路径、MQTT Topic、错误码（`INVALID_COMMAND`/`ROBOT_OFFLINE`/`MQTT_UNAVAILABLE`）按需求文档 A.4 实现；`BusinessException` 新增 `UNAVAILABLE`→503。
- 小车侧统一通过 `/cmd_vel`（`geometry_msgs/Twist`）控制，已核对 scout_ros（ROS1）和 scout_ros2（ROS2）的源码。速度只在小车侧配置。
- 电量百分比没有可靠来源，返回 `null`；另外上报真实的 `battery_voltage`。

在原计划基础上的几处细化：

- 保活日志去重同样适用于重复的失败请求（同一用户、命令、错误码 1.5 s 内只记一条），避免离线时按住按钮造成日志刷屏。
- 控制失败由执行器带命令记录为 `ROBOT_CONTROL`，不再由全局异常处理器重复记一条 `BUSINESS_FAILURE`。
- 首次连接与断线重连都按固定间隔（默认 5 s，可配置）重试，不使用 Paho 自带的指数退避。
- exp2 阶段的 PPT、PDF、设计文档没有复制到 exp3，仍在 `exp2/docs`。

## 6. 待确认事项

| # | 问题 | 当前默认 |
| --- | --- | --- |
| 1 | 工控机实际是 ROS1 还是 ROS2，驱动是否已编译 | 两者都支持，`--ros auto` 自动识别；Melodic（Python 2）不支持 |
| 2 | Broker 部署在哪台机器，是否需要账号或 TLS | 两种方案都写在 DEPLOYMENT.md；默认匿名 + 1883 |
| 3 | 默认速度 0.3 m/s、0.5 rad/s，看门狗 0.6 s 是否合适 | 可在 `config.yaml` 调整（看门狗 ≤ 1 s，速度有硬上限） |
| 4 | 低电量电压阈值 | 默认关闭，实车测量后设置 `PATROL_LOW_BATTERY_VOLTAGE` |
| 5 | 遥控器需要拨到哪个档位才允许 CAN 控制 | 以实车手册为准 |
