# exp3 前端（Flutter Web）

SCOUT MINI 远程巡检控制台。前后端分离：本目录是独立的 Flutter Web 应用，通过 `/api/v1/**` 调用 [`../backend`](../backend)。生产环境由 nginx 同源提供静态文件和 API 反向代理；开发时可指向本机后端并启用后端 CORS 白名单。

当前功能：

| 页面 | 内容 |
| --- | --- |
| 登录 | 后端 JWT 登录；令牌只保存在内存，刷新页面即退出；401 自动回到登录页 |
| 总览 | 机器人名称/编号、在线/MQTT/底盘状态、电池电压、速度、里程计位姿、最近回执、每秒轮询 |
| 控制 | 按住方向键行驶（每 200 ms 续发）、松开/切换页面/退出登录自动 STOP、独立急停按钮；按角色禁用 |
| 告警 | 级别/状态筛选、分页、MONITOR 可在页面确认告警 |
| 日志 | 操作类型筛选、分页；后端按角色限制本人/全部范围 |

未实现（后续迭代）：路线与任务的增删改查页面、移动端专门布局、WebSocket 实时推送。

## 界面预览

以下截图来自本地模拟底盘联调（不是实车），仅用于确认功能与布局：

![登录页](docs/screenshots/login.png)

![总览页](docs/screenshots/dashboard.png)

![控制页](docs/screenshots/control.png)

## 1. 开发

需要 Flutter 3.44+（Dart 3.12+）。首次：

```powershell
cd exp3\frontend
flutter pub get
flutter analyze
flutter test
```

启动后端（示例，端口 8080），并在启动前允许 Flutter 开发服务器的跨域来源：

```powershell
cd exp3\backend
$env:PATROL_WEB_ALLOWED_ORIGINS = "http://127.0.0.1:5173,http://localhost:5173"
.\scripts\Run-Demo.ps1 -MqttUrl tcp://127.0.0.1:1883
```

另开终端：

```powershell
cd exp3\frontend
flutter run -d chrome --web-port=5173 `
  --dart-define=API_BASE_URL=http://127.0.0.1:8080
```

`API_BASE_URL` 只用于开发。生产构建不要传入它：留空表示同源请求，由 nginx 把 `/api/` 代理到后端。

> 只用本机回环地址时允许 `http://`；其他地址必须是 `https://`，否则页面会显示“不安全的访问配置”并拒绝启动。

## 2. 构建与部署

```powershell
cd exp3\frontend
flutter build web --release --no-web-resources-cdn --no-wasm-dry-run
```

产物在 `build/web/`。`--no-web-resources-cdn` 让 CanvasKit 从本站加载，避免依赖 `gstatic.com`（国内网络经常不可用）。完整的服务器部署（DNS、nginx、HTTPS、反向代理、限流、验收）见 [`deploy/README.md`](deploy/README.md)。

nginx 配置在 [`deploy/nginx-http.conf`](deploy/nginx-http.conf)（首次申请证书）和 [`deploy/nginx-tls.conf`](deploy/nginx-tls.conf)（正式 HTTPS），安全响应头在 [`deploy/security-headers.conf`](deploy/security-headers.conf)。

## 3. 目录

```text
lib/
  main.dart                 入口
  config.dart               构建期 API 地址与超时；HTTPS 校验
  core/api_client.dart      类型化 REST 客户端、错误码、401 回调
  core/api_exception.dart   失败模型（status=0 表示网络/超时）
  core/hold_controller.dart 按住续发、串行化、STOP 顺序保证
  models/models.dart        后端 JSON 模型（null 不补值）
  state/app_state.dart      会话、令牌、登录/退出/过期
  state/robot_store.dart    1 Hz 状态轮询、命令发送
  ui/pages/                 登录、总览、控制、告警、日志
  ui/widgets/               状态标签、卡片、分页等
  ui/theme.dart             Material 3 主题、内置中文字体
assets/fonts/               Noto Sans SC 子集（OFL）与 Roboto（Apache-2.0）
test/                       API 客户端、按住控制器、登录/总览 widget 测试
deploy/                     nginx 与部署文档
```

## 4. 安全设计

- **令牌**：登录响应中的 JWT 只存在 Dart 内存；不写 `localStorage`、`sessionStorage`、URL 或日志。刷新页面必须重新登录，XSS 无法读取持久令牌。
- **传输**：生产强制 HTTPS；`API_BASE_URL` 若为非回环 `http://`，应用直接拒绝运行。nginx 配置 HSTS、CSP、`X-Content-Type-Options`、`X-Frame-Options`、`Referrer-Policy`。
- **CSP**：`connect-src 'self'`、`script-src 'self' 'wasm-unsafe-eval'`，无外部字体/CDN。字体已内置，避免中文回退到 `fonts.gstatic.com`。
- **角色**：界面只做可用性提示；真正的权限由后端 `RobotCommandExecutor`、`RolePolicy` 和 Spring Security 校验。MONITOR 只能 STOP，VIEWER/MAINTAINER 只读。
- **运动安全**：按住每 200 ms 续发；松开、指针取消、切换标签页、退出登录都会发送 STOP。请求串行化，STOP 会等正在进行的 keepalive 完成后再发，避免迟到 FORWARD 覆盖 STOP。即使浏览器崩溃，小车侧 0.6 s 看门狗仍会停车。
- **错误处理**：只展示后端返回的中文错误信息；不记录请求体、密码或 Token。401 统一清除会话并回到登录页。
- **登录入口**：nginx 对 `/api/v1/auth/login` 做每 IP 10 次/分钟限流（burst 5），生产默认屏蔽 Swagger。
- **CORS**：生产 `backend.env` 必须设置 `PATROL_WEB_ALLOWED_ORIGINS=https://agilex.yoyoyan.cn`。虽然页面与 API 同域，但 Spring 只看到 nginx 代理后的请求，浏览器 POST 的 `Origin` 仍会被 CORS 过滤器校验；未列出会返回 403。开发时改为 `http://127.0.0.1:5173`。

## 5. 测试与验证

```powershell
flutter analyze          # 0 issues
flutter test             # 15 项：API 客户端 7、按住控制器 5、Widget 3
```

2026-10-23 本机联调（真实 Broker + 真实后端 JAR + 模拟底盘桥接 `--ros sim` + Chrome）已通过：

- 登录 → 总览显示真实在线状态、21.8 V 电压、位姿；
- 按住前进 1.2 s 期间观察到 ≥4 条 FORWARD 请求，松开后发送 STOP，收到 `APPLIED` 回执，并回到 `STOPPED`；
- 电量过低告警、机器人控制日志均能从后端读取；
- 页面零未捕获异常、零控制台错误（CSP 下无外部字体请求）；
- 使用 `deploy/nginx-tls.conf` 在本机 nginx 1.28.0（自签证书，端口 8443）完成 `nginx -t` 与同一套 E2E，验证 CSP/HSTS、`/api` 反代、Swagger 404 与 HTTP 301。

这些是本地模拟底盘联调结果，**不等于实车运动验证**；实车测试仍需按 [`../PROGRESS.md`](../PROGRESS.md) 第 8 节进行。

## 6. 字体说明

内置 `NotoSansSC-Regular.otf` / `NotoSansSC-Bold.otf` 是 Noto Sans SC 的子集（SIL OFL 1.1），覆盖 GB2312 一级汉字、项目中的全部中文以及常用标点；`Roboto-*.ttf` 为 Apache-2.0。许可证见 `assets/fonts/OFL.txt` 与 `assets/fonts/Apache-2.0.txt`。

如果以后新增了子集外的生僻字，页面可能出现方框。处理方式：重新用完整 Noto Sans SC 字体生成子集（把新文案加入字符集），或把该字加入 `assets/fonts` 的字体子集。构建产物不会访问 Google Fonts。
