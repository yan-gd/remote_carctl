# 3 米内相对目标与实时激光避障

适用本次 Xavier / ROS1 Melodic / SCOUT MINI。此模式不加载预存地图、不启动 AMCL、不要求 RViz 设置初始位姿。底盘里程计提供 `odom → base_link`，真实 `/scan` 更新滚动障碍地图，ROS `move_base` 规划和避障。**代码和本地测试已实现；尚未在这台小车验证真实到达、障碍绕行和停车耗时。**

## 1. 使用含义与边界

- Web 导航根据小车报告自动切换为“附近目标 · 实时避障”，不能通过浏览器绕过小车的模式选择。
- X 为前后距离（前方正、后方负），Y 为左右距离（左侧正、右侧负），单位米。相对朝向为弧度，0 表示终点保持开始时朝向。例如 X=2、Y=1、yaw=0，目标直线距离约 2.24 米。
- 小车接受任务时从新鲜里程计读取起点和朝向，一次性转换目标到 odom；目标不会随小车移动而继续向前漂移。要求开始时实际速度接近零。
- 只允许一个目标、执行一次，目标距离 0.1–3m。软件速度上限 0.15m/s、0.3rad/s；任务最长 60s。绕行累计距离超过 6m 或距离起点超过 4m 时取消停车。
- 滚动代价地图由激光实时更新。未观测空间仍为未知，Navfn 不允许通过未知空间；目标及周围 0.35m 空间不满足可通行条件时拒绝启动。目标看得见不等于一定有可行路径。
- 避障失败/动作超时、雷达/代价地图/TF/底盘遥测过期、里程计明显跳变均会停止。小车报告只有真实 move_base 返回 SUCCEEDED 才算到达，不修改数据库巡检任务完成状态。
- 保留现有 STOP/手动覆盖、MQTT 断线停车、后端 3s 租约、权限和后台续约。切换摄像头或关闭浏览器不取消已授权目标，主动“立即停止”可结束。
- 这是短距离相对导航，里程计存在漂移，不替代远距离全局定位或未知场地探索。仅使用当前雷达转换出的 LaserScan，感知范围与现场传感器安装/过滤设置一致。

## 2. 更新服务器

先按原流程将最新代码推送至远程；在服务器 root 终端执行。保留现有 MQTT、SQLite、JWT、摄像头密钥和 Nginx 配置：

```bash
cd /opt/remote_carctl &&
git pull --ff-only origin main &&
cd backend &&
mvn clean install &&
cd ../frontend &&
npm run build &&
test -s dist/index.html &&
rsync -a dist/ /www/wwwroot/agilex.yoyoyan.cn/
```

启用现有后端导航开关，再重启后端：

```bash
test -s /etc/remote-carctl/backend.env &&
cp -p /etc/remote-carctl/backend.env \
  "/etc/remote-carctl/backend.env.bak-$(date +%Y%m%d-%H%M%S)" &&
sed -i '/^PATROL_NAVIGATION_ENABLED=/d' /etc/remote-carctl/backend.env &&
printf '\nPATROL_NAVIGATION_ENABLED=true\n' >> /etc/remote-carctl/backend.env &&
systemctl restart remote-carctl
```

本次包含后端协议/校验变更，不能只更新前端。旧服务端会按旧地图模式处理，不应混用版本。

## 3. 小车启动

先在原 `start-scout.sh`、单独雷达、旧导航/建图窗口按 Ctrl+C，避免重复节点。使用仓库里的最新脚本，**不要继续用之前 SCP 到 `~/start-scout.sh` 的旧副本**。

```bash
cd ~/Desktop/remote_carctl &&
git pull --ff-only origin main &&
bash robot-bridge/start-scout.sh --relative
```

此命令加载 ROS 环境，检查系统 Python2.7 TF、Conda Python3 action 消息和现有 ROS 包，再启动 CAN、底盘、雷达、相对导航、MQTT 桥接、D435 和视频上传。缺包时打印实际错误并退出，不自动安装/升级 ROS。当前 MQTT 配置和摄像头密钥原样读取，不需要编辑 `config.yaml` 开关。

不需要摄像头时：

```bash
bash ~/Desktop/remote_carctl/robot-bridge/start-scout.sh --relative --no-camera
```

保持终端打开，Ctrl+C 统一停止。它沿用前台启动方案，不重新安装 systemd。依赖旧服务存在时仅停用对应旧单元；没有旧单元也可以执行。

若需要各窗口手动启动，先运行原底盘和 `cloud_sensors.launch`，在另一终端加载 ROS 环境后执行：

```bash
/usr/bin/python2.7 /opt/ros/melodic/bin/roslaunch \
  ~/Desktop/remote_carctl/robot-bridge/launch/cloud_relative_navigation.launch
```

然后仅启动一份桥接（已加载 Melodic 和 catkin 环境）：

```bash
cd ~/Desktop/remote_carctl/robot-bridge
/home/agilex/archiconda3/bin/python3 -u scout_mqtt_bridge.py \
  --ros 1 --config config.yaml --navigation-mode relative
```

不要同时运行统一脚本和上述手动入口。旧固定地图模式保留：不加 `--relative` 时按原配置启动，地图导航继续使用 `cloud_navigation.launch`。

## 4. Web 验收

1. 强制刷新前端，使用 OPERATOR 登录，进入“导航”。模式应为“附近目标 · 实时避障”，没有地图指纹/多点/轮次输入，而是导航会话和相对距离。
2. 等待真实数据就绪，确认现场通行空间及急停可用，停车后先提交附近空闲区域中的小目标，例如 X=0.5、Y=0、yaw=0。不得把样例坐标当成对现场通行性的保证。
3. 检查实际到达反馈；再在受控条件下验证障碍阻挡时绕行或终止、STOP、手动覆盖、断链停车。切换摄像头可观察执行过程。
4. 最后再尝试更远目标；输入 X=3、Y=3 会超出半径限制，前端、后端和小车均拒绝。不要为了启动而放宽未知空间或删除保护。

常见状态：`LIVE_GRID_UNAVAILABLE` 为障碍地图未收到/过期；`LOCALIZATION_UNAVAILABLE` 为 odom 位姿不可用；`ROBOT_MUST_BE_STOPPED` 要求先停车；`GOAL_NOT_IN_FREE_MAP_SPACE` 为目标空间不满足通行条件；`RELATIVE_TRAVEL_LIMIT` 为绕行达到边界；`NAVIGATION_MODE_MISMATCH` 通常为两端版本或模式不一致。

TrajectoryPlanner 启动时可能提示 `escape_vel` 非负：这里刻意配置为 0，关闭其兜底倒车动作；该提示本身不是启动失败。

## 5. 实现位置

- `robot-bridge/launch/cloud_relative_navigation.launch` / `relative_navigation.yaml`：odom 滚动地图、障碍标记/清除、未知区域禁止通行、Navfn + TrajectoryPlannerROS，输出隔离在 `/navigation/cmd_vel`。
- `scout_navigation.py`：固定相对目标、障碍地图与里程计会话分开、跳变校验、范围/旅程边界、原有 STOP/租约/动作结果。
- `NavigationReport` / `NavigationOrder` / `NavigationMessages`：显式 `mode`，缺失 mode 的旧报告兼容 map；relative 只接受 odom。
- `NavigationService`：用户权限、单点单次/半径校验、续约；API 仍为原 `/api/v1/navigation` 三个接口，`mapId` 在 relative 模式表示里程计会话标识。
- `NavigationPage.tsx`：根据真实模式呈现输入，模式变化清空旧坐标；控制页的视频与人工遥控不改动。

配置参数依据 ROS Melodic 上游：[Costmap2DROS](https://github.com/ros-planning/navigation/blob/melodic-devel/costmap_2d/src/costmap_2d_ros.cpp)、[NavfnROS](https://github.com/ros-planning/navigation/blob/melodic-devel/navfn/src/navfn_ros.cpp)、[TrajectoryPlannerROS](https://github.com/ros-planning/navigation/blob/melodic-devel/base_local_planner/src/trajectory_planner_ros.cpp)。
