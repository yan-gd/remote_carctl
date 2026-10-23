# 智能巡检管理平台后端（exp3）

以 exp2 后端（COLA 5.0 多模块、Spring Boot 2.7.2、SQLite、JWT）为基线增量开发：保留认证、路线/任务数据管理、告警和操作日志的全部 17 个接口，新增经 **MQTT** 与松灵 SCOUT MINI 通信的机器人接口（基本信息、运行状态、运动/停止控制）、心跳在线判定和设备告警。小车侧的 MQTT↔ROS 桥接节点在 [`../robot-bridge`](../robot-bridge)。仍没有前端、导航/返航、调度或视频功能。

总体说明、部署与联调步骤见 [`../README.md`](../README.md)；MQTT 协议见 [`../docs/MQTT-PROTOCOL.md`](../docs/MQTT-PROTOCOL.md)；测试结果见 [`../docs/TEST-REPORT.md`](../docs/TEST-REPORT.md)。exp2 阶段的设计文档、答辩 PPT、演示流程保留在 `exp2/docs`，未复制到此处。

## 构建与启动

需要 JDK 17+ 和 Maven 3.9.11。本机验证使用 JDK 21.0.5，编译使用 `--release 17`，Spring Boot 保持 2.7.2。新增依赖：`org.eclipse.paho.client.mqttv3` 1.2.5、`jackson-databind`（版本由 Spring Boot 管理）。

在 `exp3/backend` 目录：

```sh
mvn clean test
mvn clean install
java -jar start/target/start-1.0-SNAPSHOT.jar
```

或先完成 `mvn clean install`，再在 `start` 目录执行：

```sh
mvn spring-boot:run
```

默认仅监听 `127.0.0.1:8080`。Swagger：<http://localhost:8080/swagger-ui.html>；OpenAPI JSON：<http://localhost:8080/v3/api-docs>。可用 `PORT`、`BIND_ADDRESS` 调整监听配置。

首次启动自动创建 `exp3/backend/data/exp2.db` 和父目录（文件名沿用 exp2，与 exp2 的库互不影响）；从 `exp3/backend`、`exp3/backend/start` 启动会向上查找同一项目根。启动其他位置的独立 JAR 时必须设置 `PATROL_DB_PATH` 为绝对路径。此配置也允许测试隔离数据库，不接受相对路径以避免意外生成两套库。

MQTT 默认开启并连接 `tcp://127.0.0.1:1883`，用 `PATROL_MQTT_URL` 指向小车与后端共用的 Broker（变量清单见 [docs/API.md](docs/API.md) 1.1 节）。Broker 不可用时服务照常启动、后台每 5 秒重连，控制命令返回 503；`PATROL_MQTT_ENABLED=false` 可完全关闭（测试即如此运行）。

## 演示账号（显式启用）

普通注册只能得到 VIEWER，不能传入 role、creatorId 等额外字段。没有管理员提权接口。要演示写操作，在 PowerShell 执行：

```powershell
.\scripts\Run-Demo.ps1
```

脚本在本机安全提示输入初始密码。也可自行设置 `PATROL_DEMO_ENABLED=true` 和 `PATROL_DEMO_PASSWORD` 后运行 JAR。仅首次插入以下账号，重复启动不修改已有密码或角色：

| 用户名 | 角色 | 权限 |
| --- | --- | --- |
| demo_operator | OPERATOR | 路线/任务增删改；机器人全部运动命令；全部路线、任务、告警查询；本人日志 |
| demo_monitor | MONITOR | 告警确认；机器人 STOP；全部路线、任务、告警查询；本人日志 |
| demo_maintainer | MAINTAINER | 路线、任务、告警、全部操作日志查询 |
| demo_viewer | VIEWER | 路线、任务、告警、全部操作日志查询 |

演示模式插入一条 `source=DEMO_FIXTURE` 告警，描述明确表示测试夹具。默认不开启，不声称来自真实设备。演示模式缺少合法密码会拒绝启动。初始化不清库，不覆盖已有用户，告警依靠唯一 seed_key 防止重复。

## API 约定与示例

统一前缀 `/api/v1`。成功结构 `{"code":"SUCCESS","message":"success","data":...}`。新建返回 HTTP 201；查询、更新、确认、删除、机器人控制返回 HTTP 200（删除 data=null）。错误返回真实 HTTP 400/401/403/404/409/500/503，绝不伪装成功。分页为 `data.items/total/page/size`，page从1开始，size为1-100，最大页码1000000。

完整接口文档（20 个操作、字段说明、请求/响应示例、权限矩阵）见 [docs/API.md](docs/API.md)。

| 方法 | 路径（相对于 /api/v1） | 用途 |
| --- | --- | --- |
| POST | /auth/register | 注册，只允许VIEWER |
| POST | /auth/login | 登录，获取JWT |
| GET | /auth/me | 当前用户 |
| POST / GET | /routes | 新建 / 分页列表 |
| GET / PUT / DELETE | /routes/{id} | 详情 / 更新 / 删除 |
| POST / GET | /tasks | 新建 / 分页列表 |
| GET / PUT / DELETE | /tasks/{id} | 详情 / 更新 / 删除 |
| GET | /alarms | 分页、时间、级别、状态筛选 |
| GET | /alarms/{id} | 告警详情 |
| PATCH | /alarms/{id}/ack | 确认；重复或并发重复确认409 |
| GET | /control-logs | 日志分页及操作类型、时间筛选 |
| GET | /robot | 唯一机器人基本信息、在线摘要 |
| GET | /robot/status | 在线、运动状态、电压、速度、里程计位姿、MQTT 状态、最近回执；拿不到的为 null |
| POST | /robot/control | `{"command":"FORWARD"}`；OPERATOR 全部命令，MONITOR 仅 STOP；按住期间每 200 ms 重发 |

在 Swagger 中先调用登录：

```json
{"username":"demo_operator","password":"你设置的演示密码"}
```

复制返回的 `data.token`，点右上角 **Authorize**，输入Token本身（界面自动加Bearer）。创建路线：

```json
{"name":"仓库东区路线","description":"本阶段只维护路线数据","points":[{"sequence":0,"x":1.5,"y":2.0,"yaw":0},{"sequence":1,"x":3.0,"y":4.5}]}
```

创建任务（routeId替换为真实返回ID）：

```json
{"name":"早班巡检计划","routeId":1,"plannedAt":"2026-10-20T08:00:00+08:00","status":"DRAFT"}
```

任务状态：`DRAFT`（草稿）、`PLANNED`（已计划）、`CANCELLED`（已取消）。草稿与计划可互转，也可取消；PLANNED必须有计划时间，取消后不可修改，只能删除。不能创建CANCELLED任务；不提供RUNNING/COMPLETED等虚假设备执行状态。PUT完整更新业务字段，任务创建人从认证上下文取得且不可修改。引用中的路线即使关联任务已取消也不可删除，须先删除引用任务。

点位按sequence排序且序号唯一；坐标为有限数，yaw范围[-π,π]。路线最多200个点位。用户名3-32位字母/数字/下划线，不区分大小写唯一；密码为8-72位非空格ASCII字符（确保不越过BCrypt的72字节边界）。未知JSON字段一律400。

筛选示例：

```text
GET /api/v1/tasks?page=1&size=20&status=PLANNED
GET /api/v1/alarms?level=WARNING&status=UNACKNOWLEDGED&from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00Z
GET /api/v1/control-logs?operation=ROUTE_CREATE&page=1&size=20
```

时间均为带时区ISO-8601输入，统一以UTC返回；数据库存UTC epoch毫秒。from/to含边界，from>to返回400。告警级别INFO/WARNING/CRITICAL，状态UNACKNOWLEDGED/ACKNOWLEDGED。

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | INVALID_ARGUMENT / INVALID_COMMAND | 参数、枚举、时间、JSON格式不合法；控制命令不在白名单 |
| 401 | UNAUTHORIZED / TOKEN_EXPIRED / LOGIN_FAILED | 无凭证、凭证无效或过期、登录失败 |
| 403 | FORBIDDEN | 角色不允许 |
| 404 | NOT_FOUND | 资源或接口不存在 |
| 409 | BUSINESS_CONFLICT / DATA_CONFLICT / ROBOT_OFFLINE | 重复账号、重复确认、引用约束、非法状态变化；机器人离线时的运动命令 |
| 500 | DATABASE_ERROR / INTERNAL_ERROR | 数据服务或内部异常（不泄漏SQL/堆栈） |
| 503 | MQTT_UNAVAILABLE | MQTT 不可用，命令未下发 |

## 分层与数据库

- `exp2-client/src/main/java/org/example/patrol/client`：DTO、Command、Query、统一响应、应用服务接口。
- `exp2-app/src/main/java/org/example/patrol/app`：Command/Query Executor、用例编排和事务；成功操作与审计日志一起提交，失败日志在独立事务记录。
- `exp2-domain/src/main/java/org/example/patrol/domain`：用户、路线、任务、告警、领域规则、Gateway接口；不依赖Client、MVC、MyBatis或SQLite。
- `exp2-infrastructure/src/main/java/org/example/patrol/infrastructure`：DO、MyBatis Mapper、SQLite Gateway、BCrypt、JWT技术实现。
- `start/src/main/java/org/example/patrol`：Controller、鉴权、异常映射、OpenAPI、数据库/密钥初始化。
- `start/src/main/resources/schema.sql`：六张业务表、索引、CHECK/UNIQUE/外键约束。CREATE TABLE/INDEX IF NOT EXISTS，可重复执行，无DROP/TRUNCATE。exp3 没有改表结构：机器人告警写入 `alarm_record`，控制操作写入 `control_log`。

exp3 新增的机器人相关代码：

| 层 | 类 | 职责 |
| --- | --- | --- |
| client | `RobotModels`、`RobotService` | 请求/响应模型与应用服务接口 |
| domain | `RobotCommand`、`MotionState`、`RobotTelemetry`、`RobotHeartbeat`、`RobotAck`；`gateway/RobotGateway`、`gateway/RobotListener` | 命令白名单、遥测校验（非有限或越界的值置 null）；MQTT 端口抽象。`BusinessException.Kind` 新增 `UNAVAILABLE`→503 |
| app | `RobotMonitor` | 内存态：心跳（含遗嘱）、状态、回执；心跳超时判离线；断连、离线、电量过低、底盘无数据按状态跳变各生成一次告警 |
| app | `executor/RobotCommandExecutor`、`RobotServiceImpl`、`RobotSettings` | 角色/白名单/在线/MQTT 校验、发布、保活去重审计 |
| infrastructure | `mqtt/MqttRobotGateway`、`mqtt/RobotMessages`、`mqtt/MqttSettings`、`mqtt/DisabledRobotGateway` | Paho 客户端（QoS1、不保留、clean session、定间隔重连、入站单线程处理）、JSON 编解码；`AlarmGateway.insertAlarm` |
| start | `web/RobotController`、`config/RobotConfiguration` | 接口、属性绑定、在所有监听者就绪后再连接 Broker；`SecurityConfiguration`、`GlobalExceptionHandler`、`OpenApiConfiguration` 增量修改 |

SQLite每个池连接启用外键、busy_timeout=5000、WAL；事务采用IMMEDIATE，提前获得写预留，避免先读后写升级竞争。路线点位级联删除，任务引用RESTRICT，告警确认使用带旧状态条件的UPDATE。连接池4个连接；不存在外部数据库服务或MySQL驱动。

JWT为HS256、签发者patrol-management，默认1小时有效期。每次请求从数据库加载当前角色，避免权限变化后仍沿用旧Token角色。设置 `PATROL_JWT_SECRET`（Base64编码的至少32字节随机密钥）可覆盖本地密钥；默认在数据库旁生成 `<数据库文件名>.jwt-key`，重启保留。数据库与密钥应一并保管，不纳入Git。运行日志不记录请求体、密码、JWT或SQL参数。

原 Customer/Order 示例源码保留；启动扫描只包含 `org.example.patrol`，旧接口和错误的示例Mapper XML均不加载。原有adapter模块保留在Maven reactor中，但start直接装配app/infrastructure，核心Controller位于start。App不再依赖Infrastructure。

## 验证与报告

```sh
mvn clean test
mvn clean install
python scripts/verify_process_restart.py
```

JUnit 5覆盖领域边界、Mockito编排、MockMvc实际JWT/SQLite接口、数据库外键、回滚、错误转换、并发确认和真实HTTP服务重启；exp3 新增机器人领域规则、`RobotMonitor` 状态机与告警去重、`RobotCommandExecutor` 校验顺序与保活去重、MQTT 报文编解码、无 Broker 时的网关行为，以及机器人接口的 MockMvc 测试。测试中 MQTT 由 surefire 系统属性关闭，接口测试用替身网关。所有测试使用独立临时文件，演示测试明确标为夹具，绝不使用正式数据库。原JUnit4示例未引入Vintage执行，不计入有效业务测试。

`MqttRobotGatewayBrokerTest` 需要真实 Broker，默认跳过；设置环境变量后运行：

```powershell
$env:MQTT_TEST_URL = "tcp://127.0.0.1:1883"; mvn clean test
```

JaCoCo聚合HTML：`start/target/site/jacoco-aggregate/index.html`，XML同目录；各模块Surefire报告在对应`target/surefire-reports`。聚合范围为 `org.example.patrol` 生产代码，原示例不计入；JaCoCo自身过滤Lombok生成的访问器。测试数量与覆盖率见 [`../docs/TEST-REPORT.md`](../docs/TEST-REPORT.md)。

Python脚本使用两个独立JVM，先从根目录启动，经API创建路线并用SQLite查询记录，再停止进程、从start目录重启、使用原Token查询同一路线；同时检查Swagger（20 个操作）和初始化幂等性。该脚本只验证持久化，启动时关闭 MQTT。结果写入`verification/process-proof.json`，不输出密码或JWT。

与 Broker、桥接节点一起的端到端联调脚本见 `exp3/scripts/e2e_sim.py`。
