# D435 H.264 / WebRTC 低延迟视频（可选部署）

> 当前状态：代码与配置模板已加入仓库；前端及 Python 单元测试通过。**未在 Xavier 的 GStreamer/NVENC、云服务器 MediaMTX 或公网 WebRTC 上进行实机验证，不应直接宣称达到 15 FPS。** 旧 JPEG 模式默认保留。先停稳小车，再按顺序验证。

## 1. 架构和安全边界

`D435 RGB ROS CompressedImage → Xavier 系统 Python2.7/GStreamer jpegdec → nvv4l2h264enc (H.264 baseline) → RTSPS/TCP → 云服务器 MediaMTX → WebRTC/UDP → React HTMLVideoElement`

- H.264 默认目标：640×480、15 FPS、900 kbit/s，现场可调；NVIDIA AGX Xavier 的 `nvv4l2h264enc` 需要实际安装的 JetPack 插件支持。不重新打开 `/dev/video*`，与已有 ROS 相机共用图像 topic。视频不存文件。
- 发布：`rtsps://agilex.yoyoyan.cn:8322/SCOUT-001`，TLS、独立随机密码，密码只保存在服务器 MediaMTX 配置和小车 `h264.env`，不出现在命令行或 URL。
- 观看：浏览器 `POST /webrtc/SCOUT-001/whep`，携带现有内存 JWT；Nginx `auth_request` 向 Spring `/api/v1/auth/me` 校验。MediaMTX 的 WebRTC HTTP 只监听 127.0.0.1:8889。**仅 8189/UDP 是 WebRTC 媒体入口，并非无鉴权的 HTTP 播放服务。**
- JWT 只在 WHEP 信令请求的 HTTP 头里，Nginx 不转发给 MediaMTX。MediaMTX 仅允许 loopback 地址的无账号 WHEP 读取；公网 RTSPS 必须凭证发布，公网 RTSPS 读取不开放。
- 原有 MQTT、控制 API、STOP、导航速度门控和看门狗不改。JPEG 上传仍可在 WebRTC 故障时提供兼容画面。新增 H.264 发布器会自行重试；原有 D435 驱动/JPEG 上传进程的异常退出仍沿用一键脚本的保护性清理，避免在失去视频源时继续盲目遥控。
- 浏览器 WHEP 断线自动重连；长连接每约 55 秒更新一次以重新核验 JWT，连续三次不能建立连接会切到 JPEG。JPEG 若连续 7 秒无解码新帧，即使 HTTP 连接仍打开也会自动重建。拍照、短录像在浏览器进行，服务器无历史视频。

官方参考：[NVIDIA GStreamer 硬件编码](https://docs.nvidia.com/jetson/l4t/Tegra%20Linux%20Driver%20Package%20Development%20Guide/accelerated_gstreamer.html)、[MediaMTX RTSP 推流](https://mediamtx.org/docs/publish/gstreamer)、[MediaMTX WHEP 播放](https://mediamtx.org/docs/read/webrtc)、[公网 ICE 配置](https://mediamtx.org/docs/features/webrtc-specific-features)。

## 2. 服务器：MediaMTX（不要改动现有 Spring/SQLite/MQTT）

1. 先备份当前 Nginx 有效 vhost、证书配置和站点文件。服务器仍按现场使用宝塔与 `/www/wwwroot/agilex.yoyoyan.cn`，**不要用仓库的通用 Nginx 模板覆盖宝塔配置**。
2. 安装官方 MediaMTX 1.x Linux x86_64 可执行文件到 `/usr/local/bin/mediamtx`，先运行 `mediamtx --version`；复制 `server/media/mediamtx.yml` 到 `/etc/remote-carctl/mediamtx.yml`。创建系统账号/组 `mediamtx`；配置文件权限建议 `root:mediamtx 640`。
3. 将 MediaMTX 模板内的 `REPLACE_WITH_RANDOM_PUBLISH_PASSWORD` 替换为 32 字节以上的随机密码（交互式编辑，勿粘贴到聊天、shell 历史或 Git）。小车的 `SCOUT_RTSP_PASSWORD` 必须与它一致。用户名为 `robot_publisher`。
4. 从现有 `agilex.yoyoyan.cn` 合法证书安装一份 **可续期** 的私钥/全链到模板中写明的 `/etc/remote-carctl/stream-privkey.pem` 与 `stream-fullchain.pem`，确保 `mediamtx` 组可读私钥而其他非特权用户不可读（例如 `root:mediamtx 640`）。通过现有 `acme.sh` 续期钩子更新证书及重启媒体服务；不要覆盖站点原证书。
5. 复制 `server/media/remote-carctl-media.service` 到 `/etc/systemd/system/`，检查文件路径及权限后执行 `systemctl daemon-reload`、`systemctl enable --now remote-carctl-media`。用 `journalctl -u remote-carctl-media -n 50 --no-pager` 检查 RTSPS/WHEP 是否监听。
6. 云安全组与操作系统防火墙仅根据实际来源开放 **TCP 8322**（RTSPS 推流，尽量限制为小车出口 IP）和 **UDP 8189**（浏览器 WebRTC 媒体）。TCP 8889 **绝不能公网开放**；8554 明文 RTSP 已禁用。若用户网络不允许 UDP，另行评估 TCP ICE/TURN，不能假定 443 反代解决所有媒体连接问题。

### 宝塔 HTTPS 站点添加代理

先 `nginx -T` 找到实际生效的 443 vhost（可能是 `agilex.yoyoyan.cn.conf` 或 `html_agilex.yoyoyan.cn.conf`）。保留现有 ACME、证书、API 反代及 camera-relay 规则。在 HTTPS `server { ... }` 中仅加入一次：

```nginx
include /www/server/nginx/conf/snippets/agilex-webrtc-relay.conf;
```

先把 `frontend/deploy/webrtc-relay.conf` 复制到上述路径，检查是否已存在重复 `/webrtc/` 或 `/_scout_webrtc_auth` location。然后执行有效 Nginx 二进制的 `-t`，成功后 reload。非宝塔模板中的 include 路径为 `/etc/nginx/snippets/agilex-webrtc-relay.conf`（默认注释，部署 MediaMTX 后再启用）。

**信令验收（不需要 JWT）**：访问 `https://agilex.yoyoyan.cn/webrtc/SCOUT-001/whep` 不应该返回 React 的 HTML，也不应该未认证直接允许建立视频会话；JWT 缺失时应由 `auth_request` 返回 401。此时尚未推流，允许媒体端保持待机。

## 3. Xavier：先只读预检，再启用 H.264

使用 `agilex` 账户、真实 Melodic/工作区环境：

```bash
source /opt/ros/melodic/setup.bash
source /home/agilex/catkin_ws/devel/setup.bash
/usr/bin/python2.7 -c 'import rospy, gi; gi.require_version("Gst", "1.0"); from gi.repository import Gst; print(Gst.version_string())'
gst-inspect-1.0 nvv4l2h264enc
gst-inspect-1.0 rtspclientsink
gst-inspect-1.0 nvvidconv
timeout 10 /usr/bin/python2.7 /opt/ros/melodic/bin/rostopic hz /camera/color/image_raw/compressed
```

缺包时先报出实际错误及 `gst-inspect` 输出，不盲目升级 JetPack/ROS 或使用 Conda Python3 的 GI。相机必须由原 RealSense ROS 驱动启动，不能让第二个进程抢占 D435 USB。

创建当前用户私有 `/home/agilex/.config/remote-carctl/h264.env`，权限 `600`，内容只在小车终端本地编辑：

```bash
SCOUT_RTSP_URL=rtsps://agilex.yoyoyan.cn:8322/SCOUT-001
SCOUT_RTSP_USER=robot_publisher
SCOUT_RTSP_PASSWORD=替换为服务器配置的真实发布密码
SCOUT_H264_ROS_TOPIC=/camera/color/image_raw/compressed
SCOUT_H264_FPS=15
SCOUT_H264_BITRATE=900000
```

请勿把该文件提交 Git。`start-scout.sh --webrtc` 检查系统 Python2 GI 和必须的 GStreamer 插件，缺失时在停旧服务之前退出。全套实机运行（先停止旧的前台脚本，车辆保持静止）：

```bash
cd /home/agilex/Desktop/remote_carctl
bash robot-bridge/start-scout.sh --relative --webrtc
```

不需要相对导航时去掉 `--relative`。这个脚本继续运行原 JPEG 上传，以便 WebRTC 暂不可用时回退（可在现场将 JPEG 的 `CAMERA_FPS` 调低至 1，减少冗余带宽）。新 H.264 发布器在后台独立重试，不改变原有摄像头驱动与控制系统的停机保护。Ctrl+C 仍统一清理所有由脚本启动的进程。

## 4. Web 前端：验证媒体服务后再启用

服务器从最新版本源码构建前端：

```bash
cd /opt/remote_carctl/frontend
npm ci
npm run typecheck
npm test
VITE_CAMERA_TRANSPORT=webrtc npm run build
```

保留已部署 `dist` 的备份，然后按既有静态发布流程同步构建产物到实际宝塔站点根目录。生产不要设置 `VITE_API_BASE_URL`，仍走同源 HTTPS。完成后强制刷新，以 OPERATOR/MONITOR/VIEWER 登录分别查看控制页和摄像头页。

预期：显示 `H.264 / WebRTC`，逐帧计数增加；WebRTC 连接失败时重连，连续失败后显示 `JPEG 兼容`。照片和录像应来自浏览器当前的视频画面，断线不能把旧图当实时画面。退出、切页和断开时必须释放 PeerConnection。独立急停与人工按住控制逻辑不变。

## 5. 现场验收与回滚

| 检查 | 验收要求 |
| --- | --- |
| 安全 | 无 JWT 的 WHEP 被拒；错误 RTSPS 发布密码被拒；证书有效；未开放 8889 公网；浏览器和日志不出现密钥 |
| 视频 | ROS 压缩图像正常；RTSPS 发布有连接；WebRTC ICE 连接成功；记录实际 FPS、冻结次数与 1/2 位观众的带宽 |
| 可靠性 | 断 Xavier 网络、断 MediaMTX、关闭浏览器均能显示故障并恢复；H.264 发布器异常不使底盘桥接退出；D435 驱动退出仍触发原有安全清理，拔插 USB 仅在车辆静止状态下验证并重新启动脚本 |
| 安全控制 | 视频运行/断流时仍能 STOP；不修改看门狗或速度门控；不能以模拟状态替代实车验证 |
| 存储 | MediaMTX `record: false`；媒体没有进入现有 Spring/SQLite 或 Nginx 临时文件缓存 |

回滚：先用原有构建方式（不带 `VITE_CAMERA_TRANSPORT=webrtc`）恢复 JPEG 前端；小车停止当前前台脚本，再不带 `--webrtc` 启动；确认页面/遥控正常后才停用可选 `remote-carctl-media`。保留原 MQTT、数据库、证书和视频密钥配置，避免误删真实运行数据。
