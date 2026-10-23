# exp3 测试报告

测试日期：2026-10-23。下列结果均为本机实际执行所得；**没有在实车上测试**（见第 6 节）。

## 1. 结论

| 层级 | 内容 | 结果 |
| --- | --- | --- |
| 后端自动化测试 | JUnit 5 / Mockito / MockMvc / 真实 SQLite / 真实 MQTT Broker 往返 | **98 项全部通过**（exp2 原有 48 项 + 新增 50 项） |
| 后端双进程重启验证 | `scripts/verify_process_restart.py` | PASS（20 个操作，持久化与 JWT 重启后仍有效） |
| 桥接节点单元测试 | 不需要 ROS/Broker/硬件 | **41 项**，在 Python 3.8 + paho 1.6.1 和 Python 3.13 + paho 2.1.0 两套环境下**均全部通过** |
| 端到端模拟联调 | 后端 JAR + 真实 Broker + 真实桥接进程（`--ros sim`） | **25 项**，两套桥接环境下**均全部通过** |
| 实车联调 | — | 未进行 ❓ |

## 2. 环境

| 项 | 版本 |
| --- | --- |
| 操作系统 | Windows 11 Home 10.0.26100 |
| 后端 | JDK 21.0.5（`--release 17`）、Maven 3.9.11、Spring Boot 2.7.2、Eclipse Paho Java 1.2.5 |
| 桥接节点 | Python 3.8.6 + paho-mqtt 1.6.1 + PyYAML 6.0.1；Python 3.13.5 + paho-mqtt 2.1.0 + PyYAML 6.0.3 |
| Broker | amqtt 0.12.1（MQTT 3.1.1），监听 127.0.0.1:1883，匿名。部署推荐 Mosquitto，见 DEPLOYMENT.md |

## 3. 后端（`exp3/backend`）

```powershell
cd exp3\backend
$env:MQTT_TEST_URL = "tcp://127.0.0.1:1883"   # 不设置时 MqttRobotGatewayBrokerTest 自动跳过，其余 97 项照常运行
mvn clean test
```

| 模块 | 测试类 | 数量 | 覆盖内容 |
| --- | --- | --- | --- |
| domain | `DomainBoundaryTest`、`DomainRulesTest`（exp2） | 7 | 路线/任务/告警/凭据规则 |
| domain | `RobotDomainTest` | 5 | 命令白名单（大小写、`HOME`、null）、未知运动状态不猜测、非有限或越界遥测置 null、系统告警工厂、`requireAny` 与 503 类别 |
| app | `ExecutorTest`（exp2） | 5 | 路线/任务编排 |
| app | `RobotMonitorTest` | 15 | 心跳超时边界、遗嘱立即离线、SHUTDOWN 仅 WARNING、断线只记 MQTT 告警且重连后须新心跳、首次连接失败告警、过期遥测隐藏、电压/百分比告警与回差、底盘无数据告警、告警库异常不影响监测、配置校验 |
| app | `RobotCommandExecutorTest` | 11 | 发布与审计、1.5 s 保活去重（按用户）、MONITOR 只能 STOP、其他角色和不存在的用户不发布、非法命令不回显、MQTT 断开 503、离线时拒绝运动但放行 STOP、Broker 未确认 503、失败去重、审计异常不掩盖已发出的命令 |
| infrastructure | `JwtTokenTest`（exp2） | 4 | JWT |
| infrastructure | `RobotMessagesTest` | 5 | 控制报文字段、心跳/状态/回执解析，畸形、超长、他车、旧 session 消息一律丢弃 |
| infrastructure | `MqttRobotGatewayTest` | 3 | 配置校验与密码不入日志、Broker 不可达时不发送并持续重试、关闭幂等、禁用网关 |
| infrastructure | `MqttRobotGatewayBrokerTest` | 1 | **真实 Broker 往返**：连接订阅、心跳、控制报文 seq/session、状态、回执、订阅前残留的保留消息被忽略、强制断开触发遗嘱 |
| start | `ApiIntegrationTest`（exp2，Swagger 操作数断言由 17 改为 20） | 25 | 认证、权限矩阵、CRUD、错误、日志、Swagger |
| start | `ExceptionMappingTest`、`PersistenceTest`、`RestartPersistenceTest`（exp2） | 7 | 异常映射、SQLite、真实 HTTP 重启 |
| start | `RobotApiTest` | 7 | 经真实安全链与 SQLite：四种角色读接口与匿名 401、离线时遥测为 null、在线遥测与回执、控制角色矩阵、400/409/503 错误码、未知字段 400、保活只记一条日志且失败不重复记录、心跳丢失告警只生成一次并可确认、Broker 断开告警 |
| start | `RobotConfigurationTest` | 3 | MQTT 开关、生命周期启动/停止、错误配置启动即失败 |
| | **合计** | **98** | 0 失败、0 错误、0 跳过 |

JaCoCo 聚合覆盖率（`start/target/site/jacoco-aggregate/index.html`，范围 `org.example.patrol`）：

| 范围 | 指令 | 分支 | 行 | 方法 |
| --- | --- | --- | --- | --- |
| 全部 `org.example.patrol`（81 个类） | 96.33% | 88.66% | 95.65% | 96.41% |
| 其中新增机器人/MQTT 相关类（27 个） | 93.42% | 86.71% | 91.34% | 93.28% |

不设置 `MQTT_TEST_URL` 时，`MqttRobotGateway` 的已连接路径（回调、订阅、入站分发）不会被执行，覆盖率会相应降低。

## 4. 桥接节点（`exp3/robot-bridge`）

```bash
cd exp3/robot-bridge
python -m unittest discover -s tests -v
```

| 测试类 | 数量 | 覆盖内容 |
| --- | --- | --- |
| `ConfigTest` | 4 | 默认值保守、合并覆盖、超速/看门狗超过 1 s/类型错误/未知配置项一律拒绝、Topic 名称 |
| `MotionTest` | 8 | 五个命令的速度映射并立即发布、按住保活持续运动、0.6 s 看门狗停车后补 10 帧零速再静默、STOP 立即生效且不算看门狗触发、空闲不发布、MQTT 断线停车、退出时停车并发送 SHUTDOWN、ROS 发布异常不中断循环 |
| `ProtocolTest` | 9 | 回执字段、非法命令 REJECTED 且不动、重复/乱序 seq 丢弃与 session 重置、无编号消息放行、畸形消息忽略、保留/他车消息忽略、连接后立即订阅并发送心跳和状态、Broker 不可达的周期提示、断线期间不发布 |
| `TelemetryTest` | 7 | 心跳 1 Hz / 状态 2 Hz、只报实测值（电量百分比恒为 null）、过期置 null、里程计速度兜底、底盘不可观测时为 null 而非 false、非有限值丢弃、四元数转航向角 |
| `MqttLinkTest` | 5 | paho 2.x 使用 `CallbackAPIVersion.VERSION2`、1.x 兼容构造；遗嘱内容；账号与 TLS；两种回调签名；只转发控制 Topic；发布不保留并可等待送达 |
| `Ros1AdapterTest`、`Ros2AdapterTest`、`SimAdapterTest`、`StartupTest` | 8 | 用替身 rospy/rclpy/消息模块检查话题名、消息类型、Twist 字段、`/odom` 与 `/scout_status` 回调写入遥测、无 `scout_msgs` 时的降级、关闭流程；仿真积分；ROS 版本自动识别；命令行覆盖配置文件；`config.yaml` 与代码默认值一致 |
| **合计** | **41** | 两套环境各 41 项全部通过 |

## 5. 端到端模拟联调（`exp3/scripts/e2e_sim.py`）

```powershell
cd exp3\backend; mvn -DskipTests package; cd ..
python scripts\e2e_sim.py --bridge-python <装有 paho-mqtt 与 PyYAML 的 python> --broker-cmd "<启动 Broker 的命令>"
```

脚本启动 Broker（传入 `--broker-cmd` 时）、后端 JAR（临时 SQLite、随机演示密码、电压告警阈值 22.0 V）和桥接节点（`--ros sim`，仿真底盘上报 21.8 V），然后只通过 REST API 和进程操作完成下表各步。报告写入 `verification/e2e-report-*.json`。

| # | 步骤 | 桥接 paho 1.6.1 / Py 3.8 | 桥接 paho 2.1.0 / Py 3.13 |
| --- | --- | --- | --- |
| 1 | Broker、后端启动（后端用时） | PASS（6.3 s） | PASS（5.8 s） |
| 2 | 四个演示账号登录 | PASS | PASS |
| 3 | 桥接心跳使机器人在线 | PASS（0.27 s） | PASS（0.26 s） |
| 4 | 状态只含实测值或 null（电量百分比 null、电压 21.8、底盘已连接、有位姿） | PASS | PASS |
| 5 | 电压低于阈值，生成且只生成一条 WARNING 告警 | PASS | PASS |
| 6 | FORWARD 返回 accepted，并收到小车 APPLIED 回执 | PASS（回执 0.03 s） | PASS（0.02 s） |
| 7 | 按住 FORWARD 2 s（每 200 ms 一次），仿真底盘前进 | PASS（0.78 m） | PASS（0.78 m） |
| 8 | 松手后由看门狗停车，之后不再移动 | PASS（距最后一条命令 0.93 s 时经 API 看到 STOPPED，漂移 0.000 m） | PASS（0.69 s，漂移 0.000 m） |
| 9 | 11 次 FORWARD 请求只留下 2 条控制日志 | PASS | PASS |
| 10–13 | MONITOR 可 STOP；MONITOR 运动 403；VIEWER 控制 403；`JUMP` → 400 INVALID_COMMAND | PASS | PASS |
| 14 | 强杀桥接进程，后端经遗嘱立即判定离线 | PASS（0.003 s） | PASS（0.028 s） |
| 15 | 离线生成 CRITICAL 告警（描述含“遗嘱”） | PASS | PASS |
| 16–17 | 离线时运动命令 409 ROBOT_OFFLINE；STOP 仍为 200 | PASS | PASS |
| 18 | 重启桥接后恢复在线 | PASS（0.26 s） | PASS（0.31 s） |
| 19 | Ctrl+Break 正常退出：退出码 0，生成 WARNING“正常退出”告警 | PASS | PASS |
| 20 | 关闭 Broker，后端检测到断开 | PASS（0.013 s） | PASS（0.007 s） |
| 21–22 | 命令返回 503 MQTT_UNAVAILABLE；只生成一条 CRITICAL 告警 | PASS | PASS |
| 23 | 重启 Broker，后端与桥接节点自动重连并恢复在线 | PASS（1.6 s） | PASS（1.6 s） |
| 24 | 恢复后控制正常 | PASS | PASS |
| | **合计** | **25 / 25** | **25 / 25** |

时间说明：

- 第 8 步“看到 STOPPED”的时间 = 0.6 s 看门狗 + 最长 0.5 s 状态上报周期 + 50 ms 轮询，所以落在 0.6–1.2 s 之间。看门狗本身在最后一条命令后 0.60–0.65 s 触发（单元测试中用确定性时钟验证）。“漂移 0.000 m”说明停车后底盘确实不再移动。
- 第 14 步的时间短，是因为进程被杀时 TCP 连接立即关闭，Broker 马上发布遗嘱。网络静默中断时，要靠后端 3 s 心跳超时或 Broker 的 1.5 × keepalive 才能发现。
- 以上都是本机回环网络上的数值，实车 Wi-Fi 下会更长，需要在 DEPLOYMENT.md 第 5 节的清单中实测记录。

## 6. 未验证项与风险 ❓

| 项 | 说明 |
| --- | --- |
| 实车运行 | ROS1/ROS2 适配器只用替身模块验证过接线，没有连真实的 rospy/rclpy、scout_base 和底盘 |
| 小车侧系统 | 桥接节点只在 Windows 上运行过；Linux 下的信号处理和 `start_bridge.sh` 只做了 `bash -n` 语法检查 |
| Mosquitto | 自动化测试用的是 amqtt。两者都实现 MQTT 3.1.1 标准，部署前用 `mosquitto_sub` 复核即可 |
| TLS、Broker 账号 | 配置与代码路径已具备，只有单元测试，没有做端到端测试 |
| 时序参数 | 看门狗 0.6 s、保活 200 ms、心跳超时 3 s 需要在实车 Wi-Fi 下确认（TBD-008/009） |
| 电池阈值 | SCOUT MINI 的正常/低电电压未知，告警阈值默认关闭，需要实车测量后再设置 |
