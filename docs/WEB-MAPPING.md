# Web 建图、定位与地图选点导航

新增 `--web-map` 模式：无需打开小车桌面或 RViz，在网页「导航」完成建图、保存、加载、初始定位和选点。首次需要在服务器和小车终端完成部署；之后日常地图操作在 Web 完成。**当前是代码与本地测试完成，尚未部署或完成实车验收。**

## 操作流程

1. 在「地图工作台」点击「开始新建地图」。小车启动 Gmapping，Web 显示真实 `/map`、蓝色小车位姿与橙色激光点。
2. 按住同页方向按钮，让小车缓慢走过要建图的区域，尽量形成闭环。松开、失焦或断开控制续发会停车。这里是人工遥控采集，不是自主探索；建图时不运行 move_base 自动避障。
3. 停车，输入新地图名称，点击「保存地图」。名称为 1–40 位英文字母、数字、下划线或短横线；同名地图不会覆盖。保存成功以小车回执为准，HTTP 接收不等于保存完成。
4. 在已保存地图列表中选地图，点击「加载地图导航」。小车停止 Gmapping，启动 map_server、AMCL 和 move_base，三者均不打开 RViz。
5. 当前地图尚未确认初始位姿时，网页自动切换到「设置小车初始位置」，并在地图上方显示指引。在小车当前实际位置按下，向车头方向拖动，再点击「确认小车初始位置」。这会发布标准 `/initialpose`。检查橙色激光与地图障碍轮廓是否对齐；位置不正确时重新设置。蓝色箭头只是当前估计，不能替代现场核对。选择目的地不能替代初始定位。
6. 等待真实导航检查就绪，网页自动切换到「前往地图目标」。在地图可通行处按下并拖动指定到达朝向，点击「前往此处」即提交单点、单次导航，无需再到下方添加坐标或点击开始。定位、雷达等仍未就绪时，原因会直接显示在地图旁；恢复就绪不会自动出发。需要多点时，主动选择「编辑多点巡逻」，逐点点击「加入巡逻点」，最后在下方列表点击「开始导航」。
7. 导航使用既有后端续约与小车速度门控，切页不会终止任务。全局「立即停止」始终可用；OPERATOR/MONITOR 均可 STOP。地图操作仅 OPERATOR 可用，其他角色可看地图。

全图、缩放、平移和「定位小车」支持桌面及触屏。灰色未知区域、深色障碍不允许选点。地图显示最多 512×512，按保守规则降采样；小车用完整地图再次检查目标周围空间，显示可选并不保证目标被底盘接收。

## 服务器部署

沿用已有 Spring Boot、SQLite、MQTT 和同源 HTTPS 反代，不需要新增公网端口或 rosbridge。

1. 更新代码并在 `backend/` 执行 `mvn clean install`；在 `frontend/` 执行 `npm install`、`npm run build`，按现有部署流程发布 `dist/`。
2. 在已有 `/etc/remote-carctl/backend.env` 中**追加或更新**以下配置，保留原数据库、MQTT 和摄像头配置：

```ini
PATROL_NAVIGATION_ENABLED=true
PATROL_MAPPING_DEVICE_KEY=替换为独立的至少32字符随机密钥
```

密钥仅供建图设备接口使用，不复用后端用户密码或摄像头密钥；不提交 Git。文件权限保留 600。重启既有 `remote-carctl.service`。

3. 既有 Nginx `/api/` 反代可以直接覆盖新增接口，通常不需要修改 vhost。请求体上限至少 384KiB（仓库模板 1MiB 足够）；不要缓存 `/api/v1/mapping` 和 `/api/v1/mapping/grid`。生产 CSP 保持已有 `img-src ... blob:`，地图图像在浏览器内存生成。

## 小车端部署（Xavier / ROS1 Melodic）

复用现有系统 Python2.7、Conda Python3.7、底盘和雷达。小车需要 `gmapping`、`map_server`、`amcl`、`move_base`、`scout_description`、`rslidar_sdk`、`pointcloud_to_laserscan`；启动脚本会预检包。缺包时先确认当前工作空间，不要因此重装 ROS。实际 TF 仍须是 `odom → base_link` 与正确的激光外参。

一次性创建独立配置（agilex 用户）：

```bash
mkdir -p "$HOME/.config/remote-carctl"
chmod 700 "$HOME/.config/remote-carctl"
# 已存在时编辑原文件，保留原内容，不要覆盖其他设备配置。
touch "$HOME/.config/remote-carctl/mapping.env"
chmod 600 "$HOME/.config/remote-carctl/mapping.env"
nano "$HOME/.config/remote-carctl/mapping.env"
```

内容如下，密钥与服务器一致：

```bash
MAPPING_SERVER=https://agilex.yoyoyan.cn
PATROL_MAPPING_DEVICE_KEY='替换为同一独立随机密钥'
SCOUT_MAP_DIR=/home/agilex/.local/share/remote-carctl/maps
```

先停车并退出旧的底盘、雷达、relative/direct、建图及导航进程，再从仓库启动：

```bash
cd /home/agilex/Desktop/remote_carctl
bash robot-bridge/start-scout.sh --web-map
```

暂不需要摄像头时附加 `--no-camera`；已部署好 WebRTC 时可附加 `--webrtc`。**不要与 `--relative` / `--direct` 混用。** 默认前台运行，保持终端开启；Ctrl+C 先停车，再清理本次创建的 ROS 子进程。启动脚本沿用原有对旧服务的处理，不新增统一 systemd 开机服务。

`--web-map` 覆盖的是本次进程配置，不改小车已有 `config.yaml` 中的 MQTT 密码。没有启动旧建图/导航进程时，启动后只运行底盘、雷达、桥接和可选相机；点击 Web 按钮才启动 Gmapping 或地图导航。

地图按 `<SCOUT_MAP_DIR>/<名称>/map.pgm` 与 `map.yaml` 保存，使用标准 map_server 格式。YAML 在图像完整落盘后发布，已有目录一律不覆盖；保存失败留下的不完整目录不会出现在 Web 列表，可换名重试。地图保存与不保存摄像头媒体是两回事；服务器只缓存最新显示栅格，不存地图历史。

## 协议与运行边界

| 接口 | 鉴权 | 用途 |
| --- | --- | --- |
| `GET /api/v1/mapping` | JWT | 设备状态、地图列表、位姿、激光、近期路径、操作回执 |
| `GET /api/v1/mapping/grid` | JWT | 当前显示栅格，base64 单字节：0 未知、1 空闲、2 障碍；ROS 底行优先 |
| `POST /api/v1/mapping/commands` | JWT + OPERATOR | START、SAVE、LOAD、STOP、INITIAL_POSE |
| `POST /api/v1/device/mapping/exchange` | 独立 `X-Device-Key` | 设备每秒上报，领取当前会话的一条待执行命令 |
| `PUT /api/v1/device/mapping/grid` | 独立 `X-Device-Key` | 只在地图变化或连接恢复时上传显示栅格 |

- 命令绑定设备本次启动的 session，排队有效期 10 秒，设备记住已处理 ID，重复轮询不会重复启动/保存。服务器与小车应同步 NTP；过期或时钟偏差不执行。
- 后端先发原有 MQTT STOP；设备也独立取消导航、输出零速度，并确认底盘新鲜速度低于 0.03，再切换流程。切换期间运动命令拒绝，STOP 保持可用；文件和进程操作不占用 MQTT 控制锁。
- 仅启动固定 launch 文件，不拼接 shell 命令、不接受浏览器文件路径；发现另外的 `/map` 或 `/move_base/status` 发布者时拒绝启动，不杀外部节点。
- Gmapping 与 AMCL 不同时运行。完整 map 的指纹与现有导航一致；换图/设备重启/链路失效会使 Web 已选目标失效。建图时禁止启动地图导航。
- 地图完整尺寸最多 1600 万格；显示 512×512，JSON 上传最大 384KiB，状态最大 128KiB。服务端 5 秒没有状态即显示离线；浏览器旧地图不得继续下发位姿或目标。
- 网页显示原生 TF 转发的真实位姿和激光点。TF/激光缺失时不补假数据。map 导航发布完整局部代价地图，以满足既有健康检查。
- 地图后台使用已有 move_base 地图导航；原相对导航和纯里程计模式仍保留，均没有被自动切换。

## 规划失败或持续转圈

2026-10-26 现场输出显示实际插件是 `mpc_local_planner/MpcLocalPlannerROS`，而设备同时留有 `DWAPlannerROS` 参数；这些参数不会配置 MPC。任务返回 status=4：找不到有效规划，恢复行为也失败。这确认任务失败，但不能仅凭该消息判定是定位偏差、障碍、机器人模型还是控制参数导致无可行轨迹。

Web 地图 launch 现在使用仓库 `map_navigation.yaml`，明确选择 `base_local_planner/TrajectoryPlannerROS`，非全向模型，线速度上限0.15m/s、角速度上限0.3rad/s，关闭恢复行为和旋转清障；保留原代价地图及障碍检查。配置依据 [ROS Melodic TrajectoryPlannerROS](https://github.com/ros-planning/navigation/blob/melodic-devel/base_local_planner/src/trajectory_planner_ros.cpp) 和 [move_base 恢复逻辑](https://github.com/ros-planning/navigation/blob/melodic-devel/move_base/src/move_base.cpp)。

桥接地图模式额外检查真实 `/odom`，每次相对监测起点位移达到0.1m才重置计时/累计转角；30秒未达到，或累计绝对转角达到2π，则取消任务、输出零速度。计数每个目标重新开始，AMCL定位跳动不会当作底盘位移；里程计缺失/过期同样停止。该保护针对原地停滞/转圈，并不检测所有绕圈路径，也不能修复错误地图或激光外参。

更新小车代码并重启启动脚本后，必须在网页重新加载地图，新的 move_base 配置才会生效。停车状态下依次确认：

```bash
rosparam get /move_base/base_local_planner
rosparam get /move_base/recovery_behavior_enabled
rosparam get /move_base/clearing_rotation_allowed
```

预期分别为 `base_local_planner/TrajectoryPlannerROS`、`false`、`false`。确认小车初始位置/车头朝向及激光与静态墙面轮廓对齐后，再选附近已知空闲目标；失败时保留启动终端的 move_base 日志，不要反复重试或降低避障限制。

## 验收

本地自动测试覆盖坐标旋转/Y轴翻转、图像留白与缩放、地图保存方向与防覆盖、命令权限/过期/会话变化、停稳检查、切换中STOP、网页选点与初始位姿区分、断线禁用、建图失焦停车。浏览器验证使用明确标识的本地测试数据，不是实车地图。

现场需依次确认：`/scan` 与 `odom→base_link` 新鲜、Web 动态地图正常、地图保存后重启仍在、加载后初始位姿/激光对齐、短距离目标到达、障碍绕行或终止、Web STOP/实体急停、断 MQTT/雷达/定位后停车、Ctrl+C 后没有遗留建图/导航进程。没有这些结果前，不应把功能记录为实车验收完成。

参考：套装手册第30–35页的 Gmapping、地图保存、初始定位与多点导航流程；ROS 官方 [slam_gmapping Melodic 实现](https://github.com/ros-perception/slam_gmapping/blob/melodic-devel/gmapping/src/slam_gmapping.cpp)。
