# exp3 项目进度与 Agent 交接

更新时间：2026-10-23。本文记录代码状态、用户提供的现场结果和后续工作边界，供后续 Agent 接续；不是新一轮测试报告。部署命令统一见 [README 的 Quick Start](README.md#3-quick-start服务器--小车端)。

## 1. 接手时先知道这些

- 当前工作是 **exp3 的服务器与真实小车部署联调**，不是重新实现 exp2。后端业务、MQTT 与 ROS 桥接代码已存在，不要重建脚手架。
- 2026-10-23 新增目标：开发 **Flutter Web 前端**（`frontend/`），前后端分离并部署到 `agilex.yoyoyan.cn`。登录、总览、按住控制、告警、日志已完成并通过本地全栈联调；**尚未部署到云服务器**（SSH 公钥登录被拒，DNS 与服务器操作需用户执行）。本轮同时给后端增加了可配置 CORS 白名单（开发端口 + 生产公网源），并在真实 nginx 上完成了 HTTPS 反向代理联调。
- 已确认：服务器后端和 Broker 运行、Swagger 公网可访问；小车底盘真实遥测、MQTT 心跳、后端状态查询和 STOP 回执已打通。
- 用户最新要求是将部署经验写入 README，再新增本交接文档。README 已提交并推送；本文是此后新增的文件，其提交/推送状态应以实际 `git status` 为准。
- 小车桥接的 systemd 安装命令已提供并写入 README，但尚未收到安装后 `systemctl status`、日志或重启验收输出，**不能将无人值守开机恢复记为已验证**。
- **尚无完整的真实运动、真实断网停车耗时和整机重启恢复测量记录。** STOP 的 HTTP 成功、桥接 APPLIED 回执不等于物理运动测试完成。
- 本地工作环境是 Windows PowerShell；服务器和小车由用户在终端执行命令并反馈，本轮 Agent 没有直接 SSH 操作两台设备。

## 2. 仓库与资料入口

| 项目 | 位置 / 状态 |
| --- | --- |
| 本地 exp3 独立 Git 仓库 | `E:\qq157\Desktop\class\software\exp3` |
| 远程 | [yan-gd/remote_carctl](https://github.com/yan-gd/remote_carctl) |
| 分支 | `main` |
| 本文创建前最近一次已推送提交 | `e846647` — `docs: add server and Xavier deployment quick start` |
| 当前工作区 | README、backend 安全/代理/CORS 配置与测试有修改；`PROGRESS.md`、`frontend/`、`WebCorsConfiguration*` 未提交；提交/推送状态以实际 `git status` 为准 |
| 服务器 / 小车完整操作命令 | [README.md 第 3 节](README.md#3-quick-start服务器--小车端) |
| 前端代码与开发说明 | [frontend/README.md](frontend/README.md) |
| 前端部署（nginx/HTTPS/限流） | [frontend/deploy/README.md](frontend/deploy/README.md) |
| 后端全部 API | [backend/docs/API.md](backend/docs/API.md) |
| MQTT 协议 | [docs/MQTT-PROTOCOL.md](docs/MQTT-PROTOCOL.md) |
| 历史自动化测试报告 | [docs/TEST-REPORT.md](docs/TEST-REPORT.md) |
| 早期通用部署文档 | [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)，其中 Melodic、launch、服务路径等旧示例不适用本次机器，以 README 为准 |

`exp2` 与 `doc` 在 exp3 的上一级。本次不要顺带改动 exp2、课程 PPT、前端或其他历史产物。后续 Agent 先检查工作区，不要覆盖用户新改动，不改写 Git 历史。

## 3. 已有功能和代码位置

后端为 Java 17、Spring Boot 2.7.2、COLA 5.0、MyBatis、SQLite、Spring Security/JWT、Springdoc。沿用原有 Maven 模块，没有切换 FastAPI、MySQL 或新建后端。

| 范围 | 当前内容 | 主要位置 |
| --- | --- | --- |
| 原有业务 | 注册/登录/当前用户、角色权限、路线与任务 CRUD、告警查询确认、操作日志，共 17 个 API 操作 | `backend/exp2-client`、`exp2-app`、`exp2-domain`、`exp2-infrastructure`、`start` |
| 机器人 API | GET `/api/v1/robot`、GET `/api/v1/robot/status`、POST `/api/v1/robot/control` | `backend/start/src/main/java/org/example/patrol/web/RobotController.java` |
| DTO 和服务契约 | 机器人响应、控制请求、服务接口 | `backend/exp2-client/src/main/java/org/example/patrol/client/RobotModels.java`、`RobotService.java` |
| 控制用例 | 权限、在线检查、发布命令和审计 | `backend/exp2-app/src/main/java/org/example/patrol/app/executor/RobotCommandExecutor.java` |
| 在线/遥测/设备告警 | 心跳超时、遥测过期、断线与低电量告警 | `backend/exp2-app/src/main/java/org/example/patrol/app/RobotMonitor.java` |
| MQTT 技术实现 | Broker 连接、订阅、重连、消息编解码 | `backend/exp2-infrastructure/src/main/java/org/example/patrol/infrastructure/mqtt/` |
| 数据库与身份初始化 | SQLite、建表、JWT 密钥、显式演示数据 | `backend/start/src/main/java/org/example/patrol/config/`、`backend/start/src/main/resources/schema.sql` |
| 小车桥接核心 | 命令、序号、看门狗、遥测、Paho MQTT | `robot-bridge/scout_bridge_core.py` |
| ROS 适配与入口 | ROS1 / ROS2 / 显式 sim 适配、信号与退出处理 | `robot-bridge/scout_mqtt_bridge.py` |
| 前端 | Flutter Web：登录、总览、控制、告警、日志；内存 JWT、401 退出、角色禁用、按住续发与 STOP | `frontend/lib/`、`frontend/deploy/` |
| 联调工具 | 命令行控制、HTTP 客户端、模拟端到端测试 | `scripts/robot_cli.py`、`patrol_client.py`、`e2e_sim.py` |

关键业务边界：

- 所有已登录角色可读机器人状态；OPERATOR 可发送全部五种命令，MONITOR 仅 STOP，其他角色不能控制。
- 命令只有 `FORWARD`、`BACKWARD`、`LEFT`、`RIGHT`、`STOP`，区分大小写。
- MQTT 不可用返回 503；运动命令要求机器人在线，否则 409；STOP 不受在线检查限制，但仍要求 MQTT 可用。
- 后端发布返回 `accepted=true` 代表 Broker 发布确认；`lastAck` 是桥接回执，不是设备物理执行证明。
- 路线和任务仍是数据管理，不会执行自主巡逻。没有实现导航、SLAM、视频、WebSocket、前端、任务下发调度。

## 4. 服务器现场状态

| 项目 | 实际值 / 已有证据 |
| --- | --- |
| 地址 | `47.109.132.238`；登录用户 `root` |
| 后端目录 | `/opt/remote_carctl/backend` |
| Java / 构建 | Java `17.0.20.1`；用户执行 `mvn clean install` 并贴出 `BUILD SUCCESS` |
| JAR | `/opt/remote_carctl/backend/start/target/start-1.0-SNAPSHOT.jar` |
| 后端服务 | `remote-carctl.service`；用户已贴出 active、Spring Boot 启动和 MQTT 连接日志 |
| 后端配置 | `/etc/remote-carctl/backend.env`，权限 600；目录权限 700 |
| HTTP | `BIND_ADDRESS=0.0.0.0`、`PORT=8080`；`ss` 显示 Java 监听 `*:8080` |
| SQLite | `PATROL_DB_PATH=/opt/remote_carctl/backend/data/exp2.db` |
| Broker | Mosquitto，`mosquitto.service` 已启用并运行，监听 1883 |
| MQTT 密码文件 | `/etc/mosquitto/exp3.passwd`，root:mosquitto，640 |
| Broker 配置 | `/etc/mosquitto/conf.d/exp3.conf`，显式 listener、禁止匿名、密码认证 |
| 后端 MQTT | `tcp://127.0.0.1:1883`，用户名 `robot`，robot ID `SCOUT-001` |
| Swagger | [http://47.109.132.238:8080/swagger-ui.html](http://47.109.132.238:8080/swagger-ui.html) |
| OpenAPI | `http://47.109.132.238:8080/v3/api-docs` |

服务 ExecStart 是 `/usr/bin/java -jar .../start-1.0-SNAPSHOT.jar`，WorkingDirectory 是 `/opt/remote_carctl/backend`。环境文件中 `PATROL_DEMO_ENABLED=false`，因为演示账号已在第一次显式初始化时写入数据库。

后端账号使用 `demo_operator` 等演示用户名及用户当时设置的后端密码。**MQTT 用户 robot 不能直接用于后端登录**。初始化只补缺失用户，不会重置已有密码；普通注册只产生 VIEWER。

默认 JWT 密钥存于数据库旁边的 `exp2.db.jwt-key`（未显式设置 `PATROL_JWT_SECRET` 时）。保留数据库和密钥，不通过删除它们“修复”账号或部署问题。运行数据库、密码和 JWT 不提交到 Git，也不写入交接文档。

曾遇到的公网访问问题已解决：云安全组虽然允许 8080、1883，但 UFW 当时没有对应规则；放行后用户确认 Swagger 打开。本机 `/auth/me` 不带 Token 返回统一 401 属于正常现象。

## 5. Xavier 小车现场状态

| 项目 | 实际值 / 已有证据 |
| --- | --- |
| 用户 / 目录 | `agilex`；`/home/agilex/Desktop/remote_carctl` |
| ROS | ROS1 Melodic；`/opt/ros/melodic`；已编译工作空间 `/home/agilex/catkin_ws` |
| ROS 命令解释器 | `/usr/bin/python2.7` |
| 桥接解释器 | `/home/agilex/archiconda3/bin/python3`，Python `3.7.1` |
| Python 依赖 | 已指导安装 PyYAML 6.0.1、paho-mqtt 1.6.1、rospkg 1.5.1、catkin_pkg 1.0.0；用户确认导入成功并已启动桥接 |
| CAN | `can0`，500000 bit/s，标志 UP / LOWER_UP，CAN 状态 ERROR-ACTIVE |
| 底盘驱动 | `scout_base scout_mini_base.launch`，参数 is_scout_mini=True、port_name=can0、simulated_robot=False |
| Topic | `/cmd_vel`、`/odom`、`/scout_status`、`/rosout`、`/tf` 等已列出 |
| 真实遥测样本 | 静止速度 0、battery_voltage=25.2、fault_code=0、control_mode=0；均为当时值 |
| 桥接配置 | `/home/agilex/Desktop/remote_carctl/robot-bridge/config.yaml` |
| MQTT | host `47.109.132.238`、port 1883、username `robot`、robot_id `SCOUT-001` |

已经跑通的手动启动方式：

```bash
source /opt/ros/melodic/setup.bash
source /home/agilex/catkin_ws/devel/setup.bash

# 终端一：真实底盘驱动，保持运行
/usr/bin/python2.7 /opt/ros/melodic/bin/roslaunch \
  scout_base scout_mini_base.launch

# 终端二：手动桥接。已有桥接系统服务运行时，不要同时启动这一份。
cd /home/agilex/Desktop/remote_carctl/robot-bridge
/home/agilex/archiconda3/bin/python3 \
  scout_mqtt_bridge.py --ros 1 --config config.yaml
```

服务器已收到三条连续、约 1 Hz 的真实心跳，内容包含：

```json
{"robot_id":"SCOUT-001","online":true,"chassis_connected":true,"ros":"ros1","watchdog_trips":0}
```

这是摘录，原报文还有 timestamp。随后 STOP 截图返回 HTTP 200、accepted=true、seq=2；在要求核对状态和 lastAck 后，用户反馈“都可以了”。没有完整的最新状态 JSON 或物理运动测量留档，不扩展解释这个反馈。

### 桥接系统服务的交接状态

已提供的安装配置见 README 3.7；并非仓库里已有的 `.service` 文件：

- `/usr/local/bin/start-scout-mqtt-bridge`：加载 Melodic 和 catkin 工作空间，设置 `ROS_MASTER_URI=http://localhost:11311`，等待 Master，再 exec 指定 Conda Python。
- `/etc/systemd/system/scout-mqtt-bridge.service`：User=agilex、Restart=always、RestartSec=5、SIGTERM 正常停止、日志进入 journal。
- `config.yaml` 权限设置为 600，保留小车现有 MQTT 密码。
- 不负责配置 CAN、不启动底盘驱动。开机只有服务进程等待，并不代表整车已经可用。
- **安装执行、注销终端后持续运行、系统重启恢复，均还需要现场输出或实际测试确认。** 不要根据用户认可安装方案就写成完成验收。

## 6. 已排查问题：不要重复走弯路

| 现象 | 已确定的原因 / 处理 |
| --- | --- |
| `Device or resource busy` 设置 CAN | 当时 can0 已 UP 且 500000，不需要先 down 再 up；ERROR-ACTIVE 正常，state UNKNOWN 也不等于故障 |
| launch 文件找不到 | 本车没有 `scout_bringup scout_mini_robot_base.launch`，实际是 `scout_base scout_mini_base.launch` |
| Python3 导入 rospy 报缺 yaml | Conda Python 缺依赖；按 README 固定版本安装后通过，不升级 ROS、不修改系统 Python |
| MQTT 线程 `object of type 'int' has no len()` | YAML 数字密码未加引号，被解析为 int；给密码加引号后恢复，代码目前未新增此项类型校验 |
| 桥接只有 `topicmanager initialized` | 日志安静不能直接证明卡死；用 Broker 的实时心跳和 API 检查 |
| MQTT 已连接但控制 409 | Broker 连接与机器人在线不同；需收到本机器人新鲜心跳。STOP 本身允许离线，单凭 STOP 的 200 不能证明在线 |
| Swagger 登录 401 | 误用了 MQTT 的 robot 用户；后端用 demo_operator 和单独的后端密码 |
| Git push 连接 127.0.0.1:7890 失败 | 本机 Git 代理不可用；上一轮使用单次命令覆盖后成功，没有更改全局代理配置 |

`robot-bridge/start_bridge.sh` 仍是通用脚本：自动发现列表没有 Melodic，且检查 `state UP` 会误报正常的 CAN UNKNOWN。当前机器使用 README 的专用启动命令和服务包装脚本，不要直接用该通用脚本替代。

`control_mode=0` 的含义、遥控器正确档位，尚未根据本机旧版驱动/手册核实。不要凭新版协议猜模式，不要发送猜测的原始 CAN 切换帧。

## 7. 测试记录与可信边界

以下数字来自已有测试报告及此前查阅的测试产物，**不是新增交接文档时重跑所得**：

| 项目 | 历史结果 |
| --- | --- |
| 后端自动化 | 本轮重跑 **102 项**（原有 98 + CORS 4），0 失败、0 错误、**1 跳过**（真实 Broker 用例未设置 `MQTT_TEST_URL`）；历史上包含 Broker 用例的那次为 98 项无跳过 |
| JaCoCo 聚合（org.example.patrol） | 指令 96.33%、分支 88.66%、行 95.65%、方法 96.41%（历史值，本轮未重新汇总覆盖率） |
| 桥接单元测试 | 41 项；Python 3.8 / Paho 1.6.1 与 Python 3.13 / Paho 2.1.0 两套环境通过（历史值，本轮未重跑） |
| 模拟端到端 | 每套 25 项，通过；桥接为 `--ros sim`，不是实车（历史值，本轮未重跑） |
| 前端静态检查与测试 | `flutter analyze` 0 issues；`flutter test` 15 项全部通过（API 客户端 7、按住控制器 5、Widget 3） |
| 前端 release 构建 | `flutter build web --release --no-web-resources-cdn` 成功；CanvasKit 与中文字体均为本地资源 |
| 前端全栈联调（2026-10-23 本地） | 真实 Broker（amqtt）+ 后端 JAR + 桥接 `--ros sim`（21.8 V）+ Chrome：登录、总览、按住前进 1.2 s（≥4 次 keepalive）、松开 STOP、APPLIED 回执、回到 STOPPED、电量过低告警、控制日志；监控员不能前进但可急停；观察员只读；零控制台错误 |
| 后端进程重启持久化 | 历史报告 PASS，20 个操作 |
| 本轮现场构建 | 用户提供服务器 `mvn clean install` 的 BUILD SUCCESS，不据此推断完整测试数量或 Broker 测试是否执行 |
| README 文档检查 | 新增的 17 段 Bash 代码通过 `bash -n`，代码块和相对链接已检查；未执行其中远程安装命令 |
| 前端部署配置检查 | nginx 配置与部署文档已编写；本地用等价的 Node 静态服务器 + `/api` 反向代理验证同源与 CSP，**未在真实 nginx 执行 `nginx -t`**，未部署到服务器 |

`docs/TEST-REPORT.md` 中“未实车测试”是原自动化测试阶段的记录，不能用它否定后续现场已取得的心跳/STOP 证据，也不能把模拟测试的停车耗时套用到实车。

需要重新执行时，按改动范围选择：后端 `mvn clean test` / `mvn clean install`；桥接 `python -m unittest discover -s tests -v`。真实 Broker 测试需按报告配置 `MQTT_TEST_URL` 并核实测试 Broker 的认证环境，不设置时该用例会跳过。临时测试数据库不得污染服务器现有数据库。JaCoCo 报告入口为 `backend/start/target/site/jacoco-aggregate/index.html`。

## 8. 后续建议顺序（尚未全部完成）

1. **部署前端到云服务器**：在 DNS 添加 `agilex.yoyoyan.cn A 47.109.132.238`；服务器按 [frontend/deploy/README.md](frontend/deploy/README.md) 安装 nginx、上传 `frontend/build/web`、把后端 `BIND_ADDRESS` 改为 `127.0.0.1`、用 certbot 申请证书。当前 SSH 公钥登录被拒，需用户执行或提供可用凭据。
2. **验收 HTTPS 与安全配置**：HTTP→HTTPS 301、HSTS/CSP、未登录 API 返回 JSON 401、登录限流 429、Swagger 404；浏览器完整走一遍登录、总览、控制、告警、日志。
3. **确认桥接服务安装结果**：小车 `systemctl status scout-mqtt-bridge --no-pager -l`、`journalctl -u scout-mqtt-bridge -n 50 --no-pager`，后端再查 online/chassisConnected 和 STOP 对应 seq 回执。
4. **明确开机依赖**：若用户要求整机无人值守恢复，再为 CAN 和实际底盘驱动设计服务与依赖关系；这部分当前没有实现，不应声称桥接自启已覆盖它们。
5. **验证真实短时运动**：先核实控制模式、急停、订阅关系，架空车轮、降低配置速度，使用每 200 ms 续发且 finally 发送 STOP 的 CLI。记录方向、速度、回执和物理结果。前端控制按钮也应参与一次实车按住测试。
6. **验证真实停止与恢复**：在可控条件下测量停止续发、网络断开、服务停止、服务重启的表现；系统重启验证需用户现场配合，不擅自重启设备。记录实测时间，勿复用模拟报告数字。
7. **补前端路线/任务页面**：当前控制台只有机器人、告警、日志和登录；`/api/v1/routes`、`/api/v1/tasks` 的增删改查页面尚未实现。
8. **文档收尾**：将新的现场证据追加到本文，必要时更新旧 DEPLOYMENT 文档的过时示例。低电压阈值仍需现场确认，当前默认关闭。

可选的小范围代码修复：MQTT 凭据类型校验、通用脚本兼容本机启动环境。只有用户后续要求相关改动时再开展，不借交接扩大范围；修复应包含有效异常断言。

## 9. 接续工作的操作约定

- 用户偏好中文、可直接复制的分步命令；说明清楚在服务器还是小车执行，结合实际输出继续推进。
- 机器已有 Java、ROS、驱动、数据库和账号，不要求重新安装整套环境，不清库、不改写密码、不重建脚手架。
- 区分“代码实现”“命令已给出”“用户报告成功”“有日志证据”“实测结果”，没有证据的状态保留待确认。
- 不从截图复述 JWT/密码，不将远程 `config.yaml` 的真实凭据复制回仓库。编辑 README 不需要复制配置中的真实密码。
- 文档修改可做语法/链接检查；不能将 `bash -n` 写成服务安装或业务测试已通过。
- 前端改动至少执行 `flutter analyze` 与 `flutter test`；发版构建使用 `flutter build web --release --no-web-resources-cdn`，产物不提交。改中文文案后注意字体子集是否覆盖新字。
- 前端安全边界：JWT 只在内存、生产必须 HTTPS、生产构建不要传 `API_BASE_URL`、`PATROL_WEB_ALLOWED_ORIGINS` 必须显式包含 `https://agilex.yoyoyan.cn`；不要在代码或文档里放真实密码。
- Push 前检查差异、分支和远程。上次代理故障的单次解决命令是 `git -c http.proxy= -c http.https://github.com.proxy= push origin main`；仅在用户授权推送时执行，不修改全局 Git 设置。
- 本文创建这一轮没有执行远程服务安装、设备重启或云服务器部署。下一位 Agent 先检查最新仓库和现场状态，再继续。

## 10. 前端开发与本地联调（2026-10-23 新增）

### 10.1 当前实现

`frontend/` 是 Flutter 3.44 Web 应用，前后端分离，调用现有 `/api/v1/**`：

- 登录：后端 JWT；令牌只在内存，刷新页面退出；401 自动回登录页并提示。
- 总览：机器人名称/编号、在线/MQTT/底盘、真实电压、速度、里程计位姿、最近回执；每秒轮询，失败保留旧快照并标“数据可能过期”。
- 控制：按住方向键每 200 ms 续发；松开、指针取消、标签页隐藏、退出登录都发 STOP；请求串行化，STOP 等待在途 keepalive 后发送；独立急停按钮；离线/角色不符时禁用。
- 告警：级别/状态筛选、分页；MONITOR 可在页面确认。
- 日志：操作筛选、分页；后端按角色限制本人/全部范围。
- 后端：新增 `patrol.web.allowed-origins`（`PATROL_WEB_ALLOWED_ORIGINS`）CORS 白名单，禁止 `*`；开发端口与生产公网源都必须显式列出；新增 4 项单元测试；新增 `server.forward-headers-strategy=framework` 以识别 nginx 转发的协议/Host。

文件入口：`frontend/lib/main.dart`、`frontend/lib/state/app_state.dart`、`frontend/lib/ui/pages/`；开发与构建说明见 [frontend/README.md](frontend/README.md)。

### 10.2 验证结果与证据

- `flutter analyze`：0 issues；`flutter test`：15 项全部通过。
- `flutter build web --release --no-web-resources-cdn`：成功；CanvasKit 与中文字体本地化，无外部 CDN / Google Fonts 请求。
- 全栈联调环境（本机临时目录）：amqtt 0.12.1 Broker、后端 JAR（临时 SQLite、演示密码、`low-battery-voltage=22.0`）、桥接 `--ros sim`（21.8 V）、Node 静态服务器 + `/api` 反代（等价生产同源）、Chrome 156 headless。
- 结果：操作员登录 → 总览在线/21.8 V；按住前进 1.2 s 观察到多次 FORWARD、松开有 STOP、APPLIED 回执、状态回到 STOPPED；告警页出现“电量过低”；日志页有 `机器人控制`；监控员不能前进但可 STOP；观察员只读；零页面异常、零控制台错误。
- **真实 nginx 验证**：Windows nginx 1.28.0 使用 `nginx-tls.conf`（仅替换证书/路径/端口，自签证书）`nginx -t` 通过，HTTPS 8443 + 自签证书下完整 E2E 再次通过；CSP/HSTS/安全头、`/api` 反向代理、Swagger 404、HTTP 301 均已实测。过程中发现并修复：浏览器经反向代理 POST 时 Spring 把同源请求当作跨域而 403，生产必须在 `PATROL_WEB_ALLOWED_ORIGINS` 显式列出 `https://agilex.yoyoyan.cn`，并透传 `Host`/`X-Forwarded-*`。
- 该联调**没有使用真车**，也**没有在目标服务器**上执行；不能替代实车运动、断网停车与云端部署验收。

### 10.3 安全设计

- JWT 仅存内存，不写 localStorage/sessionStorage/URL/日志；刷新需重新登录。
- 生产强制 HTTPS（非回环 `http://` 的 `API_BASE_URL` 会使应用拒绝启动）；nginx 配置 HSTS、CSP、`X-Content-Type-Options`、`X-Frame-Options`、`Referrer-Policy`。
- CSP 为 `default-src 'self'`、`connect-src 'self'`、`script-src 'self' 'wasm-unsafe-eval'`；发布前在本地等价服务器验证通过。
- 角色控制仍以后端为准；前端只隐藏/禁用按钮。
- nginx 对登录接口限流（10 r/min，burst 5），生产默认 404 掉 `/swagger-ui`、`/v3/api-docs`。
- **CORS 必须显式列出生产源**：浏览器 POST 带 `Origin`，而 Spring 只看到 nginx 代理后的请求；`PATROL_WEB_ALLOWED_ORIGINS` 未包含 `https://agilex.yoyoyan.cn` 时登录返回 403。已启用 `server.forward-headers-strategy=framework` 并透传 `Host`/`X-Forwarded-*`。
- 后端生产建议只监听 `127.0.0.1:8080`，公网只开 80/443；云安全组与 UFW 都要同步。

### 10.4 未完成与边界

- **没有部署到云服务器**：本机 SSH 到 `root@47.109.132.238` 返回 `Permission denied (publickey,password)`，需要用户提供密码/密钥或由用户执行命令。2026-10-23 实测：`agilex.yoyoyan.cn` 已解析到该服务器；`http://` 返回**主机面板的“没有找到站点”默认页**（nginx + HSTS），`/api/*` 404，`https://` 尚无可用证书。
- 若服务器使用宝塔/BT 面板，应按 [frontend/deploy/README.md](frontend/deploy/README.md) 附录 A 在面板添加站点，而不是覆盖系统 nginx 配置。
- nginx 配置已在本机 nginx 1.28.0（Windows 测试构建）通过 `nginx -t` 并用自签证书完成 HTTPS + 完整 E2E；**尚未在目标服务器的 nginx（可能是宝塔面板）上执行**，证书路径/面板差异仍需现场确认。
- 路线/任务页面未实现；前端目前只覆盖登录、机器人、告警、日志。
- 未做前端实车联调、真实断网停车耗时、移动端适配和浏览器兼容矩阵（仅 Chrome）。
- 构建产物 `frontend/build/` 被 `.gitignore` 排除，不提交。

### 10.5 部署准备状态

已提供可直接使用的产物与配置：

- [frontend/deploy/README.md](frontend/deploy/README.md)：DNS、nginx 安装、上传、证书、验收、更新/回滚、安全检查清单。
- [frontend/deploy/nginx-http.conf](frontend/deploy/nginx-http.conf)（首证书前使用）、[nginx-tls.conf](frontend/deploy/nginx-tls.conf)（正式 HTTPS）、[security-headers.conf](frontend/deploy/security-headers.conf)。
- [frontend/README.md](frontend/README.md)：开发、构建、设计说明。
- 本机构建产物在 `frontend/build/web/`，打包文件 `frontend/build/agilex-web.zip`（被 `.gitignore` 忽略，不提交）；可用 scp 上传到服务器。
- 生产 `backend.env` 需包含 `PATROL_WEB_ALLOWED_ORIGINS=https://agilex.yoyoyan.cn`，否则浏览器登录 403。

下一位 Agent 应从“用户是否已添加 DNS、是否有服务器 SSH 权限”开始确认，再继续第 8 节第 1–2 步。
