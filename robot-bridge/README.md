# robot-bridge：SCOUT MINI 的 MQTT ↔ ROS 桥接节点

新增导航与摄像头的完整接入见 [NAVIGATION-CAMERA.md](../docs/NAVIGATION-CAMERA.md)。导航默认关闭，需验证地图/定位/避障并隔离速度话题；摄像头由独立的 `scout-camera-uplink.service` 通过 HTTPS 转发，服务器不保存媒体。

运行在小车工控机上，与松灵驱动 `scout_base` 并列（本次 Xavier 为 ROS1 Melodic + Conda Python3.7，另有 Noetic / ROS2 适配）：

- 订阅 `robot/{id}/control`，把 `FORWARD/BACKWARD/LEFT/RIGHT/STOP` 转成 `geometry_msgs/Twist` 发布到 `/cmd_vel`。
- 看门狗：0.6 s 内没有新的控制消息就自动停车（需求 PER-006 ≤ 1 s）；与 Broker 断线时立即停车。
- 上报 `robot/{id}/heartbeat`（1 Hz，MQTT 遗嘱 = 离线）、`robot/{id}/status`（2 Hz，数据来自 `/scout_status` 与 `/odom`，拿不到的为 `null`）、每条命令一条 `robot/{id}/control/ack` 回执。

消息格式见 [`../docs/MQTT-PROTOCOL.md`](../docs/MQTT-PROTOCOL.md)，上车步骤见 [`../docs/DEPLOYMENT.md`](../docs/DEPLOYMENT.md)。

## 文件

| 文件 | 说明 |
| --- | --- |
| `scout_bridge_core.py` | 与 ROS 无关的核心：配置校验、命令→速度映射、看门狗、`seq` 去重、遥测缓存、消息格式、paho-mqtt 1.x/2.x 封装 |
| `scout_mqtt_bridge.py` | 入口与适配器：`Ros1Adapter`（rospy）、`Ros2Adapter`（rclpy）、`SimAdapter`（无 ROS 的桌面仿真） |
| `config.yaml` | 全部配置及默认值；未知配置项会被拒绝，避免拼写错误被静默忽略 |
| `start_bridge.sh` | 在工控机上加载 ROS 环境、检查 can0 后启动 |
| `tests/` | 单元测试（不需要 ROS、Broker 或硬件） |

## 运行

```bash
pip3 install -r requirements.txt            # paho-mqtt>=1.5,<3、PyYAML；ROS 相关包来自 ROS 安装
python3 scout_mqtt_bridge.py                # 自动识别 ROS1/ROS2，读取同目录的 config.yaml
python3 scout_mqtt_bridge.py --ros 1 --host 192.168.1.10 --robot-id SCOUT-001
python3 scout_mqtt_bridge.py --ros sim      # 不需要 ROS：仿真底盘，用于联调后端
```

| 选项 | 说明 |
| --- | --- |
| `--config` | 配置文件，默认同目录的 `config.yaml` |
| `--ros auto\|1\|2\|sim` | `auto` 按 `ROS_VERSION`，或按能否导入 rclpy/rospy 判断；`sim` 必须显式指定，不会自动退化成仿真 |
| `--robot-id`、`--host`、`--port` | 覆盖配置文件中的同名项 |
| `--log-level` | `DEBUG`/`INFO`/`WARNING`/`ERROR` |

收到 Ctrl+C 或 SIGTERM 时：先停车（补发零速度），再发送 `online:false, reason:SHUTDOWN` 心跳，然后断开连接。被强杀或断电时，由 Broker 代发遗嘱 `reason:LWT`。

## 关键配置（`config.yaml`）

| 项 | 默认 | 说明 |
| --- | --- | --- |
| `robot_id` | `SCOUT-001` | 必须与后端 `patrol.robot.id` 一致 |
| `mqtt.host` / `mqtt.port` | `127.0.0.1` / `1883` | Broker 地址 |
| `mqtt.keepalive` | 5 s | Broker 在约 1.5 倍该时间后发布遗嘱 |
| `motion.linear_speed` | 0.3 m/s | FORWARD/BACKWARD 速度，上限 1.0 |
| `motion.angular_speed` | 0.5 rad/s | LEFT/RIGHT 原地转向角速度，上限 1.5 |
| `safety.watchdog_timeout` | 0.6 s | 可设 0.1–1.0 |
| `safety.publish_rate` | 20 Hz | 运动时 `/cmd_vel` 的发布频率 |
| `safety.stop_frames` | 10 | 停车后补发的零速度帧数，之后不再占用 `/cmd_vel` |
| `ros.cmd_vel_topic` / `odom_topic` / `status_topic` | `/cmd_vel` / `/odom` / `/scout_status` | 已与 scout_ros、scout_ros2 源码核对 |

## 测试

```bash
python3 -m unittest discover -s tests -v
```

本轮 65 项测试在本机 Python3.13 全部通过，并检查了生产脚本的 Python3.7 语法。覆盖原有命令映射、看门狗、STOP、断线、序号、遥测、ROS替身接线，另有导航租约/地图/健康状态/点位结果、视频解析和HTTP上传边界。历史版本曾在两套 Paho 环境通过，不能据此声称新增代码已在实际 Melodic 上运行。替身测试**不能代替实车运行**。

与后端、Broker 一起的端到端验证见 `../scripts/e2e_sim.py`。
