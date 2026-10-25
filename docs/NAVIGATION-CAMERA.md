# 云端目标点导航、多点巡逻与 D435 实时画面

更新时间：2026-10-25。以用户提供的《SCOUT MINI Pro 科研教育套装开发手册（重庆大学定制新版）》为硬件依据。**本地代码与自动化验证不代表实车导航/摄像头已验收；用户确认尚未验证 RViz 到点。** 不改变原有路线/任务数据管理含义，不把数据库任务标记为真实执行完成。

## 1. 能否实现，以及本次边界

能实现。手册第 1 页列出 AGX Xavier、RS-Helios-16P 雷达、RealSense D435 和 CH110 IMU；第 23–25 页介绍 D435，第 26–30 页介绍雷达和 LaserScan，第 31–35 页包含建图、定位、`navigation_4wd.launch`、RViz 单点及多点导航。

云服务器只提交 **map 坐标系、单位米、yaw 单位弧度** 的目标点。定位、全局/局部路径规划和避障由小车现有 ROS1 `move_base` 执行。每次最多 30 点、1–20 轮，不执行无限循环或后台定时调度。目标地图指纹变化时拒绝启动。仅 action 返回 SUCCEEDED 才推进点位。

需要先有可用地图、正确初始定位、新鲜雷达/代价地图/底盘数据和独占速度出口。D435 不是单独的导航系统，不能以“发布目标成功”代替实际到达。没有这些条件时，Web 显示未就绪并保留原有按住遥控；不降级为定时直行或里程计盲走。

导航由后端每秒续约，小车独立执行 **3秒租约**。切换摄像头、浏览器失焦或关闭页面后，已经授权的有限点位巡逻继续执行；需结束时主动点击任意页面的“立即停止”或导航取消。退出登录也会尝试发送STOP。后端退出/重启、MQTT失联、定位/传感器过期、单点超时会关闭导航速度；有效手动命令也会取消导航。后端只续约已确认仍在执行的同一任务，权限撤销或租约过期后不自动恢复；不增加定时或无限巡逻调度。

## 2. 先做小车只读检查

仓库更新到小车后执行，不会发布运动或导航目标：

```bash
cd /home/agilex/Desktop/remote_carctl
source /opt/ros/melodic/setup.bash
source /home/agilex/catkin_ws/devel/setup.bash
timeout 30 /usr/bin/python2.7 scripts/robot_preflight.py

# 查看实际导航 launch，确认地图参数、scan、节点及速度出口
sed -n '1,240p' "$(rospack find scout_bringup)/launch/navigation_4wd.launch"
```

检查输出的现有地图文件、`move_base` / `amcl` / `map_server` / `realsense2_camera`、`/map`、`/scan`、`/move_base/local_costmap/costmap`、相机 topic。缺失项按实机安装情况修复，不重装整套 ROS 或升级 RealSense 到不兼容的 ROS2 主分支。

预检还分别验证系统Python2.7的TF扩展与Conda Python3的action消息导入。两项都需OK；这只是依赖检查，不发布目标或速度。相机前台启动窗口需保持运行，再在另一个终端查图像头，避免把关闭相机后没有topic误判成驱动错误。

## 3. 实车导航准备

底盘使用已验证的 `scout_base scout_mini_base.launch`、can0 500000。已有底盘进程时不要再启动一份；先阅读实际 launch 的 include，避免导航 launch 重复启动底盘。以下为手册命令，不是已运行的现场结果。

1. 先确认遥控和急停可用、场地有监护人，暂不从 Web 启动导航。
2. 本机使用 `robot-bridge/launch/cloud_sensors.launch` 启动雷达，检查真实 `/scan`；它沿用已安装 rslidar_sdk 和 pointcloud_to_laserscan。现有 `open_rslidar.launch` 会额外启动 RF2O，本机已经有底盘 `/odom`，因此新增入口保留单一里程计。
3. 如无已验证的当前场地地图，按第31页用 `scout_bringup gmapping.launch` **或** `scout_cartographer.launch` 建图。先查看其 include/节点，确保不重复启动现有底盘、雷达或里程计，选择一个算法。用人工遥控采集真实地图，保存到新文件名，避免覆盖旧图，例如 `rosrun map_server map_saver -f "$HOME/maps/patrol_$(date +%Y%m%d_%H%M%S)"`（先创建 `~/maps`）。
4. 选定地图后，用 `robot-bridge/launch/cloud_navigation.launch` 启动 map_server、amcl、move_base。地图参数必填，不默认使用来源不明的旧图。第33页在RViz用 **2D Pose Estimate** 设置初始位姿并核对激光与地图对齐；桥接每次启动或地图变化后都要求重新观察到这一步，否则返回 INITIAL_POSE_REQUIRED。
5. 新增导航入口已在 **move_base 节点内部**将 `cmd_vel` 重映射到 `/navigation/cmd_vel`。使用其他入口也必须隔离实际输出。全局重映射可能同时改掉底盘订阅，不能替代节点内部配置。套装旧导航入口会另起底盘和平滑器，不与新增入口并行运行。
6. 检查：`rostopic info /cmd_vel` 的 publisher 只能是本项目桥接；`rostopic info /navigation/cmd_vel` 的 publisher 应为 move_base。其他 teleop、smoother 或导航节点直接发布 `/cmd_vel` 时，本适配器拒绝就绪。

速度路径：`move_base → /navigation/cmd_vel → 桥接仲裁 → /cmd_vel → scout_base`。启用后 RViz 的独立目标不会绕过云端租约直接驱动底盘。导航最大速度默认 0.2m/s、0.4rad/s；原套装正确的 footprint、障碍层、inflation 和限速参数仍必须保留并实测。健康检查使用后台线程，不阻塞 STOP/速度循环。

现场已确认：原套装 footprint 为 ±0.33m；AMCL/costmap 配置为 `base_footprint`，运行中的底盘为 `odom → base_link`。新增入口将导航统一到 `base_link`，显式设置障碍层和 scan 源的正确命名空间（原 YAML 的 plugins 位于根级）、静态地图层和 inflation 0.55m。桥接对目标周围0.35m静态空闲区做粗筛，实际避障仍依赖正确的 footprint/雷达/代价地图，需要现场验证。

代码更新到小车后，各进程使用独立终端并加载ROS/工作区；保留当前底盘，先检查已有节点，避免重复启动：

```bash
source /opt/ros/melodic/setup.bash
source ~/catkin_ws/devel/setup.bash
/usr/bin/python2.7 /opt/ros/melodic/bin/roslaunch \
  /home/agilex/Desktop/remote_carctl/robot-bridge/launch/cloud_sensors.launch

# 另一个终端：必须替换成已验证的当前场地地图路径。
/usr/bin/python2.7 /opt/ros/melodic/bin/roslaunch \
  /home/agilex/Desktop/remote_carctl/robot-bridge/launch/cloud_navigation.launch \
  map_yaml:=/absolute/path/to/verified-current-site.yaml

# 有图形桌面的终端：Fixed Frame=map，设置初始位姿，核对激光对齐。
rosrun rviz rviz -d "$(rospack find scout_description)/rviz/rviz_navigation.rviz"
```

启动这些节点建立导航能力，仍需桥接门控、云端租约和显式目标才输出导航速度。首轮在监护与急停可用的条件下，从Web提交附近单点，验证真实到达、STOP与中断停车。ROS进程启动不代表定位、避障或实际到达已通过。

修改小车 `robot-bridge/config.yaml`（**不要覆盖现场 MQTT 地址和密码**）：

```yaml
navigation:
  enabled: true
  action: /move_base
  frame: map
  base_frame: base_link
  map_topic: /map
  scan_topic: /scan
  costmap_topic: /move_base/local_costmap/costmap
  velocity_topic: /navigation/cmd_vel
  max_linear: 0.2
  max_angular: 0.4
  goal_timeout: 180
```

topic 名以实机为准。`/map` 必须是静态已保存的地图，不能把持续变化的建图过程当已定位地图。ROS 与服务器时钟需同步，否则 3 秒有效期的命令会被拒绝。当前导航适配器只支持现场 ROS1 Melodic，ROS2/仿真模式启用导航会报错，不生成虚假导航结果。

在 `/etc/remote-carctl/backend.env` 中设置 `PATROL_NAVIGATION_ENABLED=true`，重启 `remote-carctl`。重启小车桥接服务，再在 Web“导航”检查 READY、真实 map 位姿和地图指纹。**服务器与桥接两处默认均关闭；仅在完成上述现场检查后打开。**

目标坐标从 RViz 的同一地图获取，不能照抄 Dashboard 的 odom 坐标或经纬度。先输入附近的一个空闲点、1轮；验证STOP、后端/MQTT断线停车、切换摄像头仍可巡逻及实际到达，再增加点位。提交成功只表示Broker接收；结果和点位进度来自真实action回调。导航会话仅内存，后端重启后不恢复，原数据库任务计划不自动标记为完成。

Melodic的 `tf2_py` 是系统Python2.7的原生扩展。为兼容本机Conda Python3.7，桥接自动用 `/usr/bin/python2.7` 启动 `ros1_tf_pose.py`，从真实TF读取map位姿并发布标准PoseStamped到 `/patrol/navigation_pose`。Python3只订阅该消息，不加载Python2扩展；位姿源退出、消息过期或TF不可用时拒绝导航。配置项为 `navigation.pose_python` / `pose_topic`，默认分别是上述解释器和话题，不需要重编整个ROS。辅助进程不发布cmd_vel、odom、目标点或假位姿，随桥接正常退出。

## 4. 摄像头数据流与不落盘约束

`D435 RGB → ROS CompressedImage → 小车 scout_camera_uplink.py → HTTPS JPEG帧 → 后端有界内存转发 → 已登录浏览器 canvas`。

- 设备写入使用独立的 32–256 字符 `X-Device-Key`，不使用 operator JWT；浏览器读取使用现有 JWT 请求头。密钥不放 URL、前端代码或 Git。
- 每帧最大 384KiB，最多 8 个观看/拍照连接；每连接仅保留 1 个待转发帧，慢连接丢旧帧。无观众时直接丢弃，拍照等待下一帧最多 5 秒。1 秒前的排队帧不再展示为实时。
- 不写媒体数据库、不创建媒体文件、不做录像历史。Nginx **请求和响应**缓冲/缓存/文件存储都需按下一节关闭。应用仍保留必要的在线计数等元数据，业务 SQLite 不改动。
- Web 拍照/录像的源数据均来自实时上传帧；照片和最长 30 秒、8MiB 的静音视频只在当前浏览器内存预览。用户可主动下载到自己的电脑。无需把录像文件再次上传服务器，浏览器退出释放对象 URL。断流会结束录像并遮住旧画面。
- 为兼容当前 Melodic 的 Python2/Conda Python3 混合环境，不依赖 Python3 `cv_bridge` 的本地 ABI；优先读取现成的 `sensor_msgs/CompressedImage`。
- 此实现是低帧率 JPEG 视频转发，不是 RTSP/WebRTC；服务器 3Mbps 公网带宽建议先用 **4fps、640×480**，多观众带宽按人数增加。

## 5. 更新服务器（保留原环境/SQLite/证书）

服务器使用 `/opt/remote_carctl`，后端配置 `/etc/remote-carctl/backend.env`。按原有流程更新代码、`mvn clean install`、构建前端后将 `dist/` 同步到 **实际宝塔 root** `/www/wwwroot/agilex.yoyoyan.cn`，不要改成旧通用例子的 `/var/www/agilex`。

生成设备密钥，保存到原有 env（只改这一项，不打印完整 env）：

```bash
cd /opt/remote_carctl
umask 077
CAMERA_KEY="$(openssl rand -hex 32)"
sed -i '/^PATROL_CAMERA_DEVICE_KEY=/d' /etc/remote-carctl/backend.env
printf '\nPATROL_CAMERA_DEVICE_KEY=%s\n' "$CAMERA_KEY" >> /etc/remote-carctl/backend.env
chmod 600 /etc/remote-carctl/backend.env
# 将 CAMERA_KEY 通过可信渠道粘贴到小车下一节的隐藏输入；勿发聊天截图。
# 本机交互查看仅此项：printf '%s\n' "$CAMERA_KEY"
unset CAMERA_KEY
systemctl restart remote-carctl
```

**必须更新当前生效的 Nginx 站点配置**。先用 `/www/server/nginx/sbin/nginx -T` 确认当前配置文件名；命令行原站点可能仍叫 `agilex.yoyoyan.cn.conf`，宝塔纳管后可能是 `html_agilex.yoyoyan.cn.conf`，不能假定两者同时生效。备份现有配置和证书引用，保留宝塔管理内容。

```bash
install -d -m 755 /www/server/nginx/conf/snippets
install -m 644 /opt/remote_carctl/frontend/deploy/camera-relay.conf \
  /www/server/nginx/conf/snippets/agilex-camera-relay.conf
```

在已有 **HTTPS server `{...}` 内**加入一次：

```nginx
include /www/server/nginx/conf/snippets/agilex-camera-relay.conf;
```

这个 snippet 独立处理 camera 两组路径，保留原 `/api/` 代理；关闭 `proxy_request_buffering`、`proxy_buffering`、`proxy_cache`、`proxy_store`、临时响应文件，原始上传使用 512KiB 内存缓冲并限制 body 为 384KiB。不要把媒体 location 接入会保存请求正文的 WAF/调试抓包；若额外代理/CDN存在，也需核实其正文记录规则。

把**当前生效的 CSP** 的 `img-src` 增加 `blob:`，并添加 `media-src 'self' blob:`；其余限制保持。例如完整值见 `frontend/deploy/security-headers.conf`。这允许本地内存照片/录像预览，不需要开放第三方域名、脚本或 USB 权限。不要叠加两条互相冲突的 CSP。

```bash
/www/server/nginx/sbin/nginx -t && /www/server/nginx/sbin/nginx -s reload
```

通用非宝塔模板 `nginx-http.conf` / `nginx-tls.conf` 已 include `/etc/nginx/snippets/agilex-camera-relay.conf`，采用这些模板时先把同名 snippet 安装到对应目录。不要直接用通用模板覆盖现有宝塔站点。

## 6. 小车启动 D435 与上传服务

手册第 24 页的命令（另开终端，已运行时不重复启动）：

```bash
source /opt/ros/melodic/setup.bash
source /home/agilex/catkin_ws/devel/setup.bash
/usr/bin/python2.7 /opt/ros/melodic/bin/roslaunch realsense2_camera rs_camera.launch
```

先用 `/usr/bin/python2.7 /opt/ros/melodic/bin/rostopic list`、`rostopic type` 确认实际 RGB 话题。优先选 `sensor_msgs/CompressedImage`，例如 `/camera/color/image_raw/compressed`。没有压缩话题时可启动现有 image_transport 的 republish（检查该包/插件确实安装）：

```bash
rosrun image_transport republish raw in:=/camera/color/image_raw compressed out:=/patrol_camera
# 上传改读 /patrol_camera/compressed
```

ROS1 Melodic 的脚本命令若误用 Conda Python3，沿用已验证的 `/usr/bin/python2.7 /opt/ros/melodic/bin/<命令>`；C++ image_transport 可直接 rosrun。D435 输出分辨率/帧率参数先查看已安装 `rs_camera.launch` 的 args 再设置，不更新驱动或固件。不要把深度灰度设备当 RGB。`--device /dev/videoX` 是确认 UVC 彩色节点后的可选 ffmpeg 管道入口，不会写文件，也不和已占用设备的 RealSense 驱动并行启动。

先前台验证（隐藏输入相同密钥）：

```bash
cd /home/agilex/Desktop/remote_carctl/robot-bridge
source /home/agilex/catkin_ws/devel/setup.bash
read -rsp '输入服务器配置的摄像头设备密钥: ' PATROL_CAMERA_DEVICE_KEY; echo
export PATROL_CAMERA_DEVICE_KEY
/home/agilex/archiconda3/bin/python3 scout_camera_uplink.py \
  --server https://agilex.yoyoyan.cn \
  --ros-topic /camera/color/image_raw/compressed --fps 4
```

打开 Web“摄像头”→连接画面，确认实物画面、拍照和短录像。前台验证后 Ctrl+C，再安装上传 systemd（不要同时运行两份）：

```bash
sudo install -d -m 755 /etc/remote-carctl
read -rsp '输入同一个摄像头设备密钥: ' CAMERA_KEY; echo
printf 'PATROL_CAMERA_DEVICE_KEY=%s\nCAMERA_SERVER=https://agilex.yoyoyan.cn\nCAMERA_ROS_TOPIC=/camera/color/image_raw/compressed\nCAMERA_FPS=4\n' "$CAMERA_KEY" \
  | sudo tee /etc/remote-carctl/camera.env >/dev/null
unset CAMERA_KEY PATROL_CAMERA_DEVICE_KEY
sudo chmod 600 /etc/remote-carctl/camera.env
# 如使用 republish，把 env 的 CAMERA_ROS_TOPIC 改成 /patrol_camera/compressed。
sudo install -m 644 scout-camera-uplink.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now scout-camera-uplink
sudo journalctl -u scout-camera-uplink -n 40 --no-pager
```

服务只管理上传节点，依赖相机 ROS topic 已运行；不擅自改写原底盘、导航和相机驱动服务。相机未开时无真实帧，Web 明确显示等待，不能记作成功。没有新增 pip 库；小车继续使用原有 Python3.7 + rospy。上传端 TLS 使用系统可信 CA，不能 `verify=false`。

若希望关闭终端后D435仍工作，在前台已验证有图像后，停掉那份前台D435进程，再安装独立驱动服务：

```bash
cd /home/agilex/Desktop/remote_carctl/robot-bridge
sudo install -m 644 scout-d435.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now scout-d435
sudo journalctl -u scout-d435 -n 35 --no-pager
```

该服务用系统Python2.7运行已安装的 `rs_camera.launch`，`--wait` 等待并复用底盘ROS master，停止摄像头不会关闭底盘的master。已有相机驱动服务时使用原服务，避免重复占用USB设备。驱动服务和上传服务职责独立；服务显示active仍需图像header和Web实物画面验证。

## 7. 新 API 与验证

| 方法 / 路径 | 用途 / 权限 |
| --- | --- |
| GET `/api/v1/navigation` | 所有登录角色查看实际导航就绪原因/状态 |
| POST `/api/v1/navigation/missions` | OPERATOR 提交 `{mapId,points:[{x,y,yaw}],cycles}` |
| POST `/api/v1/navigation/cancel` | OPERATOR/MONITOR；先走现有 STOP，再取消导航 |
| GET `/api/v1/camera/status` | 所有登录角色查询转发元数据 |
| GET `/api/v1/camera/stream` | JWT请求头；4字节大端长度+JPEG，0长度心跳；55秒换连接 |
| GET `/api/v1/camera/snapshot` | 等待下一张新帧，不访问历史缓存 |
| PUT `/api/v1/device/camera/frame` | `Content-Type:image/jpeg`，仅设备密钥写入 |

导航 MQTT 新增 `robot/{id}/navigation/command`（QoS1，不 retained，共用 session/seq，3秒有效期）和 `navigation/status`（每0.5秒实际状态，不 retained）。START/KEEPALIVE/CANCEL 格式见 `NavigationMessages.java` 与 `scout_navigation.py`。有 Broker ACL 时将这两个 topic 加入原用户的正确读写范围；不要开放匿名访问。

本地测试：`mvn clean test` / `mvn clean install`、`npm test` / `npm run build`、`python -m unittest discover -s robot-bridge/tests -v`。测试用临时SQLite、受控图像字节和假action adapter验证协议/异常，不代表真实相机、TF、雷达和停车耗时。现场检查：单点到达、多点顺序、切换摄像头时巡逻继续、STOP、后端/MQTT断线和定位失效停车；D435真实画面、拍照/录像、断流遮罩、关闭页面释放图像连接；Nginx不产生媒体临时文件，服务器无媒体存储目录。
