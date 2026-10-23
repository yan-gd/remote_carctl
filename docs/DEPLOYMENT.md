# 部署与实车联调指南（exp3）

本文分三部分：部署拓扑、各机器上的安装步骤、上车联调的顺序与检查清单。标 ❓ 的内容取决于实车环境，本仓库**没有在实车上验证过**，要在现场确认。

## 1. 拓扑

```text
 浏览器 / Swagger / scripts/robot_cli.py
        │  HTTP  /api/v1/robot/...
        ▼
 ┌────────────────────────┐   MQTT 1883   ┌──────────────┐   MQTT   ┌──────────────────────────────┐
 │ 后端 exp3/backend       │ ────────────▶ │ MQTT Broker  │ ◀──────▶ │ 小车工控机                    │
 │ Spring Boot + SQLite    │ ◀──────────── │ (Mosquitto)  │          │  scout_mqtt_bridge.py        │
 └────────────────────────┘               └──────────────┘          │    │ /cmd_vel   ▲ /odom        │
                                                                    │    ▼            │ /scout_status│
                                                                    │  scout_base（松灵驱动）       │
                                                                    └───────────│──────────────────┘
                                                                                │ CAN 500k
                                                                          SCOUT MINI 底盘
```

Broker 放哪台机器都可以，两种常见做法：

| 方案 | 优点 | 注意 |
| --- | --- | --- |
| A. Broker 装在后端 PC | 日志集中，课堂演示方便 | Windows 防火墙放行入站 TCP 1883 |
| B. Broker 装在小车工控机 | 桥接节点走本机回环，不受 Wi-Fi 影响 | 后端经 Wi-Fi 连小车 IP；Wi-Fi 中断时后端返回 503，小车侧看门狗负责停车 |

两种方案下，后端 `PATROL_MQTT_URL` 和桥接节点 `mqtt.host` 都指向同一个 Broker。

## 2. MQTT Broker（Mosquitto）

Mosquitto 2.x 在没有配置文件时**只监听本机**。要让其他机器连进来，必须写配置：

```conf
# mosquitto.conf（仅限隔离的实验室局域网；否则改用账号密码，见下）
listener 1883 0.0.0.0
allow_anonymous true
```

- **Ubuntu（工控机）**：`sudo apt install mosquitto mosquitto-clients`，把上面两行写进 `/etc/mosquitto/conf.d/exp3.conf`，然后 `sudo systemctl restart mosquitto`。如果开着 ufw，再执行 `sudo ufw allow 1883/tcp`。
- **Windows（后端 PC）**：从 mosquitto.org 下载官方安装包，在安装目录执行 `mosquitto -c mosquitto.conf -v`，并在防火墙放行 1883。
- **账号密码**：`mosquitto_passwd -c /etc/mosquitto/passwd robot`，配置改为 `allow_anonymous false` 和 `password_file /etc/mosquitto/passwd`。后端设置 `PATROL_MQTT_USERNAME/PASSWORD`，桥接节点设置 `mqtt.username/password`。
- **TLS（可选）**：后端用 `ssl://host:8883`（证书须被 JVM 信任），桥接节点设置 `mqtt.tls: true`、`mqtt.ca_certs`。❓未测试。

连通性检查（在后端 PC 上执行）：`mosquitto_sub -h <broker> -t 'robot/#' -v`。

## 3. 后端 PC（Windows）

```powershell
cd E:\qq157\Desktop\class\software\exp3\backend
mvn clean install
$env:PATROL_MQTT_URL = "tcp://<broker-ip>:1883"
.\scripts\Run-Demo.ps1 -MqttUrl "tcp://<broker-ip>:1883"    # 或：java -jar start/target/start-1.0-SNAPSHOT.jar
```

日志出现 `MQTT connected to tcp://...` 和 `MQTT subscribed to robot/SCOUT-001/* topics` 即表示连接正常；`GET /api/v1/robot/status` 返回 `mqttConnected=true`。后端默认只监听 127.0.0.1；需要其他电脑访问 HTTP 接口时，再设置 `BIND_ADDRESS`。

## 4. 小车工控机

### 4.1 确认环境 ❓

```bash
lsb_release -a; echo $ROS_DISTRO; python3 --version
rospack find scout_base 2>/dev/null || ros2 pkg prefix scout_base   # 驱动是否已安装（Pro Kit 可能出厂预装）
```

桥接节点支持 **ROS1 Noetic（Python 3）** 和 **ROS2 Foxy/Humble**。ROS1 Melodic 的 rospy 是 Python 2，不受支持。

### 4.2 编译驱动（未预装时）❓

以 AgileX 官方 README 为准，大致步骤：

```bash
# ROS1
sudo apt install -y libasio-dev
cd ~/catkin_ws/src
git clone --recursive https://github.com/agilexrobotics/ugv_sdk.git
git clone https://github.com/agilexrobotics/scout_ros.git
cd .. && catkin_make && source devel/setup.bash

# ROS2（以 humble 为例）
cd ~/ros2_ws/src
git clone https://github.com/agilexrobotics/ugv_sdk.git
git clone -b humble https://github.com/agilexrobotics/scout_ros2.git
cd .. && colcon build && source install/setup.bash
```

### 4.3 CAN 与驱动

```bash
sudo modprobe gs_usb                                  # USB-CAN 适配器（如适用）❓
sudo ip link set can0 up type can bitrate 500000
candump can0                                          # 能看到报文说明底盘在发数据（can-utils）

roslaunch scout_bringup scout_mini_robot_base.launch          # ROS1
ros2 launch scout_base scout_mini_base.launch.py              # ROS2

rostopic echo /scout_status      # 或 ros2 topic echo /scout_status：确认 battery_voltage、control_mode 有值
rostopic info /cmd_vel           # 订阅者中应有 scout_base_node
```

遥控器须处于允许 CAN 指令控制的模式，具体拨杆位置以实车手册为准 ❓。`/scout_status.control_mode` 的取值可以帮助判断当前模式 ❓。

**先不用 MQTT，直接验证 ROS 控制链路（务必架空车轮）**：

```bash
rostopic pub -r 10 /cmd_vel geometry_msgs/Twist '{linear: {x: 0.1}}'
# 车轮应缓慢正转。按 Ctrl+C 停止发布后，观察底盘是否自行停止并记录结果：
# 这能回答“驱动或底盘自身是否有指令超时停车”。无论结果如何，桥接节点的看门狗都会生效。
```

### 4.4 安装并启动桥接节点

```bash
scp -r exp3/robot-bridge agilex@<car-ip>:~/robot-bridge     # 拷贝到工控机（用户名以实车为准）
cd ~/robot-bridge
pip3 install -r requirements.txt     # 或：sudo apt install python3-paho-mqtt python3-yaml
nano config.yaml                     # 至少修改 mqtt.host；确认 robot_id 与后端一致
python3 -m unittest discover -s tests          # 可选：在工控机上跑单元测试
SCOUT_WS=~/catkin_ws bash start_bridge.sh      # 加载 ROS 环境，检查 can0，然后启动
```

`start_bridge.sh` 会依次加载 `/opt/ros/<发行版>/setup.bash` 和 `$SCOUT_WS` 下的工作空间。要能读到 `scout_msgs`，必须加载工作空间，否则 `chassisConnected` 会显示为 `null`。日志里应出现：

```text
bridge ros1: robot SCOUT-001, broker 192.168.1.10:1883, 0.30 m/s / 0.50 rad/s, watchdog 0.60 s
MQTT connected, listening on robot/SCOUT-001/control
```

开机自启（可选，systemd）：

```ini
# /etc/systemd/system/scout-mqtt-bridge.service
[Unit]
Description=SCOUT MINI MQTT bridge (exp3)
After=network-online.target

[Service]
User=agilex
Environment=SCOUT_WS=/home/agilex/catkin_ws
ExecStart=/bin/bash /home/agilex/robot-bridge/start_bridge.sh
Restart=on-failure
RestartSec=3

[Install]
WantedBy=multi-user.target
```

驱动 `scout_base` 需要单独启动（或另写 service）；桥接节点不负责拉起驱动。

## 5. 联调顺序与检查清单

先在桌面完成 `python scripts/e2e_sim.py`（不需要实车，见 [TEST-REPORT.md](TEST-REPORT.md)），再按顺序上车。前 6 步**必须架空车轮**。

| # | 操作 | 预期 | 实测记录 |
| --- | --- | --- | --- |
| 1 | PC 上执行 `mosquitto_sub -h <broker> -t 'robot/#' -v` | 每秒一条 heartbeat、每 0.5 s 一条 status | |
| 2 | `python scripts/robot_cli.py --user demo_viewer status` | `online=true`、`mqttConnected=true`、`chassisConnected=true`、`batteryVoltage` 有值 | 电压：____ V |
| 3 | `robot_cli.py --user demo_operator hold FORWARD 1` | 车轮正转约 1 s 后停止；`lastAck.result=APPLIED` | |
| 4 | 分别 hold BACKWARD / LEFT / RIGHT | 方向正确（LEFT 为逆时针原地转） | |
| 5 | hold 期间直接关掉终端或断开 PC 网络 | 不超过 1 s 车轮停止（看门狗） | 停车时间：____ s |
| 6 | 运动中断开小车 Wi-Fi | 桥接节点立即停车；后端 3 s 内判定离线并生成告警 | |
| 7 | 落地，`linear_speed` 调到 0.2，空旷场地低速测试；旁边有人手持遥控器并随时准备急停 | 行为与架空测试一致 | |
| 8 | 记录电池满电和低电时的电压，设置 `PATROL_LOW_BATTERY_VOLTAGE` | 低于阈值时生成“电量过低”告警 | 阈值：____ V |

## 6. 常见问题

| 现象 | 排查 |
| --- | --- |
| 控制返回 503 `MQTT_UNAVAILABLE` | 后端连不上 Broker：检查 URL、防火墙、Mosquitto 2 的 `listener` 配置；后端日志中的 `reconnecting in ...` |
| 桥接节点在运行，但 `online=false` | 两端 `robot_id` 不一致，或连的不是同一个 Broker。用 `mosquitto_sub -t 'robot/#' -v` 查看实际 Topic |
| `chassisConnected=false` | scout_base 没有运行、CAN 未启动、或遥控器不在 CAN 模式 ❓ |
| `chassisConnected=null` | 桥接节点的 shell 没有加载驱动工作空间（找不到 `scout_msgs`） |
| 回执为 APPLIED 但车不动 | 遥控器模式或急停 ❓；`rostopic info /cmd_vel` 看 scout_base 是否订阅；`ros.cmd_vel_topic` 是否被改过 |
| 409 `ROBOT_OFFLINE` | 心跳超时：检查桥接节点进程和网络；后端日志中的 `Robot alarm raised` |
| 车走一下就停 | 客户端没有持续重发命令（每 200 ms 一次），这是看门狗的预期行为 |
