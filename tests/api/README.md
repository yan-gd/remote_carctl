# 接口测试

针对正在运行的后端和本机 Mosquitto，覆盖 HTTP、设备上传、摄像头二进制流，以及 `robot/SCOUT-001/*` MQTT。不修改业务代码，也不需要实车。

小车有两层替身：

- 测试进程用 Paho 发布心跳、状态、回执和导航报告。
- `robot-bridge/scout_mqtt_bridge.py --ros sim` 在离线用例之后启动，用仿真底盘产生回执、电压和看门狗停车。

WHEP、RTSPS 和 ROS/CAN 在没有 MediaMTX、证书和实车时会记为跳过，原因写在报告里。

## 运行

在仓库根目录：

```bash
bash tests/api/run.sh
```

脚本会在缺少 JAR 时执行 `mvn -DskipTests package`，创建临时 SQLite 和只监听本机的 Mosquitto，启动两个 Spring Boot 进程（18080 开启 MQTT，18081 关闭 MQTT 且不配置设备密钥），再运行 pytest。结束时无论成败都会根据 `docs/test-report/last-run.json` 生成 `docs/test-report/接口测试报告.md`，并关掉测试进程。

依赖：JDK 17+、Python 3、Mosquitto。首次构建还需要 Maven 和网络。

只重跑用例时，服务必须已经按脚本中的环境变量启动：

```bash
export API_BASE=http://127.0.0.1:18080
export API_BASE_NO_MQTT=http://127.0.0.1:18081
export MQTT_HOST=127.0.0.1 MQTT_PORT=11883
export PATROL_DEMO_PASSWORD=TestDemo123!
export PATROL_ADMIN_PASSWORD=TestAdmin123!
export PATROL_CAMERA_DEVICE_KEY=camera-device-key-0123456789abcdef
export PATROL_MAPPING_DEVICE_KEY=mapping-device-key-0123456789abcd
export PATROL_JWT_SECRET='MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY='
python -m pytest tests/api -q
```

这些密码只用于临时测试库。

## Postman

导入 `postman/remote_carctl.postman_collection.json`。集合变量 `baseUrl` 默认为 `http://127.0.0.1:18080`。先运行「登录-操作员」，后续请求使用环境里保存的 `operatorToken`。自动化通过/失败以 pytest 为准。

## 结果

| 文件 | 内容 |
| --- | --- |
| `docs/test-report/接口测试报告.md` | 中文报告。区分 main 上的初次运行和针对 PR #2 修复包的复跑 |
| `docs/test-report/last-run.json` | 初次运行（main 上的后端，164/2/4），Token 和密码已打码 |
| `docs/test-report/last-run-fix.json` | 复跑（PR #2 的 JAR，期望已改为 503 / CAMERA_DISABLED） |

Postman 集合没有把快照失败写成 406，因此这次没有改集合里的断言。
