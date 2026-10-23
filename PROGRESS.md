# exp3 项目进度与 Agent 交接

更新时间：2026-10-24 凌晨（Asia/Shanghai）。本文记录代码状态、用户提供的现场结果和后续工作边界，供后续 Agent 接续；不是新一轮测试报告。**第 11 节记录前端视觉改版与线上部署；视觉约束已覆盖第 10 节的旧配色与字体描述。** 部署命令统一见 [README 的 Quick Start](README.md#3-quick-start服务器--小车端)。

## 1. 接手时先知道这些

- 当前工作是 **exp3 的服务器与真实小车部署联调**，不是重新实现 exp2。后端业务、MQTT 与 ROS 桥接代码已存在，不要重建脚手架。
- 2026-10-23 新增目标：开发 **React 19 + TypeScript + Vite 7 前端**（`frontend/`），前后端分离并部署到 `agilex.yoyoyan.cn`。登录、总览、巡检（路线/任务）、按住控制、告警、日志已完成并通过本地全栈联调；**2026-10-23 晚间已部署到云服务器**（BT 面板，站点目录 `/www/wwwroot/agilex.yoyoyan.cn`，acme.sh 签发 Let's Encrypt 证书）。公网 HTTPS、HSTS/CSP、API 401、Swagger 404 和 smoke test 已验证；真实账号登录与机器人控制仍待浏览器验收。
- 已确认：服务器后端和 Broker 运行、Swagger 公网可访问；小车底盘真实遥测、MQTT 心跳、后端状态查询和 STOP 回执已打通。
- 用户随后要求将前端从 Flutter 切换为 **React 19 + TypeScript + Vite 7**。React 版本已提交并推送（`11e7db2`、`861f88f`）；Flutter 版本仍保留在 Git 历史 `595af1d`，工作区已不包含 Flutter。
- 小车桥接的 systemd 安装命令已提供并写入 README，但尚未收到安装后 `systemctl status`、日志或重启验收输出，**不能将无人值守开机恢复记为已验证**。
- **尚无完整的真实运动、真实断网停车耗时和整机重启恢复测量记录。** STOP 的 HTTP 成功、桥接 APPLIED 回执不等于物理运动测试完成。
- 本地工作环境是 Windows PowerShell；服务器和小车由用户在终端执行命令并反馈，本轮 Agent 没有直接 SSH 操作两台设备。

## 2. 仓库与资料入口

| 项目 | 位置 / 状态 |
| --- | --- |
| 本地 exp3 独立 Git 仓库 | `E:\qq157\Desktop\class\software\exp3` |
| 远程 | [yan-gd/remote_carctl](https://github.com/yan-gd/remote_carctl) |
| 分支 | `main` |
| 最近一次功能提交（已推送） | `a9c77e7` — `feat(frontend): limit dot field to login and add wave animation`；前一条视觉提交为 `49ff8b1 feat(frontend): restyle console with dot background and frosted glass` |
| 当前工作区 | 截至代码提交 `a9c77e7`；本次文档更新随后提交，具体以 `git log` / `git status` 为准 |
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
| 前端 | React 19 + TypeScript + Vite 7：登录、总览、巡检、控制、告警、日志；内存 JWT、401 退出、角色禁用、按住续发与 STOP | `frontend/src/`、`frontend/deploy/` |
| 联调工具 | 命令行控制、HTTP 客户端、模拟端到端测试 | `scripts/robot_cli.py`、`patrol_client.py`、`e2e_sim.py` |

关键业务边界：

- 所有已登录角色可读机器人状态；OPERATOR 可发送全部五种命令，MONITOR 仅 STOP，其他角色不能控制。
- 命令只有 `FORWARD`、`BACKWARD`、`LEFT`、`RIGHT`、`STOP`，区分大小写。
- MQTT 不可用返回 503；运动命令要求机器人在线，否则 409；STOP 不受在线检查限制，但仍要求 MQTT 可用。
- 后端发布返回 `accepted=true` 代表 Broker 发布确认；`lastAck` 是桥接回执，不是设备物理执行证明。
- 路线和任务仍是数据管理，不会执行自主巡逻。没有实现导航、SLAM、视频、WebSocket、任务下发调度；前端已完成并通过公网部署。

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
| 前端部署 | `/www/wwwroot/agilex.yoyoyan.cn`；BT vhost `/www/server/panel/vhost/nginx/agilex.yoyoyan.cn.conf` |
| 公网 HTTPS | `https://agilex.yoyoyan.cn/` 返回 200；HTTP 301 跳转；HSTS/CSP 生效 |
| 前端包 | `index-CljkQ52s.js` + `index-Dbo7a9He.css`（2026-10-24 视觉改版，React 19 + Vite 7 生产构建）；旧包为 `index-D5w0ypJS.js` |
| 证书 | Let's Encrypt，SAN `agilex.yoyoyan.cn`，有效期至 2027-01-21 UTC；acme.sh 计划 2026-12-22 续期 |
| 前端验收 | 旧构建曾 `deploy/smoke-test.sh` 8/8 PASS；2026-10-24 新构建已核对公网 200、新 bundle、API 未登录 JSON 401、CSP，Chrome 登录页无控制台错误；完整 smoke-test 与真实账号登录仍待执行 |

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
| 前端静态检查与测试 | `npm run typecheck` 通过；`npm test` 13 项全部通过（API 客户端 7、HoldController 4、LoginPage 2） |
| 前端生产构建 | `npm run build` 成功；React 19 + Vite 7 输出 `dist/`，无内联脚本、无外部字体/CDN |
| 前端全栈联调（2026-10-23 本地） | 真实 Broker（amqtt）+ 后端 JAR + 桥接 `--ros sim`（21.8 V）+ nginx HTTPS + Chrome：登录、总览、按住前进 0.9 s（≥3 次 keepalive）、松开 STOP、电量过低告警、控制日志；监控员不能前进但可急停；观察员只读 |
| 前端巡检页 E2E（2026-10-23 本地） | 路线创建/编辑/删除、任务创建/编辑为 PLANNED、被任务引用路线删除返回 409、观察员只读（无新建/编辑入口） |
| 后端进程重启持久化 | 历史报告 PASS，20 个操作 |
| 本轮现场构建 | 用户提供服务器 `mvn clean install` 的 BUILD SUCCESS，不据此推断完整测试数量或 Broker 测试是否执行 |
| README 文档检查 | 新增的 17 段 Bash 代码通过 `bash -n`，代码块和相对链接已检查；未执行其中远程安装命令 |
| 前端部署验证（目标服务器） | BT nginx `nginx -t` 通过；公网 HTTP 301、HTTPS 200、React bundle `index-D5w0ypJS.js`；HSTS/CSP、API JSON 401、Swagger 404；`smoke-test.sh` 8/8 PASS；Chrome 公网登录页加载无控制台错误。 |

`docs/TEST-REPORT.md` 中“未实车测试”是原自动化测试阶段的记录，不能用它否定后续现场已取得的心跳/STOP 证据，也不能把模拟测试的停车耗时套用到实车。

需要重新执行时，按改动范围选择：后端 `mvn clean test` / `mvn clean install`；桥接 `python -m unittest discover -s tests -v`。真实 Broker 测试需按报告配置 `MQTT_TEST_URL` 并核实测试 Broker 的认证环境，不设置时该用例会跳过。临时测试数据库不得污染服务器现有数据库。JaCoCo 报告入口为 `backend/start/target/site/jacoco-aggregate/index.html`。

## 8. 后续建议顺序（尚未全部完成）

1. **公网功能验收**：浏览器用 OPERATOR 登录，完整走登录、总览、巡检、控制、告警、日志；确认机器人 online/chassisConnected 和 STOP 回执。登录 403 时检查 `PATROL_WEB_ALLOWED_ORIGINS`；页面正常但离线时检查后端 MQTT/桥接。
2. **收紧后端暴露**：确认 `BIND_ADDRESS=127.0.0.1`、8080 未对公网开放；当前记录曾为 `0.0.0.0:8080`，需要在服务器核实并收紧。HTTPS 跳转/HSTS/CSP/API 401/Swagger 404 已通过。
3. **确认桥接服务安装结果**：小车 `systemctl status scout-mqtt-bridge --no-pager -l`、`journalctl -u scout-mqtt-bridge -n 50 --no-pager`，后端再查 online/chassisConnected 和 STOP 对应 seq 回执。
4. **明确开机依赖**：若用户要求整机无人值守恢复，再为 CAN 和实际底盘驱动设计服务与依赖关系；这部分当前没有实现，不应声称桥接自启已覆盖它们。
5. **验证真实短时运动**：先核实控制模式、急停、订阅关系，架空车轮、降低配置速度，使用每 200 ms 续发且 finally 发送 STOP 的 CLI。记录方向、速度、回执和物理结果。前端控制按钮也应参与一次实车按住测试。
6. **验证真实停止与恢复**：在可控条件下测量停止续发、网络断开、服务停止、服务重启的表现；系统重启验证需用户现场配合，不擅自重启设备。记录实测时间，勿复用模拟报告数字。
7. **前端后续优化**：移动端专门布局、WebSocket 实时推送、任务下发/导航不在本阶段；当前巡检页只维护计划数据。服务端构建需要 Node 20.19+ / 22.12+；本机实际部署目录为 `/www/wwwroot/agilex.yoyoyan.cn`。
8. **文档收尾**：将新的现场证据追加到本文，必要时更新旧 DEPLOYMENT 文档的过时示例。低电压阈值仍需现场确认，当前默认关闭。

可选的小范围代码修复：MQTT 凭据类型校验、通用脚本兼容本机启动环境。只有用户后续要求相关改动时再开展，不借交接扩大范围；修复应包含有效异常断言。

## 9. 接续工作的操作约定

- 用户偏好中文、可直接复制的分步命令；说明清楚在服务器还是小车执行，结合实际输出继续推进。
- 机器已有 Java、ROS、驱动、数据库和账号，不要求重新安装整套环境，不清库、不改写密码、不重建脚手架。
- 区分“代码实现”“命令已给出”“用户报告成功”“有日志证据”“实测结果”，没有证据的状态保留待确认。
- 不从截图复述 JWT/密码，不将远程 `config.yaml` 的真实凭据复制回仓库。编辑 README 不需要复制配置中的真实密码。
- 文档修改可做语法/链接检查；不能将 `bash -n` 写成服务安装或业务测试已通过。
- 前端改动至少执行 `npm run typecheck` 与 `npm test`；发版构建使用 `npm run build`，产物 `frontend/dist/` 不提交。
- 前端安全边界：JWT 只在内存、生产必须 HTTPS、生产构建不要传 `API_BASE_URL`、`PATROL_WEB_ALLOWED_ORIGINS` 必须显式包含 `https://agilex.yoyoyan.cn`；不要在代码或文档里放真实密码。
- Push 前检查差异、分支和远程。上次代理故障的单次解决命令是 `git -c http.proxy= -c http.https://github.com.proxy= push origin main`；仅在用户授权推送时执行，不修改全局 Git 设置。
- 2026-10-23 晚已由用户在 BT 服务器完成前端部署、acme.sh 证书申请和 nginx 配置；Agent 通过公网 smoke test、HTML/JS bundle、HSTS/CSP、API 401 和 Chrome 登录页加载进行了验证。下一位 Agent 先检查最新仓库和现场状态，再继续。

## 10. 前端开发与本地联调（2026-10-23，React 版）

### 10.1 当前实现

`frontend/` 已从 Flutter 替换为 **React 19 + TypeScript + Vite 7**，前后端分离，调用现有 `/api/v1/**`：

- 登录：后端 JWT；令牌只存在 `ApiClient` 内存字段，刷新页面退出；401 自动回登录页并提示。
- 总览：机器人名称/编号、在线/MQTT/底盘、真实电压、速度、里程计位姿、最近回执；每秒轮询。
- 巡检：路线点位增删改查、任务计划增删改查（DRAFT/PLANNED/CANCELLED、计划时间）；OPERATOR 可编辑，其他角色只读；任务不会自动下发。
- 控制：按住方向键每 200 ms 续发；松开、指针取消、窗口失焦、退出登录都发 STOP；请求串行化，STOP 等待在途 keepalive 后发送；独立急停按钮；离线/角色不符时禁用。
- 告警：级别/状态筛选、分页；MONITOR 可在页面确认。
- 日志：操作筛选、分页；后端按角色限制本人/全部范围。
- 退出：先尽力发送 STOP，再清令牌；单 `ApiClient` 实例，旧请求不会覆盖新登录会话。
- 后端：`patrol.web.allowed-origins`（`PATROL_WEB_ALLOWED_ORIGINS`）CORS 白名单、`server.forward-headers-strategy=framework` 保持原实现。

文件入口：`frontend/src/App.tsx`、`frontend/src/api/client.ts`、`frontend/src/control/HoldController.ts`、`frontend/src/pages/`；开发与构建说明见 [frontend/README.md](frontend/README.md)。

### 10.2 验证结果与证据

- `npm run typecheck`：通过（strict TypeScript）。
- `npm test`：**13 项全部通过**（API 客户端 7、HoldController 4、LoginPage 2）。
- `npm run build`：成功；Vite 7 输出 `dist/index.html` + 一个哈希 JS + 一个哈希 CSS，无内联脚本/样式、无外部字体或 CDN。
- 全栈联调环境：amqtt Broker、后端 JAR（临时 SQLite、演示密码、`low-battery-voltage=22.0`）、桥接 `--ros sim`（21.8 V）、nginx 1.28 HTTPS（自签，端口 8443）、Chrome headless。
- 控制/角色结果：操作员登录 → 总览在线/21.80 V；按住前进 0.9 s 观察到 ≥3 条 FORWARD，松开后 STOP；监控员不能前进但可 STOP；观察员只读；无未捕获异常。
- 巡检结果：路线创建/编辑/删除；任务创建/编辑为 PLANNED；被任务引用的路线删除返回 409；观察员无“新建路线”入口。
- **真实 nginx 验证**：严格 CSP（`script-src 'self'; style-src 'self'; connect-src 'self'`）下页面正常加载、API 同源请求成功，无 CSP 控制台错误；HSTS/CSP/反代/Swagger 404/HTTP 301 已实测。
- 过程中修复：`fetch` 作为对象方法调用触发 Chrome `Illegal invocation`，改为 `fetchImpl.call(globalThis, ...)` 后登录/轮询恢复正常；按住控制的 STOP 顺序与串行化有单元测试覆盖。
- **目标服务器生产验证（2026-10-23 晚）**：`https://agilex.yoyoyan.cn/` 返回 200，加载 React bundle `index-D5w0ypJS.js`；HTTP 301、HSTS/CSP、API 未登录 JSON 401、Swagger 404；`smoke-test.sh` 8/8 PASS；Chrome 公网登录页无 CSP/JS 控制台错误。真实账号登录和机器人控制尚未验证。
- 本地 E2E **没有使用真车**；不能替代实车运动、断网停车测试。

### 10.3 安全设计

- JWT 仅存内存，不写 localStorage/sessionStorage/URL/日志；刷新需重新登录。
- 生产强制 HTTPS（非回环 `http://` 的 `VITE_API_BASE_URL` 会显示配置错误并拒绝应用）；nginx 配置 HSTS、CSP、`X-Content-Type-Options`、`X-Frame-Options`、`Referrer-Policy`。
- CSP 为 `default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'`，生产构建无内联脚本。
- 角色控制仍以后端为准；前端只隐藏/禁用按钮。
- nginx 对登录接口限流（10 r/min，burst 5），生产默认 404 掉 `/swagger-ui`、`/v3/api-docs`。
- **CORS 必须显式列出生产源**：`PATROL_WEB_ALLOWED_ORIGINS` 未包含 `https://agilex.yoyoyan.cn` 时登录返回 403。已启用 `server.forward-headers-strategy=framework` 并透传 `Host`/`X-Forwarded-*`。
- 后端生产建议只监听 `127.0.0.1:8080`，公网只开 80/443；云安全组与 UFW 都要同步。

### 10.4 云端部署结果（2026-10-23 晚）

- 服务器使用 BT 面板；前端实际部署目录为 **`/www/wwwroot/agilex.yoyoyan.cn`**，不是文档默认的 `/var/www/agilex`。
- BT vhost：`/www/server/panel/vhost/nginx/agilex.yoyoyan.cn.conf`；安全头：`/www/server/panel/vhost/nginx/agilex-security-headers.inc`。
- 证书：`acme.sh` 申请 Let's Encrypt，SAN 仅 `agilex.yoyoyan.cn`；证书 `notAfter 2027-01-21 12:05:55 UTC`；`acme.sh --list` 显示计划 `2026-12-22T21:44:29Z` 续期，cron 为 `2,8,14,20` 点。
- 公网验证：HTTP `301` → HTTPS；HTTPS `200`；页面引用 `/assets/index-D5w0ypJS.js`；HSTS/CSP 生效；API 未登录 JSON `401`；Swagger `404`；`deploy/smoke-test.sh` **8/8 PASS**。
- 用 Chrome 从公网加载登录页，标题/表单正常，无 CSP 或 JS 控制台错误。
- `nginx -t` 仅有 `listen ... http2` 的弃用 warning，不影响运行。
- 没有安装 certbot；使用 `acme.sh`。不要再用 `xiaoyou.yoyoyan.cn`、`media.yoyoyan.cn` 或 `0.default` 的证书，它们不覆盖 `agilex.yoyoyan.cn`。

### 10.5 未完成与边界

- **尚未用真实账号登录公网页面**，因此登录 POST、总览/巡检/控制/告警/日志的云端功能仍待浏览器验收。登录 403 时检查 `PATROL_WEB_ALLOWED_ORIGINS=https://agilex.yoyoyan.cn`。
- **后端 8080 的绑定仍需核实**：交接表中记录过 `BIND_ADDRESS=0.0.0.0`。建议改为 `127.0.0.1` 并重启，公网只留 80/443；云安全组和 UFW 同步收紧。
- 直接写 BT vhost 后，站点在面板中可能不显示；**不要在面板里对该站点点保存/SSL 覆盖配置**，否则可能覆盖证书路径或反代。
- 巡检页只维护路线/任务数据，没有任务下发、导航/SLAM、执行状态或视频。
- 未做 React 实车联调、真实断网停车耗时、专门移动端布局和浏览器兼容矩阵（仅 Chrome）。
- 构建产物 `frontend/dist/` 被 `.gitignore` 排除，不提交。

### 10.6 部署文件与复现

- [frontend/README.md](frontend/README.md)：开发、构建、安全说明。
- [frontend/deploy/README.md](frontend/deploy/README.md)：系统 nginx/面板部署、证书、验收、回滚和安全清单。注意本机实际使用 BT + `/www/wwwroot/agilex.yoyoyan.cn`，与文档默认 `/var/www/agilex` 不同；以服务器实际路径为准。
- 构建：`cd frontend && npm ci && npm run build`，产物 `dist/`。
- 静态发布：`sudo rsync -a --delete dist/ /www/wwwroot/agilex.yoyoyan.cn/`，再 `chown -R www:www`。
- 证书续期：`/root/.acme.sh/acme.sh --list`；不要用 certbot。
- 部署后自检：`bash frontend/deploy/smoke-test.sh agilex.yoyoyan.cn`。

下一位 Agent 应先确认公网登录/控制是否已由用户验收，再继续第 8 节第 1–6 步；不要把“页面可打开”当成“真实控制已验收”。

## 11. 前端视觉改版（2026-10-23 深夜，React）

### 11.1 用户最新视觉约束

用户在审阅第 10 节的绿色/薄荷色版本后，明确要求：

- **不要绿色主色**（原 teal / mint 配色废弃），也不要用“屎绿色”。
- **中文用宋体，西文用 Times New Roman**。
- **磨砂玻璃样式，整体更简约**；不要添加无用的图形 logo（例如单独的大写 “S”）。
- 保留点状背景与动画；不要破坏前后端 API 链接。
- 后续补充：**点阵只在登录页**，浅色纯白 + 灰点、深色纯黑 + 灰点，铺满登录页并更密；登录页需要波动动画；**登录后的 dashboard 等页面不要背景**；卡片保持磨砂玻璃透明感，并增加控件交互动画；深色模式反过来（纯黑背景、浅灰点阵、深色玻璃）。

### 11.2 本次实际改动

- `styles.css` 重写为**纯白/纯黑 + 登录页灰色点阵**的玻璃主题：浅色 `#ffffff`，深色 `#000000`；移除了所有绿色/薄荷色 token，也不再使用冷灰蓝背景。
- 字体改为系统字体栈：
  `'Times New Roman', 'Songti SC', STSong, SimSun, 'Noto Serif CJK SC', serif`。
  因此中文使用宋体（Windows 为 SimSun），西文与数字使用 Times New Roman；不再使用 ZCOOL XiaoWei / Cormorant Garamond，也移除了 `public/fonts/` 下的自托管字体和 `@font-face`。
- **点阵只在登录页渲染**：`Atmosphere` 仅在 `!user` 时挂载；dashboard、巡检、控制、告警、日志登录后不再渲染点阵，保持纯白/纯黑背景。
- 登录页点距从 24px 收紧到 **16px**，点半径 1.1；浅色点 `rgba(0,0,0,0.18)`，深色点 `rgba(255,255,255,0.15)`，无渐隐遮罩、铺满登录页视口。在 46s 漂移和闪烁之外新增 `dot-breathe` 呼吸缩放，以及三道 11s 错峰 `wave-ripple` 扩散波纹；`prefers-reduced-motion` 下全部停止。
- **顶栏与侧栏保持完全透明**，只保留 1px 发丝线；顶部栏不 sticky，避免透明栏与滚动内容重叠。登录后页面本身没有点阵背景，透明栏只用于保持干净的层次。导航选中态和侧栏状态卡仍是局部玻璃元素。
- 登录面板、弹窗和卡片使用半透明白/黑玻璃、细边框、柔和阴影与 `backdrop-filter: blur(...) saturate(...)`；浅色卡片约 55% 白、深色约 10% 白，保持透明感。
- 增加控件交互动画：按钮/导航 hover 位移与阴影、输入 focus 环、方向盘 hover 抬升、按住 `scale(0.94)` + 反色 + `press-ring` 波纹，松开恢复。
- 移除图形品牌标（侧栏/登录页的 “S” 方块）、轨道图、装饰编号、超大 “S” 水印、霓虹色标题渐变等；侧栏和登录页只保留文字 “SCOUT”。
- 页面结构简化：顶部显示页面标题与一句说明；巡检页使用“路线/任务”分段控件；控制页合并为单张卡片，移动端把方向盘放在说明之前；列表不再显示装饰序号。
- 修复弹窗：`Modal` 通过 `createPortal` 挂到 `document.body`，避免页面入场动画的 `transform` 把 `position: fixed` 遮罩限制在内容区；打开时给 `body` 加 `modal-open` 锁滚动。页面入场动画也改为只做透明度，避免建立额外的合成层。
- 响应式断点：960px 顶栏导航，760px 单列/方向盘前置，560px 隐藏顶部说明，360px 指标单列。
- `ApiClient`、`HoldController`、路由与角色的业务逻辑未改动；本次只改视觉与展示结构。

### 11.3 修改文件

- 主题与背景：`frontend/src/styles.css`、`frontend/src/components/Atmosphere.tsx`、`frontend/src/components/DotPattern.tsx`、`frontend/index.html`、`frontend/public/favicon.svg`。
- 布局与页面：`App.tsx`、`components/Layout.tsx`、`components/UI.tsx`、`pages/LoginPage.tsx`、`pages/DashboardPage.tsx`、`pages/ControlPage.tsx`、`pages/InspectionPage.tsx`、`pages/AlarmsPage.tsx`、`pages/LogsPage.tsx`。
- 文档与截图：`frontend/README.md`、`frontend/docs/screenshots/*.png`（全部替换为本次视觉的模拟数据截图；新增深色模式截图）。
- `frontend/public/fonts/` 已删除（不再使用自托管显示字体）。

### 11.4 验证结果（本机，非实车）

- `npm run typecheck`：通过。
- `npm test`：**13 项全部通过**（API 客户端 7、HoldController 4、LoginPage 2）。
- `npm run build`：成功；产物为单个哈希 JS + 单个哈希 CSS；无内联脚本/内联样式。
- 用本地静态服务器按生产 CSP（`script-src 'self'; style-src 'self'; connect-src 'self'`）提供 `dist/`，Playwright + 模拟 API 走完登录、总览、巡检、控制、告警、日志和路线/任务弹窗：
  - 0 控制台错误、0 页面异常、0 个 `[style]` 属性；
  - 计算字体栈包含 `Times New Roman` 与宋体 fallback；
  - 浅色 `body` 背景 `rgb(255,255,255)`、登录页点阵 `rgba(0,0,0,0.18)`、点距 16px；深色 `body` 背景 `rgb(0,0,0)`、登录页点阵 `rgba(255,255,255,0.15)`；点阵 `mask-image: none`，`.atmosphere` 尺寸等于 1440×1100 视口；登录后 dashboard 的 `.atmosphere` 数量为 **0**；
  - 登录页有 3 个 `.atmosphere-wave`；`dot-breathe` 与 `wave-ripple` 动画名称均生效；`prefers-reduced-motion: reduce` 下漂移、呼吸、闪烁、波纹全部为 `none`；
  - 顶栏和侧栏计算背景均为 `rgba(0,0,0,0)`（透明）、`backdrop-filter: none`；登录后无点阵，登录页点阵不被控件栏遮挡；
  - 登录面板背景 `rgba(255,255,255,0.65)` + `blur(32px) saturate(1.2)`；主卡片为 55% 白玻璃；深色为半透明深色玻璃；
  - 1440 / 820 / 390 / 320 宽度均无横向溢出；
  - 交互动画：方向盘 hover `translateY(-2px)`、环形阴影 4px；按住 `scale(0.94)`、背景切换为反色、`press-ring` 动画运行；
  - 请求路径与请求体未变：登录、状态轮询、路线/任务 GET+POST、告警 GET + PATCH 确认、日志 GET、控制 POST；
  - 按住前进约 0.8 s 后端收到 5 次 `FORWARD`，松开后收到 `STOP`；退出登录再发 `STOP`；
  - MONITOR 可确认告警、不能前进但可 STOP；VIEWER 巡检页无新建/编辑按钮。
- 截图使用模拟数据；**视觉改版后尚未用真实后端/真实小车重新联调**；登录页-only 的最新版本也尚未部署到服务器。

### 11.5 线上部署与验证（2026-10-24）

用户已在 BT 服务器 `/opt/remote_carctl/frontend` 拉取代码并构建，随后 `rsync` 到 `/www/wwwroot/agilex.yoyoyan.cn/`，`chown` 为 `www:www`。公网已验证：

- `https://agilex.yoyoyan.cn/` 返回 200；HTML 引用新 bundle `assets/index-CljkQ52s.js` 和 `assets/index-Dbo7a9He.css`，JS/CSS 均 200。
- 未登录调用 `/api/v1/robot/status` 返回 401；响应头 CSP 仍为严格策略。
- 用本机 Chrome（Playwright）加载公网登录页：0 控制台错误、0 页面异常、0 个内联 `style`；`body` 背景 `rgb(255,255,255)`，点阵 `rgba(0,0,0,0.22)`、无遮罩，字体栈包含 Times New Roman 与宋体，登录面板为半透明玻璃 + `blur(32px)`。
- **尚未用真实账号登录并验证总览/控制/告警/日志**；完整 `smoke-test.sh` 仍需用户在服务器执行。

> **2026-10-24 追加版（已推送 `a9c77e7`，尚未部署）**：登录页-only 点阵、16px 点距、三道 `wave-ripple` + `dot-breathe`，登录后 dashboard 不再渲染点阵。本地构建为 `index-kdcNEZf2.js` / `index-BZdA0KMz.css`；线上仍是上一版 `index-CljkQ52s.js`。服务器拉取 `a9c77e7` 后按下方命令重新 build + rsync；验证时登录页应看到更密的点和扩散波纹，登录后背景应为无点阵的纯白/纯黑。

服务器实际上由 **BT 面板的 nginx** 提供服务，不是 systemd 的 `nginx.service`：

```text
master: /www/server/nginx/sbin/nginx -c /www/server/nginx/conf/nginx.conf
systemctl is-active nginx -> failed
command -v nginx -> /usr/sbin/nginx
```

因此 `sudo systemctl reload nginx` 报 “nginx.service is not active” 是正常的，**静态文件更新本来不需要 reload**。也不要为了这个错误去启动 systemd 的 nginx 单元，可能与 BT nginx 抢占 80/443。若要验证/重载 BT nginx，使用其自己的二进制：

```bash
sudo /www/server/nginx/sbin/nginx -t
sudo /www/server/nginx/sbin/nginx -s reload
# 或 /etc/init.d/nginx reload
```

后续部署命令（服务器上）：

```bash
cd /opt/remote_carctl
git pull --ff-only

cd frontend
npm ci
npm run typecheck
npm test
npm run build

# 保护 .well-known（证书续期）和 BT 的 .user.ini
sudo rsync -a --delete --exclude='.well-known' --exclude='.user.ini' \
  dist/ /www/wwwroot/agilex.yoyoyan.cn/
sudo chown -R www:www /www/wwwroot/agilex.yoyoyan.cn

# 静态文件无需 reload；确需清缓存/验证配置时：
sudo /www/server/nginx/sbin/nginx -t
sudo /www/server/nginx/sbin/nginx -s reload

# 确认线上引用的是新构建（以 dist/index.html 实际 hash 为准；本次本地为 index-kdcNEZf2.js）
curl -s https://agilex.yoyoyan.cn/ | grep -o 'assets/index-[^"]*\.js'

cd /opt/remote_carctl
bash frontend/deploy/smoke-test.sh agilex.yoyoyan.cn
```

注意：

- 新构建的 JS/CSS 文件名带新 hash；`smoke-test.sh` 只检查页面、CSP、API 401 等，不校验具体 bundle 名。
- 没有新增字体文件，部署后不需要额外的 `font-src` 配置；CSP 保持严格即可。
- 截图是模拟数据，不是实车或真实后端验收。真实公网验收仍需 OPERATOR 登录、状态在线、按住控制、STOP 回执与角色检查。
- 后续视觉修改必须保留用户约束：**不要绿色主色、不要无用 logo、中文宋体、西文 Times New Roman、纯白/纯黑底 + 灰色点阵铺满整页、顶栏/侧栏不遮挡点阵、卡片磨砂玻璃、控件交互动画、深色模式反色**。第 10 节的绿色主题描述只作为历史记录。
