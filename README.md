# exp3：经 MQTT 控制松灵 SCOUT MINI Pro Kit

后端通过 MQTT 向小车下发运动/停止命令，并接收心跳、状态与执行回执。exp3 由三部分组成：

| 目录 | 内容 | 运行位置 |
| --- | --- | --- |
| [`backend/`](backend) | 完整的后端：以 exp2 为基线（17 个接口全部保留），新增机器人接口 `/api/v1/robot`、`/robot/status`、`/robot/control` 和 MQTT 客户端 | Linux 服务器 / Windows PC，Java 17 |
| [`frontend/`](frontend) | React 19 + TypeScript + Vite 7 控制台：登录、总览、巡检、按住控制、告警、操作日志；前后端分离 | 浏览器；由服务器 nginx 同源托管 |
| [`robot-bridge/`](robot-bridge) | 与小车通信的代码：MQTT ↔ ROS 桥接节点，把命令转成 `/cmd_vel`，上报心跳与状态，带看门狗 | 小车工控机；本次 Xavier 使用 ROS1 Melodic + Conda Python 3.7；另有 Noetic / ROS2 适配器 |
| [`scripts/`](scripts) | `e2e_sim.py` 不接实车的端到端联调；`robot_cli.py` 命令行遥控（实车调试用）；`patrol_client.py` HTTP 客户端 | 后端 PC |
| [`docs/`](docs) | [MQTT 协议](docs/MQTT-PROTOCOL.md)、[部署与实车联调](docs/DEPLOYMENT.md)、[测试报告](docs/TEST-REPORT.md)；前端部署见 [frontend/deploy/README.md](frontend/deploy/README.md) | — |

接口细节见 [`backend/docs/API.md`](backend/docs/API.md)（第 5.18–5.20 节为机器人接口）。

当前部署进度、现场验证证据、已知问题和后续 Agent 交接见 [`PROGRESS.md`](PROGRESS.md)。

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

## 3. Quick Start：服务器 + 小车端

以下命令按本次部署环境整理。在对应机器的 **Bash** 中执行，逐段检查结果。已完成的初始化步骤不用重复执行；现有密码、配置和 SQLite 数据库保留。`docs/DEPLOYMENT.md` 中的通用示例与本节不一致时，本次 Xavier 部署以本节为准。

| 项目 | 服务器 | 小车 Xavier |
| --- | --- | --- |
| 登录用户 | `root`（其他用户需使用 sudo） | `agilex` |
| 项目目录 | `/opt/remote_carctl` | `/home/agilex/Desktop/remote_carctl` |
| 运行内容 | Spring Boot 后端、Mosquitto Broker | CAN、ROS 底盘驱动、MQTT 桥接 |
| 环境 | Java 17、Maven 3.9.11 | ROS1 Melodic、已有 `catkin_ws`、Conda Python 3.7 |
| 网络 | 本次公网 IP：`47.109.132.238` | 能连接服务器 TCP 1883 |

这里假定两个目录都已包含本仓库内容，即目录下有 `backend/` 和 `robot-bridge/`。换服务器时，把本节的 IP 和实际路径同步替换。**MQTT 用户 `robot` 与后端用户 `demo_operator` 是两套账号，密码分别设置。**

### 3.1 服务器：构建后端、配置 Broker

```bash
cd /opt/remote_carctl/backend
java -version
mvn -version
mvn clean install

sudo apt update
sudo apt install -y mosquitto mosquitto-clients
```

构建应显示 `BUILD SUCCESS`，产物为 `start/target/start-1.0-SNAPSHOT.jar`。首次创建 MQTT 密码文件；**文件已存在时去掉 `-c`，避免覆盖其他 MQTT 账号**：

```bash
sudo mosquitto_passwd -c /etc/mosquitto/exp3.passwd robot
sudo chown root:mosquitto /etc/mosquitto/exp3.passwd
sudo chmod 640 /etc/mosquitto/exp3.passwd

sudo tee /etc/mosquitto/conf.d/exp3.conf >/dev/null <<'EOF'
listener 1883 0.0.0.0
allow_anonymous false
password_file /etc/mosquitto/exp3.passwd
EOF

sudo systemctl enable --now mosquitto
sudo systemctl restart mosquitto
sudo systemctl status mosquitto --no-pager
```

网络需要同时放行**云服务器安全组和操作系统防火墙**：TCP 8080 用于 HTTP，TCP 1883 用于 MQTT。云安全组允许访问，不代表 UFW 已放行。若 UFW 当前启用，执行下列命令；不需要为此新启用或重置防火墙。访问来源按实际调试电脑和小车网络配置。

```bash
sudo ufw status
# UFW 已启用时：
sudo ufw allow 8080/tcp
sudo ufw allow 1883/tcp
```

### 3.2 服务器：首次初始化后端账号

**已有 `demo_operator` 等账号的部署跳过本小节**。普通注册只生成 VIEWER，不能用于运动控制。首次在前台启动，显式初始化演示账号；输入 8–72 位非空格 ASCII 密码：

```bash
cd /opt/remote_carctl/backend
read -rsp '设置后端演示账号密码: ' PATROL_DEMO_PASSWORD
echo

PATROL_DEMO_ENABLED=true \
PATROL_DEMO_PASSWORD="$PATROL_DEMO_PASSWORD" \
PATROL_DB_PATH=/opt/remote_carctl/backend/data/exp2.db \
PATROL_MQTT_ENABLED=false \
BIND_ADDRESS=127.0.0.1 \
java -jar start/target/start-1.0-SNAPSHOT.jar
```

看到 `Started Application` 后，按 Ctrl+C 停止此次前台进程，再执行 `unset PATROL_DEMO_PASSWORD`。这次启动只初始化数据库和账号，暂不连接 MQTT。账号为 `demo_operator`、`demo_monitor`、`demo_maintainer`、`demo_viewer`，均使用刚设置的密码；还会插入一条明确标记为 `DEMO_FIXTURE` 的测试告警。初始化不会覆盖已有账号密码，也不会清空数据库。

数据库固定为 `/opt/remote_carctl/backend/data/exp2.db`，启动时自动创建父目录和缺失的表，后续启动保留已有数据。

### 3.3 服务器：安装后端系统服务

**`/etc/remote-carctl/backend.env` 已存在时保留原文件，跳过下面创建配置的代码块。** 首次创建时，输入前面为 MQTT 用户 `robot` 设置的密码；配置文件只允许 root 读取。

```bash
sudo install -d -m 700 /etc/remote-carctl
read -rsp '输入 MQTT 用户 robot 的密码: ' MQTT_PASSWORD
echo
# EnvironmentFile 使用双引号包裹密码，并转义反斜杠和双引号。
MQTT_ESCAPED=${MQTT_PASSWORD//\\/\\\\}
MQTT_ESCAPED=${MQTT_ESCAPED//\"/\\\"}

sudo install -m 600 /dev/null /etc/remote-carctl/backend.env
{
  cat <<'EOF'
PORT=8080
BIND_ADDRESS=0.0.0.0
PATROL_MQTT_ENABLED=true
PATROL_MQTT_URL=tcp://127.0.0.1:1883
PATROL_MQTT_USERNAME=robot
PATROL_ROBOT_ID=SCOUT-001
PATROL_DB_PATH=/opt/remote_carctl/backend/data/exp2.db
PATROL_DEMO_ENABLED=false
PATROL_WEB_ALLOWED_ORIGINS=https://agilex.yoyoyan.cn
SERVER_SHUTDOWN=graceful
EOF
  printf 'PATROL_MQTT_PASSWORD="%s"\n' "$MQTT_ESCAPED"
} | sudo tee /etc/remote-carctl/backend.env >/dev/null
unset MQTT_PASSWORD MQTT_ESCAPED
```

创建服务并启动，确保之前的前台 Java 进程已退出，避免占用 8080：

```bash
sudo tee /etc/systemd/system/remote-carctl.service >/dev/null <<'EOF'
[Unit]
Description=Remote Car Control Backend
Wants=network-online.target mosquitto.service
After=network-online.target mosquitto.service

[Service]
Type=simple
User=root
WorkingDirectory=/opt/remote_carctl/backend
EnvironmentFile=/etc/remote-carctl/backend.env
ExecStart=/usr/bin/java -jar /opt/remote_carctl/backend/start/target/start-1.0-SNAPSHOT.jar
Restart=on-failure
RestartSec=5
TimeoutStopSec=30
SuccessExitStatus=143
UMask=0077
StandardOutput=journal
StandardError=journal
SyslogIdentifier=remote-carctl

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now remote-carctl
sudo systemctl status remote-carctl --no-pager -l
sudo journalctl -u remote-carctl -n 50 --no-pager
```

确认日志出现 `Started Application`、`MQTT connected`、`MQTT subscribed`，再验证本机接口：

```bash
ss -ltnp 'sport = :8080'
curl --noproxy '*' --max-time 30 -sS -L \
  -o /dev/null -w 'Swagger HTTP=%{http_code}\n' \
  http://127.0.0.1:8080/swagger-ui.html
curl --noproxy '*' --max-time 15 -i http://127.0.0.1:8080/api/v1/auth/me
```

预期监听 `*:8080` / `0.0.0.0:8080`，Swagger 为 200；未携带 JWT 的 `/auth/me` 返回 401 是正常结果。浏览器打开 [Swagger](http://47.109.132.238:8080/swagger-ui.html)。若本机 200、外部无法访问，检查安全组、UFW 和浏览器代理，不要重复启动后端。

### 3.4 小车端：检查 Python、ROS 和网络

以下在 **Xavier 的 agilex 用户**下执行。本次 ROS 命令使用系统 Python 2.7，桥接使用已验证的 Conda Python 3.7，两者不要混用。

```bash
cd /home/agilex/Desktop/remote_carctl
source /opt/ros/melodic/setup.bash
source /home/agilex/catkin_ws/devel/setup.bash

rospack find scout_base
/home/agilex/archiconda3/bin/python3 --version
/home/agilex/archiconda3/bin/python3 -c \
"import socket; s=socket.create_connection(('47.109.132.238',1883),5); print('MQTT端口连通'); s.close()"
```

首次缺少依赖时安装以下兼容版本；ROS 消息模块来自上述工作空间，不通过 pip 安装 rospy：

```bash
/home/agilex/archiconda3/bin/python3 -m pip install --upgrade 'pip==24.0'
/home/agilex/archiconda3/bin/python3 -m pip install \
  'PyYAML==6.0.1' 'paho-mqtt==1.6.1' 'rospkg==1.5.1' 'catkin_pkg==1.0.0'

/home/agilex/archiconda3/bin/python3 -c \
"import yaml, rospkg, rospy; import paho.mqtt.client; from geometry_msgs.msg import Twist; from nav_msgs.msg import Odometry; from scout_msgs.msg import ScoutStatus; print('ROS和MQTT模块导入成功')"
```

这是本次 Melodic 工控机的实际组合，不表示所有 Melodic 安装都能直接用 Python 3。若消息模块缺失，先确认已加载正确的 `catkin_ws/devel/setup.bash`。

### 3.5 小车端：启动 CAN 和真实底盘驱动

```bash
ip -details link show can0
# 仅当 can0 尚未启动时执行；已 UP、500000 时不需要重设：
sudo ip link set can0 up type can bitrate 500000
ip -details link show can0

/usr/bin/python2.7 /opt/ros/melodic/bin/roslaunch \
  scout_base scout_mini_base.launch
```

CAN 预期包含 `UP`、`bitrate 500000` 和 `can state ERROR-ACTIVE`；接口的 `state UNKNOWN` 不代表故障。本车已存在的 launch 是 **`scout_base scout_mini_base.launch`**，不是 `scout_bringup scout_mini_robot_base.launch`。驱动参数应为 `port_name: can0`、`is_scout_mini: True`、`simulated_robot: False`。

保持驱动终端运行，另开一个小车终端确认真实状态：

```bash
source /opt/ros/melodic/setup.bash
source /home/agilex/catkin_ws/devel/setup.bash
/usr/bin/python2.7 /opt/ros/melodic/bin/rostopic list
timeout 10 /usr/bin/python2.7 /opt/ros/melodic/bin/rostopic \
  echo -n 1 /scout_status
```

应有 `/cmd_vel`、`/odom`、`/scout_status`，且能读到真实电压、速度等字段。本次读到过 `battery_voltage: 25.2`，它是当时测量值，不是固定验收值。

### 3.6 小车端：配置并试运行桥接

```bash
cd /home/agilex/Desktop/remote_carctl/robot-bridge
nano config.yaml
```

只修改现有配置中的对应字段，保留其他 ROS、看门狗配置。**密码即使全是数字，也必须用引号包裹为字符串**：

```yaml
robot_id: SCOUT-001
mqtt:
  host: 47.109.132.238
  port: 1883
  username: "robot"
  password: "替换为实际MQTT密码"
```

`robot_id` 必须与后端一致。不要把含真实密码的运行配置提交到 Git。试运行：

```bash
source /opt/ros/melodic/setup.bash
source /home/agilex/catkin_ws/devel/setup.bash
/home/agilex/archiconda3/bin/python3 -u \
  scout_mqtt_bridge.py --ros 1 --config config.yaml
```

这里使用真实 ROS 模式，不能用 `--ros sim` 代替。某些环境控制台只打印 `topicmanager initialized`，应通过心跳和 API 判断是否正常，不能仅根据日志安静判断卡死。

### 3.7 小车端：将桥接安装为系统服务

先按 Ctrl+C 停止手动桥接，**保留底盘驱动运行**，避免两个桥接同时接收控制命令。执行：

```bash
sudo tee /usr/local/bin/start-scout-mqtt-bridge >/dev/null <<'EOF'
#!/bin/bash
set -eo pipefail
export HOME=/home/agilex
export PYTHONUNBUFFERED=1
source /opt/ros/melodic/setup.bash
source /home/agilex/catkin_ws/devel/setup.bash
export ROS_MASTER_URI=http://localhost:11311
cd /home/agilex/Desktop/remote_carctl/robot-bridge
test -x /home/agilex/archiconda3/bin/python3
test -r config.yaml
echo "Waiting for ROS Master..."
until /usr/bin/timeout 5 /usr/bin/python2.7 \
  /opt/ros/melodic/bin/rosnode list >/dev/null 2>&1; do
  sleep 2
done
echo "ROS Master ready, starting MQTT bridge."
exec /home/agilex/archiconda3/bin/python3 -u \
  scout_mqtt_bridge.py --ros 1 \
  --config /home/agilex/Desktop/remote_carctl/robot-bridge/config.yaml
EOF

sudo chmod 755 /usr/local/bin/start-scout-mqtt-bridge
chmod 600 /home/agilex/Desktop/remote_carctl/robot-bridge/config.yaml

sudo tee /etc/systemd/system/scout-mqtt-bridge.service >/dev/null <<'EOF'
[Unit]
Description=SCOUT MINI MQTT ROS Bridge
Wants=network-online.target
After=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
User=agilex
WorkingDirectory=/home/agilex/Desktop/remote_carctl/robot-bridge
Environment=HOME=/home/agilex
Environment=PYTHONUNBUFFERED=1
ExecStart=/usr/local/bin/start-scout-mqtt-bridge
Restart=always
RestartSec=5
KillSignal=SIGTERM
TimeoutStopSec=15
UMask=0077
StandardOutput=journal
StandardError=journal
SyslogIdentifier=scout-mqtt-bridge

[Install]
WantedBy=multi-user.target
EOF

sudo systemd-analyze verify /etc/systemd/system/scout-mqtt-bridge.service
sudo systemctl daemon-reload
sudo systemctl enable --now scout-mqtt-bridge
sudo systemctl status scout-mqtt-bridge --no-pager -l
sudo journalctl -u scout-mqtt-bridge -n 50 --no-pager
```

服务使用 `agilex` 权限和指定的 Conda Python，不依赖交互终端、`.bashrc` 或 `conda activate`。停止服务会向桥接发送 SIGTERM，程序正常退出时尝试发布零速度和离线心跳。`Restart=always` 用于进程退出后恢复，主动 `systemctl stop` 不会触发重启。

**此服务只管理桥接，不会配置 CAN 或启动 `scout_base`。** 重启小车后仍需执行 3.5 的 CAN/驱动步骤；驱动启动 ROS Master 后，等待中的桥接才开始运行。仅看到服务 `active (running)` 不代表底盘已连接，最终以心跳和 `/robot/status` 为准。

### 3.8 联通验收：心跳 → 登录 → 状态 → STOP 回执

在服务器确认收到小车实时心跳，输入的是 MQTT 密码：

```bash
read -rsp '输入 MQTT 密码: ' MQTT_PASS
echo
timeout 12 mosquitto_sub \
  -h 127.0.0.1 -p 1883 -u robot -P "$MQTT_PASS" \
  -t 'robot/SCOUT-001/heartbeat' -v -C 3
unset MQTT_PASS
```

预期每秒一条，包含 `robot_id: SCOUT-001`、`online: true`、`chassis_connected: true`。若只连接 Broker、未收到实时心跳，后端不会把小车判为在线。

在 [Swagger](http://47.109.132.238:8080/swagger-ui.html) 中按顺序操作：

1. `POST /api/v1/auth/login`：用户名填 `demo_operator`，密码填 3.2 的后端密码，**不是 MQTT 的 robot 账号**。
2. 复制响应 `data.token`，点击 **Authorize**，只粘贴 Token 本身，界面会自动加 Bearer。
3. `GET /api/v1/robot/status`：确认 `mqttConnected`、`online`、`chassisConnected` 均为 `true`，电压为实际值，拿不到的字段允许为 `null`。
4. `POST /api/v1/robot/control`，请求体 `{"command":"STOP"}`；记下响应的 `seq`。
5. 再查状态，确认 `lastAck.command=STOP`、`lastAck.result=APPLIED`，且 `lastAck.seq` 与上一步一致。

`accepted=true` 只代表后端成功向 Broker 发布；`APPLIED` 是桥接处理回执，均不能单独证明物理运动完成。运动联调先架空车轮、准备遥控器急停，确认控制模式后再做短时低速测试。运动命令需要每 200 ms 续发，单点 Swagger 一次后很快停车属于看门狗的预期行为。

### 3.9 日常启停与排查

服务器：

```bash
sudo systemctl status remote-carctl mosquitto --no-pager
sudo journalctl -u remote-carctl -f
# 在车辆静止、无控制会话时更新后端：
cd /opt/remote_carctl/backend
mvn clean install
sudo systemctl restart remote-carctl
```

小车：

```bash
sudo systemctl status scout-mqtt-bridge --no-pager -l
sudo journalctl -u scout-mqtt-bridge -f
# 修改 config.yaml 后：
sudo systemctl restart scout-mqtt-bridge
# 停止桥接：
sudo systemctl stop scout-mqtt-bridge
```

`journalctl -f` 中 Ctrl+C 只退出日志查看，不停止服务。后端和 Broker 已配置开机自启；小车桥接已提供开机自启配置，但本节没有为 CAN、底盘驱动安装自启服务。

| 现象 | 优先检查 |
| --- | --- |
| Swagger 本机 200，公网访问失败 | 云安全组与 UFW 是否都允许 8080；本机代理是否拦截 |
| 登录返回 `LOGIN_FAILED` | 使用 `demo_operator` 和后端密码；已有账号不会被初始化过程重置密码 |
| Python 报 `No module named yaml` | 是否用指定的 Conda Python 安装和启动；是否加载 ROS 工作空间 |
| MQTT 报 `object of type 'int' has no len()` | YAML 中密码被解析为整数，给密码加引号并重启桥接 |
| 桥接日志一直 `Waiting for ROS Master` | 在小车上启动 3.5 的底盘驱动，确认 ROS Master 地址 |
| `mqttConnected=true` 但 `online=false` / 控制 409 | MQTT 连接只表示后端连接 Broker；检查桥接、实时心跳、双方 robot_id |
| `chassisConnected=false` / 无真实电压 | CAN、底盘驱动、`/scout_status`；避免误启仿真模式 |
| 控制 503 `MQTT_UNAVAILABLE` | 后端到 Broker 的地址、密码和 mosquitto 服务 |
| 有回执但车不动 | 底盘模式、急停、`/cmd_vel` 订阅和驱动；不要绕过看门狗或在线检查 |

## 4. 前端（React 19 + TypeScript + Vite 7）

`frontend/` 是独立的 React 单页应用，通过 `/api/v1/**` 调用后端，部署后由 nginx 同源提供页面并反代 API。页面包括登录、机器人总览（1 Hz 刷新）、巡检（路线/任务增删改查）、按住控制、告警和操作日志；操作按钮按后端角色禁用，MONITOR 只能急停。

```powershell
cd exp3\frontend
npm ci
npm run typecheck
npm test
npm run build
```

开发时运行 `npm run dev`（Vite 默认 `http://127.0.0.1:5173`），并把 `/api` 代理到本机 8080；后端启动前设置 `PATROL_WEB_ALLOWED_ORIGINS=http://127.0.0.1:5173`。生产构建不要设置 `VITE_API_BASE_URL`（保持同源），产物在 `frontend/dist`，部署到 `/var/www/agilex`。

生产环境的 `PATROL_WEB_ALLOWED_ORIGINS` 必须显式包含 `https://agilex.yoyoyan.cn`：浏览器 POST 会带 `Origin`，而 Spring 只看到 nginx 代理后的请求，不会把它当成同源。未列出时登录会返回 403。

部署目标域名为 `agilex.yoyoyan.cn`：nginx 托管静态文件并把 `/api/` 反代到 `127.0.0.1:8080`，HTTPS 用 Let's Encrypt，登录接口限流，生产默认关闭 Swagger。完整命令、安全清单与回滚步骤见 [frontend/deploy/README.md](frontend/deploy/README.md)。

安全要点：JWT 只保存在页面内存；CSP/HSTS 等响应头由 nginx 设置；生产构建无内联脚本、无外部字体或 CDN；后端建议只监听 `127.0.0.1`。

## 5. 验证结果（详见 [测试报告](docs/TEST-REPORT.md)）

| 项 | 结果 |
| --- | --- |
| 后端 `mvn clean test`（含真实 Broker 往返测试） | 98 项通过（exp2 原有 48 项 + 新增 50 项），指令覆盖率 96.3% |
| 后端双进程重启验证 | PASS |
| 桥接节点单元测试 | 41 项，Python 3.8 / paho 1.6.1 与 Python 3.13 / paho 2.1.0 均通过 |
| 端到端模拟联调 | 25 项，两套桥接环境均通过；遗嘱触发离线 ≤ 0.03 s，Broker 重启后约 1.6 s 自动恢复，松手后停车且不再移动 |
| 前端 React 19 + Vite 7（2026-10-23，本地） | `npm run typecheck` 通过；13 项单元/组件测试通过；`npm run build` 通过；真实后端 + Broker + `--ros sim` 桥接 + **nginx 1.28 HTTPS（自签）** + Chrome 的 E2E 覆盖操作员/监控员/观察员、按住续发、STOP、告警、日志，以及路线/任务增删改查，严格 CSP 下无控制台错误 |
| 本次现场联调（2026-10-23，用户反馈） | Xavier / Melodic 真实底盘状态、桥接上报心跳、后端状态查询和 STOP 回执已确认；实际运动与断网停车时间未在本节记录测量结果 |

## 6. 采用的技术决策

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

## 7. 待确认事项

| # | 问题 | 当前默认 |
| --- | --- | --- |
| 1 | 工控机 ROS / Python 环境 | 本次已确认 Melodic、已编译 scout_base、Conda Python 3.7，启动方式见 Quick Start；其他机器需重新检查兼容性 |
| 2 | Broker 部署与认证 | 本次部署在服务器，用户名 robot，1883 密码认证；TLS 未在本次配置中启用 |
| 3 | 默认速度 0.3 m/s、0.5 rad/s，看门狗 0.6 s 是否合适 | 可在 `config.yaml` 调整（看门狗 ≤ 1 s，速度有硬上限） |
| 4 | 低电量电压阈值 | 默认关闭，实车测量后设置 `PATROL_LOW_BATTERY_VOLTAGE` |
| 5 | 遥控器需要拨到哪个档位才允许 CAN 控制 | 以实车手册为准 |
