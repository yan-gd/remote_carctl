# 纯里程计目标导航（显式启用，无自动避障）

> **危险：本模式完全不检查雷达、障碍地图或前进道路上的人员和障碍物。**
> 仅适合已经清空并隔离人员的受控测试场地，必须有人在场监护并持有实体急停。
> 不能用于公共场所、有人或有障碍物的区域。代码没有在实车上验收。

这不是更改 ROS `move_base` 的障碍权重，而是新建不依赖雷达和 `move_base` 的闭环目标控制。
网页「导航」保持相对坐标输入：X 为当前朝向前方、Y 为左侧，朝向为相对弧度；
小车先旋转面对目标，再向前移动，到点后旋转至目标朝向并停车。差速底盘不作横向平移。

## 边界与保护

- 只接受一个、距离当前起点 0.1–3 米的目标；不进行多点巡逻。
- 仅依赖新鲜的 `odom → base_link` TF 位姿、底盘连接状态和速度话题独占性。
- **不使用** `/scan`、代价地图、雷达避障、`move_base` Action 或路径规划。
- 最大命令速度为 0.10 m/s、0.25 rad/s；只向前行驶，不倒车。
- 位置误差 ≤0.12 米、朝向误差 ≤0.15 rad 时结束；误差来自里程计，不保证现实世界中的绝对精度。
- 保持原有 3 秒 MQTT 导航租约、60 秒任务超时、相对原点 4 米位移 / 6 米累积行程限制。
- 里程计/底盘失联、突然跳变、手动命令、急停、关闭程序均立即关闭速度门控。
- **使用独立 `--direct` 标志**；不影响原有 `--relative` 雷达避障模式或 map 导航。

## 小车现场启动

1. 物理隔离测试区域，清空预定路径，现场确认实体急停、Web STOP 和底盘手动遥控有效。
2. 按原有方法更新仓库，先结束旧的 `start-scout.sh` 前台进程；严禁并行运行两份底盘驱动/桥接。
3. 使用 agilex 用户启动：

```bash
cd /home/agilex/Desktop/remote_carctl
bash robot-bridge/start-scout.sh --direct --webrtc
```

不用 WebRTC 时可以仅运行 `--direct`。此模式不会拉起 `cloud_sensors.launch` 和
`cloud_relative_navigation.launch`，但仍需底盘 ROS 驱动及位姿 TF 正常。
服务器沿用既有 `PATROL_NAVIGATION_ENABLED=true`、MQTT 和后端导航协议，模式仍上报为 `relative`。
若要让网页明确显示「纯里程计（无避障）」的警示，更新前端构建产物；页面根据导航会话 ID 前缀 `direct-` 识别模式。

## 只读检查与现场验收

```bash
timeout 8 rostopic hz /patrol/navigation_pose
rostopic info /cmd_vel
```

位姿应持续约 5Hz；`/cmd_vel` 只能由当前 MQTT 桥接发布，不能有旧 `move_base`、teleop 等第二发布者。
检查网页确实显示「纯里程计（无避障）」且导航就绪后，**现场工作人员**可从短距离单点开始、
逐项验证：转向、到点停车、Web STOP、实体急停、位置传感器中断与断网停车。
没有通过这些实车测试前，不要在非隔离区域运行。

恢复旧雷达导航：停车，退出脚本，再运行 `bash robot-bridge/start-scout.sh --relative --webrtc`。

