# 智能巡检管理平台后端接口文档

| 项目 | 内容 |
| --- | --- |
| 系统 | 智能巡检管理平台（松灵小车版）——后端服务 |
| 文档版本 | 1.1（exp3：新增机器人接口与 MQTT 通信） |
| 适用代码 | `exp3/backend`（以 exp2 COLA 5.0 多模块工程为基线），`1.0-SNAPSHOT` |
| 服务地址 | `http://localhost:8080`（默认仅监听 `127.0.0.1`） |
| 接口前缀 | `/api/v1` |
| 认证方式 | JWT Bearer Token（HS256） |
| 数据库 | SQLite，默认 `exp3/backend/data/exp2.db` |
| 小车通信 | MQTT（默认 `tcp://127.0.0.1:1883`），协议见 [`exp3/docs/MQTT-PROTOCOL.md`](../../docs/MQTT-PROTOCOL.md) |
| 在线调试 | `/swagger-ui.html`、`/v3/api-docs` |

> exp2 的 17 个接口保持不变；exp3 新增 3 个机器人接口（5.18–5.20），经 MQTT 与小车上的桥接节点通信。`accepted=true` 只表示后端已校验并把命令交给 MQTT 发布，**不代表小车已完成动作**；拿不到的遥测一律返回 `null`，不伪造。路线/任务仍是计划数据，不会被下发给小车执行。

---

## 1. 快速开始

### 1.1 构建与启动

```powershell
cd E:\qq157\Desktop\class\software\exp3\backend
mvn clean install
java -jar start/target/start-1.0-SNAPSHOT.jar
```

默认端口 `8080`，只监听本机。可通过环境变量调整：

```powershell
$env:PORT = "9090"
$env:BIND_ADDRESS = "127.0.0.1"
$env:PATROL_MQTT_URL = "tcp://192.168.1.10:1883"   # 小车与后端共用的 MQTT Broker
java -jar start/target/start-1.0-SNAPSHOT.jar
```

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PATROL_MQTT_ENABLED` | `true` | `false` 时不连 Broker，机器人恒为离线，控制命令返回 503 |
| `PATROL_MQTT_URL` | `tcp://127.0.0.1:1883` | Broker 地址，支持 `tcp://`、`ssl://` |
| `PATROL_MQTT_USERNAME` / `PATROL_MQTT_PASSWORD` | 空 | Broker 账号（可选） |
| `PATROL_MQTT_CLIENT_ID` | 随机 `patrol-backend-xxxxxxxx` | 固定值时注意不能与其他客户端重复 |
| `PATROL_MQTT_RECONNECT_MS` | `5000` | 断线后的重连间隔（PER-007） |
| `PATROL_ROBOT_ID` | `SCOUT-001` | 机器人编号，必须与桥接节点 `robot_id` 一致 |
| `PATROL_HEARTBEAT_TIMEOUT_MS` | `3000` | 超过该时间未收到心跳即判定离线 |
| `PATROL_LOW_BATTERY_VOLTAGE` | 空（关闭） | 电池电压告警阈值（V），需按实车电池确认后再设置 |

Broker 不可用时服务照常启动，后台按间隔重连，并生成一条“MQTT连接断开”告警。

### 1.2 启用演示账号

普通注册只能得到 `VIEWER`，没有提权接口。演示写操作需要显式启用固定账号：

```powershell
.\scripts\Run-Demo.ps1
```

脚本会安全提示输入初始密码。也可以直接设置环境变量：

```powershell
$env:PATROL_DEMO_ENABLED = "true"
$env:PATROL_DEMO_PASSWORD = "你的演示密码"
java -jar start/target/start-1.0-SNAPSHOT.jar
```

| 用户名 | 角色 | 能力 |
| --- | --- | --- |
| `demo_operator` | OPERATOR | 路线/任务增删改；全部路线、任务、告警查询；本人日志 |
| `demo_monitor` | MONITOR | 告警确认；全部路线、任务、告警查询；本人日志 |
| `demo_maintainer` | MAINTAINER | 路线、任务、告警、全部操作日志查询 |
| `demo_viewer` | VIEWER | 路线、任务、告警、全部操作日志查询 |

首次启用时插入 4 个账号和 1 条 `source=DEMO_FIXTURE` 告警；重复启动不会重复插入，也不会覆盖已有密码或角色。关闭演示模式后不会删除已有数据。

### 1.3 Swagger 调试

1. 打开 <http://localhost:8080/swagger-ui.html>。
2. 调用 `POST /api/v1/auth/login` 获取 `data.token`。
3. 点击右上角 **Authorize**，在输入框中填入 Token 本身（不需要手动加 `Bearer `）。
4. 展开任意接口，点击 **Try it out** → **Execute**。

所有接口均为同源调用，Swagger 页面可以直接调试。OpenAPI JSON 位于 <http://localhost:8080/v3/api-docs>。

---

## 2. 通用约定

### 2.1 请求头

| 请求头 | 取值 | 说明 |
| --- | --- | --- |
| `Content-Type` | `application/json` | 有请求体时必须 |
| `Authorization` | `Bearer <JWT>` | 除注册、登录外均必须 |
| `Accept` | `application/json` | 可选 |

Token 默认有效期 3600 秒。服务每次请求都会从数据库重新加载用户和角色，角色变化后旧 Token 不会继续沿用旧权限。

### 2.2 统一响应

成功：

```json
{
  "code": "SUCCESS",
  "message": "success",
  "data": {}
}
```

失败：

```json
{
  "code": "INVALID_ARGUMENT",
  "message": "请求参数、JSON字段或格式不合法，请参阅接口文档",
  "data": null
}
```

- `code` 为稳定的业务码，客户端应优先按 `code` 判断。
- 失败时 HTTP 状态码为真实错误码，**不会把所有异常都返回 200**。
- 错误消息为中文、面向调用方，不包含 SQL、堆栈、密码或 Token。

### 2.3 分页

请求参数：

| 参数 | 类型 | 默认值 | 约束 |
| --- | --- | --- | --- |
| `page` | int | 1 | 1 ~ 1000000，从 1 开始 |
| `size` | int | 20 | 1 ~ 100 |

响应 `data` 的结构：

```json
{
  "items": [],
  "total": 0,
  "page": 1,
  "size": 20
}
```

### 2.4 时间

- 请求时间使用带时区的 ISO-8601，例如 `2026-10-20T08:00:00+08:00` 或 `2026-10-20T00:00:00Z`。
- 响应统一为 UTC 的 ISO-8601，例如 `2026-10-20T00:00:00Z`。
- 时间范围筛选包含边界；`from` 晚于 `to` 返回 400。
- 数据库存储 UTC epoch 毫秒。

### 2.5 JSON 与校验

- 请求体必须是 JSON，字段名区分大小写。
- **未知 JSON 字段一律返回 400**，不会静默忽略，防止客户端误传 `role`、`creatorId` 等字段。
- 路径参数和查询参数类型不合法返回 400。
- 所有字段校验在 Controller/DTO 和领域层双重执行。

---

## 3. 权限矩阵

| 操作 | OPERATOR | MONITOR | MAINTAINER | VIEWER | 未登录 |
| --- | --- | --- | --- | --- | --- |
| 注册、登录 | 允许 | 允许 | 允许 | 允许 | 允许 |
| 查询路线、任务、告警 | ✅ | ✅ | ✅ | ✅ | 401 |
| 新增/修改/删除路线 | ✅ | 403 | 403 | 403 | 401 |
| 新增/修改/删除任务 | ✅ | 403 | 403 | 403 | 401 |
| 确认告警 | 403 | ✅ | 403 | 403 | 401 |
| 查询操作日志 | 仅本人 | 仅本人 | 全部 | 全部 | 401 |
| 查询机器人信息、运行状态 | ✅ | ✅ | ✅ | ✅ | 401 |
| 下发运动命令 FORWARD/BACKWARD/LEFT/RIGHT | ✅ | 403 | 403 | 403 | 401 |
| 下发停止命令 STOP | ✅ | ✅（FR-MON-004） | 403 | 403 | 401 |

说明：

- 注册接口固定创建 `VIEWER`，请求中出现 `role` 字段会直接 400。
- 权限同时在 Spring Security 过滤链和应用层 `RolePolicy` 校验，避免只依赖 URL 匹配。
- 日志范围由服务端根据当前数据库角色计算，客户端参数不能扩大范围。

---

## 4. API 清单

| # | 方法 | 路径（相对 `/api/v1`） | 权限 | 说明 |
| --- | --- | --- | --- | --- |
| 1 | POST | `/auth/register` | 公开 | 用户注册（固定 VIEWER） |
| 2 | POST | `/auth/login` | 公开 | 登录并签发 JWT |
| 3 | GET | `/auth/me` | 已登录 | 当前用户信息 |
| 4 | POST | `/routes` | OPERATOR | 创建巡检路线 |
| 5 | GET | `/routes` | 已登录 | 分页查询路线 |
| 6 | GET | `/routes/{id}` | 已登录 | 路线详情 |
| 7 | PUT | `/routes/{id}` | OPERATOR | 完整修改路线 |
| 8 | DELETE | `/routes/{id}` | OPERATOR | 删除路线 |
| 9 | POST | `/tasks` | OPERATOR | 创建巡检任务计划 |
| 10 | GET | `/tasks` | 已登录 | 分页查询任务，可按状态过滤 |
| 11 | GET | `/tasks/{id}` | 已登录 | 任务详情 |
| 12 | PUT | `/tasks/{id}` | OPERATOR | 完整修改任务 |
| 13 | DELETE | `/tasks/{id}` | OPERATOR | 删除任务 |
| 14 | GET | `/alarms` | 已登录 | 分页查询告警，可按级别/状态/时间过滤 |
| 15 | GET | `/alarms/{id}` | 已登录 | 告警详情 |
| 16 | PATCH | `/alarms/{id}/ack` | MONITOR | 确认告警 |
| 17 | GET | `/control-logs` | 已登录 | 分页查询操作日志 |
| 18 | GET | `/robot` | 已登录 | 唯一机器人的基本信息与在线摘要 |
| 19 | GET | `/robot/status` | 已登录 | 机器人运行状态与遥测 |
| 20 | POST | `/robot/control` | OPERATOR；MONITOR 仅 STOP | 下发运动/停止命令（经 MQTT） |

---

## 5. 接口详情

### 5.1 用户注册

`POST /api/v1/auth/register`

权限：公开。注册成功后角色固定为 `VIEWER`，不能提交 `role`、`id`、`creatorId` 等额外字段。

请求体：

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `username` | string | 是 | 3~32 位字母、数字、下划线；不区分大小写唯一 |
| `password` | string | 是 | 8~72 位非空格 ASCII 可见字符；BCrypt 散列存储 |

请求示例：

```json
{
  "username": "viewer01",
  "password": "ExamplePass123!"
}
```

响应 `201 Created`：

```json
{
  "code": "SUCCESS",
  "message": "success",
  "data": {
    "id": 1,
    "username": "viewer01",
    "role": "VIEWER",
    "createdAt": "2026-10-19T03:00:00Z"
  }
}
```

错误：

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | `INVALID_ARGUMENT` | 用户名/密码格式不合法、未知字段、JSON 格式错误 |
| 409 | `BUSINESS_CONFLICT` | 用户名已存在（大小写不敏感） |

### 5.2 用户登录

`POST /api/v1/auth/login`

权限：公开。

请求体字段同注册。请求示例：

```json
{
  "username": "demo_operator",
  "password": "你设置的演示密码"
}
```

响应 `200 OK`：

```json
{
  "code": "SUCCESS",
  "message": "success",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 3600,
    "user": {
      "id": 1,
      "username": "demo_operator",
      "role": "OPERATOR",
      "createdAt": "2026-10-19T03:00:00Z"
    }
  }
}
```

错误：

| HTTP | code | 场景 |
| --- | --- | --- |
| 401 | `LOGIN_FAILED` | 用户名不存在或密码错误（统一提示，不区分账号是否存在） |
| 400 | `INVALID_ARGUMENT` | 请求字段格式不合法 |

登录成功和失败都会写入操作日志（失败日志不含密码）。

### 5.3 当前用户

`GET /api/v1/auth/me`

权限：已登录。响应 `data` 为 `UserView`。

```json
{
  "code": "SUCCESS",
  "message": "success",
  "data": {
    "id": 1,
    "username": "demo_operator",
    "role": "OPERATOR",
    "createdAt": "2026-10-19T03:00:00Z"
  }
}
```

错误：401 `UNAUTHORIZED` / `TOKEN_EXPIRED`。

---

### 5.4 创建路线

`POST /api/v1/routes`

权限：`OPERATOR`。

请求体：

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `name` | string | 是 | 1~100 字符，去首尾空格后非空 |
| `description` | string | 否 | 最多 1000 字符 |
| `points` | array | 是 | 1~200 个点位 |
| `points[].sequence` | int | 是 | 非负，路线内唯一；响应按升序排序 |
| `points[].x` | number | 是 | 有限数（米） |
| `points[].y` | number | 是 | 有限数（米） |
| `points[].yaw` | number | 否 | 有限数，范围 `[-π, π]`（弧度） |

请求示例：

```json
{
  "name": "仓库东区路线",
  "description": "本阶段只维护路线点位数据，不执行巡逻",
  "points": [
    { "sequence": 0, "x": 1.5, "y": 2.0, "yaw": 0 },
    { "sequence": 1, "x": 3.0, "y": 4.5 }
  ]
}
```

响应 `201 Created`：

```json
{
  "code": "SUCCESS",
  "message": "success",
  "data": {
    "id": 1,
    "name": "仓库东区路线",
    "description": "本阶段只维护路线点位数据，不执行巡逻",
    "points": [
      { "sequence": 0, "x": 1.5, "y": 2.0, "yaw": 0.0 },
      { "sequence": 1, "x": 3.0, "y": 4.5, "yaw": null }
    ],
    "createdAt": "2026-10-19T03:00:00Z",
    "updatedAt": "2026-10-19T03:00:00Z"
  }
}
```

错误：

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | `INVALID_ARGUMENT` | 点名称为空/过长、点位为空或超过 200、序号重复/负数、坐标非有限数、yaw 越界、未知字段 |
| 403 | `FORBIDDEN` | 非 OPERATOR |
| 409 | `DATA_CONFLICT` | 数据库唯一/外键约束冲突 |
| 500 | `DATABASE_ERROR` | 数据库不可用 |

### 5.5 分页查询路线

`GET /api/v1/routes?page=1&size=20`

权限：已登录。

响应：

```json
{
  "code": "SUCCESS",
  "message": "success",
  "data": {
    "items": [
      {
        "id": 1,
        "name": "仓库东区路线",
        "description": "本阶段只维护路线点位数据，不执行巡逻",
        "points": [
          { "sequence": 0, "x": 1.5, "y": 2.0, "yaw": 0.0 },
          { "sequence": 1, "x": 3.0, "y": 4.5, "yaw": null }
        ],
        "createdAt": "2026-10-19T03:00:00Z",
        "updatedAt": "2026-10-19T03:00:00Z"
      }
    ],
    "total": 1,
    "page": 1,
    "size": 20
  }
}
```

结果按 `id` 倒序。本阶段路线列表不支持名称过滤。

### 5.6 路线详情

`GET /api/v1/routes/{id}`

权限：已登录。`id` 必须为正整数。

- 成功：`200 OK`，`data` 为 `RouteView`。
- 不存在：`404 NOT_FOUND`。
- `id` 非正整数：`400 INVALID_ARGUMENT`。

### 5.7 修改路线

`PUT /api/v1/routes/{id}`

权限：`OPERATOR`。**完整更新**：请求体同创建路线，未提交的可选字段会被置空，点位整体替换。`id` 和 `createdAt` 保持不变，`updatedAt` 由服务器更新。

成功：`200 OK`，`data` 为更新后的 `RouteView`。

错误：400（参数）、403（角色）、404（路线不存在）、409（数据冲突）。

### 5.8 删除路线

`DELETE /api/v1/routes/{id}`

权限：`OPERATOR`。

成功：`200 OK`：

```json
{ "code": "SUCCESS", "message": "success", "data": null }
```

约束：

- 路线被任意任务引用（包括已取消任务）时返回 `409 BUSINESS_CONFLICT`，必须先删除引用任务。
- 路线不存在返回 `404`。
- 删除路线时其点位级联删除。

---

### 5.9 创建任务

`POST /api/v1/tasks`

权限：`OPERATOR`。`creatorId` 从当前登录用户取得，**请求中不能提交**。

请求体：

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `name` | string | 是 | 1~100 字符 |
| `routeId` | long | 是 | 必须引用已存在的路线 |
| `plannedAt` | string | 条件 | 当 `status=PLANNED` 时必填；ISO-8601 带时区 |
| `status` | string | 是 | `DRAFT` / `PLANNED` / `CANCELLED`；新建不能是 `CANCELLED` |

请求示例（草稿）：

```json
{
  "name": "早班巡检计划",
  "routeId": 1,
  "plannedAt": "2026-10-20T08:00:00+08:00",
  "status": "DRAFT"
}
```

响应 `201 Created`：

```json
{
  "code": "SUCCESS",
  "message": "success",
  "data": {
    "id": 1,
    "name": "早班巡检计划",
    "routeId": 1,
    "creatorId": 1,
    "plannedAt": "2026-10-20T00:00:00Z",
    "status": "DRAFT",
    "createdAt": "2026-10-19T03:10:00Z",
    "updatedAt": "2026-10-19T03:10:00Z"
  }
}
```

错误：

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | `INVALID_ARGUMENT` | 状态非法、PLANNED 缺少计划时间、新建 CANCELLED、未知字段 |
| 403 | `FORBIDDEN` | 非 OPERATOR |
| 404 | `NOT_FOUND` | `routeId` 对应路线不存在 |
| 409 | `DATA_CONFLICT` | 数据库约束冲突 |

### 5.10 分页查询任务

`GET /api/v1/tasks?page=1&size=20&status=PLANNED`

权限：已登录。

`status` 可选，取值 `DRAFT`、`PLANNED`、`CANCELLED`；非法值返回 400。结果按 `id` 倒序。

响应：

```json
{
  "code": "SUCCESS",
  "message": "success",
  "data": {
    "items": [
      {
        "id": 1,
        "name": "早班巡检计划",
        "routeId": 1,
        "creatorId": 1,
        "plannedAt": "2026-10-20T00:00:00Z",
        "status": "PLANNED",
        "createdAt": "2026-10-19T03:10:00Z",
        "updatedAt": "2026-10-19T03:20:00Z"
      }
    ],
    "total": 1,
    "page": 1,
    "size": 20
  }
}
```

### 5.11 任务详情

`GET /api/v1/tasks/{id}`

权限：已登录。成功 `200`，不存在 `404`，非法 ID `400`。

### 5.12 修改任务

`PUT /api/v1/tasks/{id}`

权限：`OPERATOR`。完整更新业务字段 `name`、`routeId`、`plannedAt`、`status`；`creatorId`、`createdAt` 不可修改。

允许的状态流转：

| 当前状态 | 允许更新为 | 说明 |
| --- | --- | --- |
| `DRAFT` | `DRAFT` / `PLANNED` / `CANCELLED` | 草稿可转为已计划或取消 |
| `PLANNED` | `PLANNED` / `DRAFT` / `CANCELLED` | 已计划可退回草稿或取消 |
| `CANCELLED` | 不可修改 | 返回 `409`，只能删除后重建 |

补充：`PLANNED` 必须有 `plannedAt`；取消是单向的，取消后不能恢复。

成功：`200`，`data` 为更新后的 `TaskView`。

错误：400（状态/时间/参数）、403、404（任务或新路线不存在）、409（已取消任务修改）。

### 5.13 删除任务

`DELETE /api/v1/tasks/{id}`

权限：`OPERATOR`。任意状态均可删除；成功返回 `200` 且 `data=null`。不存在返回 `404`。

> 本阶段没有任务下发和执行状态。`DRAFT/PLANNED/CANCELLED` 只描述计划数据，不代表小车已执行、完成或失败。

---

### 5.14 分页查询告警

`GET /api/v1/alarms?page=1&size=20&level=WARNING&status=UNACKNOWLEDGED&from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00Z`

权限：已登录。

| 参数 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `page` | int | 否 | 1~1000000 |
| `size` | int | 否 | 1~100 |
| `level` | string | 否 | `INFO` / `WARNING` / `CRITICAL` |
| `status` | string | 否 | `UNACKNOWLEDGED` / `ACKNOWLEDGED` |
| `from` | ISO-8601 | 否 | 发生时间下界，含边界 |
| `to` | ISO-8601 | 否 | 发生时间上界，含边界 |

结果按 `occurredAt` 倒序、`id` 倒序。

响应：

```json
{
  "code": "SUCCESS",
  "message": "success",
  "data": {
    "items": [
      {
        "id": 1,
        "title": "演示告警：测试夹具",
        "description": "仅供接口演示，并非来自真实设备",
        "level": "WARNING",
        "status": "UNACKNOWLEDGED",
        "source": "DEMO_FIXTURE",
        "occurredAt": "2026-10-19T03:00:00Z",
        "acknowledgedBy": null,
        "acknowledgedAt": null
      }
    ],
    "total": 1,
    "page": 1,
    "size": 20
  }
}
```

说明：默认不启用演示模式时，告警表为空；不会伪造设备告警。

### 5.15 告警详情

`GET /api/v1/alarms/{id}`

权限：已登录。成功 `200`，不存在 `404`，非法 ID `400`。

### 5.16 确认告警

`PATCH /api/v1/alarms/{id}/ack`

权限：`MONITOR`。无请求体。

成功 `200`：

```json
{
  "code": "SUCCESS",
  "message": "success",
  "data": {
    "id": 1,
    "title": "演示告警：测试夹具",
    "description": "仅供接口演示，并非来自真实设备",
    "level": "WARNING",
    "status": "ACKNOWLEDGED",
    "source": "DEMO_FIXTURE",
    "occurredAt": "2026-10-19T03:00:00Z",
    "acknowledgedBy": 2,
    "acknowledgedAt": "2026-10-19T03:30:00Z"
  }
}
```

规则：

- 只有 `UNACKNOWLEDGED` 可以确认，确认后写入确认人和时间。
- 重复确认返回 `409 BUSINESS_CONFLICT`；并发重复确认通过带旧状态条件的 UPDATE 保证只有一个成功。
- 告警不存在返回 `404`；非 MONITOR 返回 `403`。

### 5.17 查询操作日志

`GET /api/v1/control-logs?page=1&size=20&operation=ROUTE_CREATE&from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00Z`

权限：已登录。

| 参数 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `page` | int | 否 | 1~1000000 |
| `size` | int | 否 | 1~100 |
| `operation` | string | 否 | 1~40 位大写字母或下划线，如 `ROUTE_CREATE` |
| `from` | ISO-8601 | 否 | 操作时间下界，含边界 |
| `to` | ISO-8601 | 否 | 操作时间上界，含边界 |

数据范围：

- `OPERATOR`、`MONITOR`：只能看到本人产生的日志；即使传其他参数也不能扩大范围。
- `MAINTAINER`、`VIEWER`：可查看全部日志。

响应：

```json
{
  "code": "SUCCESS",
  "message": "success",
  "data": {
    "items": [
      {
        "id": 12,
        "actorId": 1,
        "actorName": "demo_operator",
        "operation": "ROUTE_CREATE",
        "target": "route:1",
        "result": "SUCCESS",
        "occurredAt": "2026-10-19T03:00:00Z",
        "error": null
      }
    ],
    "total": 1,
    "page": 1,
    "size": 20
  }
}
```

日志由真实业务操作自动产生，不记录明文密码、JWT、BCrypt 散列或请求体。失败操作记录 `result=FAILURE` 和错误码。

常见 `operation`：

`REGISTER`、`LOGIN`、`ROUTE_CREATE`、`ROUTE_UPDATE`、`ROUTE_DELETE`、`TASK_CREATE`、`TASK_UPDATE`、`TASK_DELETE`、`ALARM_ACK`、`AUTH_FAILURE`、`BUSINESS_FAILURE`、`DEMO_INIT`、`ROBOT_CONTROL`。

`ROBOT_CONTROL` 的 `target` 为 `robot:<机器人编号>:<命令>`，如 `robot:SCOUT-001:FORWARD`；非法命令记为 `robot:SCOUT-001:INVALID`（不回显请求内容）。

### 5.18 机器人基本信息

`GET /api/v1/robot`

权限：已登录。

```json
{
  "code": "SUCCESS",
  "message": "success",
  "data": {
    "robotId": "SCOUT-001",
    "name": "SCOUT MINI Pro Kit",
    "model": "SCOUT MINI",
    "commands": ["FORWARD", "BACKWARD", "LEFT", "RIGHT", "STOP"],
    "online": true,
    "mqttConnected": true,
    "lastSeenAt": "2026-10-23T06:25:01.512Z"
  }
}
```

`online` 的判定：后端与 Broker 已连接，且最近一次心跳报告在线并在 `heartbeat-timeout-ms`（默认 3000 ms）之内收到。桥接节点异常断开时 Broker 会立即转发其遗嘱消息，后端随即判定离线。

### 5.19 机器人运行状态

`GET /api/v1/robot/status`

权限：已登录。前端可每 0.5~1 秒轮询一次。

```json
{
  "code": "SUCCESS",
  "message": "success",
  "data": {
    "robotId": "SCOUT-001",
    "online": true,
    "motionState": "FORWARD",
    "batteryPct": null,
    "batteryVoltage": 25.2,
    "linearVelocity": 0.3,
    "angularVelocity": 0.0,
    "position": { "x": 1.5, "y": 2.5, "yaw": 0.1 },
    "mqttConnected": true,
    "chassisConnected": true,
    "lastSeenAt": "2026-10-23T06:25:01.512Z",
    "telemetryAt": "2026-10-23T06:25:01.430Z",
    "lastAck": {
      "seq": 9,
      "command": "FORWARD",
      "result": "APPLIED",
      "reason": null,
      "receivedAt": "2026-10-23T06:25:01.320Z"
    }
  }
}
```

- 离线时 `motionState=UNKNOWN`，`batteryPct`、速度、`position.*`、`chassisConnected` 均为 `null`；不展示过期数据。
- 在线但最近一次状态上报已超过心跳超时时间，遥测同样返回 `null`。
- `batteryPct`：松灵驱动没有提供电量百分比，固定为 `null`；`batteryVoltage` 来自底盘 `/scout_status`。
- `position` 为轮式里程计累计位姿，会漂移，不是全局定位。
- `lastAck` 为小车侧对最近一条命令的回执；`APPLIED` 只表示已写入 `/cmd_vel`。

与需求文档 A.4.3 的字段对应（本项目沿用 exp2 的 camelCase 与统一响应外层）：`online`→`online`、`motion_state`→`motionState`、`battery_pct`→`batteryPct`、`linear_velocity`→`linearVelocity`、`angular_velocity`→`angularVelocity`、`position{x,y,yaw}`→`position{x,y,yaw}`、`mqtt_connected`→`mqttConnected`、`last_seen_at`→`lastSeenAt`。

### 5.20 下发运动/停止命令

`POST /api/v1/robot/control`

权限：OPERATOR 可下发全部命令；MONITOR 只能下发 `STOP`；MAINTAINER、VIEWER 返回 403。

请求：

```json
{ "command": "FORWARD" }
```

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `command` | string | 是 | `FORWARD`、`BACKWARD`、`LEFT`、`RIGHT`、`STOP` 之一，区分大小写 |

响应 200：

```json
{
  "code": "SUCCESS",
  "message": "success",
  "data": { "accepted": true, "command": "FORWARD", "seq": 12, "timestamp": "2026-10-23T06:25:01.300Z" }
}
```

控制语义：

- **按住即走，松开即停**：按住方向键期间每 200 ms 重发一次同一命令；停发超过 0.6 s，小车侧看门狗自动停车（需求 PER-006 ≤ 1 s）。松开时应再发一次 `STOP`。
- 后端不发送速度，速度只在小车侧配置（默认 0.3 m/s、0.5 rad/s 原地转向）。
- 校验顺序：角色 → 命令白名单 → MONITOR 只能 STOP → MQTT 已连接 → 运动命令要求机器人在线。`STOP` 不受在线状态限制，优先级最高。
- 同一用户、同一命令在 1.5 秒内的重复视为保活，照常发布但只写一条 `ROBOT_CONTROL` 日志（FR-COM-002）；失败同样按“用户+命令+错误码”去重记录。
- `seq` 为本次后端运行期内递增的序号，小车回执（`lastAck.seq`）与之对应；小车侧丢弃重复或乱序的 `seq`。

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | `INVALID_COMMAND` | `command` 缺失或不在白名单内 |
| 400 | `INVALID_ARGUMENT` | JSON 格式错误或含未知字段（如 `speed`） |
| 401 | `UNAUTHORIZED` / `TOKEN_EXPIRED` | 未登录或 Token 失效 |
| 403 | `FORBIDDEN` | 角色不允许（MONITOR 下发运动命令等） |
| 409 | `ROBOT_OFFLINE` | 机器人离线时下发运动命令 |
| 503 | `MQTT_UNAVAILABLE` | 后端未连接 Broker，或 Broker 未在 1 秒内确认（命令未下发） |

---

## 6. 错误码

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | `INVALID_ARGUMENT` | 参数、枚举、时间、分页、JSON 格式或未知字段不合法 |
| 400 | `INVALID_COMMAND` | 机器人控制命令不在白名单内 |
| 401 | `UNAUTHORIZED` | 缺少凭证、凭证非法、用户不存在 |
| 401 | `TOKEN_EXPIRED` | Token 已过期 |
| 401 | `LOGIN_FAILED` | 登录用户名或密码错误 |
| 403 | `FORBIDDEN` | 当前角色无权执行该操作 |
| 404 | `NOT_FOUND` | 资源或接口不存在 |
| 405 | `METHOD_NOT_ALLOWED` | HTTP 方法不支持 |
| 409 | `BUSINESS_CONFLICT` | 重复账号、重复确认、非法状态变化、路线被引用 |
| 409 | `DATA_CONFLICT` | 数据库唯一/外键等约束冲突 |
| 409 | `ROBOT_OFFLINE` | 机器人离线，拒绝运动命令 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 未使用 `application/json` |
| 500 | `DATABASE_ERROR` | 数据库操作失败（不泄漏 SQL） |
| 500 | `INTERNAL_ERROR` | 未预期的内部错误（不泄漏堆栈） |
| 503 | `MQTT_UNAVAILABLE` | MQTT 服务不可用，命令未下发 |

401 响应会带 `WWW-Authenticate: Bearer`。错误返回不会包含请求体、密码、Token 或数据库细节。

---

## 7. 数据模型

### 7.1 UserView

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | long | 用户 ID |
| `username` | string | 用户名 |
| `role` | string | `OPERATOR` / `MONITOR` / `MAINTAINER` / `VIEWER` |
| `createdAt` | string | UTC 时间 |

### 7.2 RouteView

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | long | 路线 ID |
| `name` | string | 名称 |
| `description` | string/null | 描述 |
| `points` | array | 点位，按 `sequence` 升序 |
| `createdAt` | string | 创建时间 |
| `updatedAt` | string | 更新时间 |

### 7.3 TaskView

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | long | 任务 ID |
| `name` | string | 任务名称 |
| `routeId` | long | 关联路线 ID |
| `creatorId` | long | 创建用户 ID（服务端赋值） |
| `plannedAt` | string/null | 计划执行时间 |
| `status` | string | `DRAFT` / `PLANNED` / `CANCELLED` |
| `createdAt` | string | 创建时间 |
| `updatedAt` | string | 更新时间 |

### 7.4 AlarmView

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | long | 告警 ID |
| `title` | string | 标题 |
| `description` | string/null | 描述 |
| `level` | string | `INFO` / `WARNING` / `CRITICAL` |
| `status` | string | `UNACKNOWLEDGED` / `ACKNOWLEDGED` |
| `source` | string | 来源：演示数据 `DEMO_FIXTURE`；后端与 Broker 断连 `MQTT`；小车离线、电量过低、底盘无数据为机器人编号（如 `SCOUT-001`） |
| `occurredAt` | string | 发生时间 |
| `acknowledgedBy` | long/null | 确认人 ID |
| `acknowledgedAt` | string/null | 确认时间 |

### 7.5 LogView

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | long | 日志 ID |
| `actorId` | long/null | 操作用户 ID，匿名失败可为 null |
| `actorName` | string | 操作用户名或 `anonymous` |
| `operation` | string | 操作类型 |
| `target` | string | 操作对象，如 `route:1`、`task:2`、`alarm:3` |
| `result` | string | `SUCCESS` / `FAILURE` |
| `occurredAt` | string | 时间戳 |
| `error` | string/null | 失败错误码 |

### 7.6 RobotView

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `robotId` | string | 机器人编号，同 MQTT Topic 中的 `{id}` |
| `name` / `model` | string | 名称与型号（配置项） |
| `commands` | array | 允许的命令 |
| `online` | boolean | MQTT 已连接且心跳未超时 |
| `mqttConnected` | boolean | 后端与 Broker 的连接状态 |
| `lastSeenAt` | string/null | 最近一次心跳的接收时间 |

### 7.7 RobotStatusView

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `robotId` | string | 机器人编号 |
| `online` | boolean | 同上 |
| `motionState` | string | `STOPPED` / `FORWARD` / `BACKWARD` / `LEFT` / `RIGHT` / `UNKNOWN`（离线或无有效上报） |
| `batteryPct` | number/null | 电量百分比，驱动未提供时为 null |
| `batteryVoltage` | number/null | 电池电压（V） |
| `linearVelocity` / `angularVelocity` | number/null | m/s、rad/s，来自底盘反馈 |
| `position` | object | `x`、`y`（米）、`yaw`（弧度），字段可为 null |
| `mqttConnected` | boolean | 后端与 Broker 的连接状态 |
| `chassisConnected` | boolean/null | 桥接节点是否收到底盘状态；未知为 null |
| `lastSeenAt` / `telemetryAt` | string/null | 最近一次心跳 / 状态上报的接收时间 |
| `lastAck` | object/null | `seq`、`command`、`result`（`APPLIED`/`REJECTED`）、`reason`、`receivedAt` |

### 7.8 ControlResultView

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `accepted` | boolean | 恒为 true（失败走错误响应） |
| `command` | string | 本次命令 |
| `seq` | long | 本次后端运行期内的消息序号 |
| `timestamp` | string | 后端受理时间 |

---

## 8. 调用示例

以下示例假定服务运行在 `http://localhost:8080`，演示账号已启用。

### 8.1 PowerShell

```powershell
$base = "http://localhost:8080/api/v1"

$login = Invoke-RestMethod -Method Post -Uri "$base/auth/login" `
  -ContentType "application/json" `
  -Body (@{ username = "demo_operator"; password = $env:PATROL_DEMO_PASSWORD } | ConvertTo-Json)
$token = $login.data.token
$headers = @{ Authorization = "Bearer $token" }

$route = Invoke-RestMethod -Method Post -Uri "$base/routes" -Headers $headers `
  -ContentType "application/json" `
  -Body (@{
    name = "仓库东区路线"
    description = "PowerShell 调用示例"
    points = @(
      @{ sequence = 0; x = 1.5; y = 2.0; yaw = 0 },
      @{ sequence = 1; x = 3.0; y = 4.5 }
    )
  } | ConvertTo-Json -Depth 5)

$task = Invoke-RestMethod -Method Post -Uri "$base/tasks" -Headers $headers `
  -ContentType "application/json" `
  -Body (@{
    name = "早班巡检计划"
    routeId = $route.data.id
    plannedAt = "2026-10-20T08:00:00+08:00"
    status = "DRAFT"
  } | ConvertTo-Json)

Invoke-RestMethod -Method Get -Uri "$base/routes?page=1&size=20" -Headers $headers

# 机器人：查询状态，按住前进 1 秒（每 200 ms 重发一次），然后停止
Invoke-RestMethod -Method Get -Uri "$base/robot/status" -Headers $headers
$forward = @{ command = "FORWARD" } | ConvertTo-Json
1..5 | ForEach-Object {
  Invoke-RestMethod -Method Post -Uri "$base/robot/control" -Headers $headers -ContentType "application/json" -Body $forward
  Start-Sleep -Milliseconds 200
}
Invoke-RestMethod -Method Post -Uri "$base/robot/control" -Headers $headers -ContentType "application/json" -Body '{"command":"STOP"}'
```

### 8.2 curl

```bash
BASE=http://localhost:8080/api/v1

TOKEN=$(curl -s -X POST "$BASE/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"demo_operator","password":"你的演示密码"}' \
  | python -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")

curl -s -X POST "$BASE/routes" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"name":"仓库东区路线","points":[{"sequence":0,"x":1.5,"y":2.0,"yaw":0}]}'

curl -s "$BASE/tasks?status=DRAFT&page=1&size=20" \
  -H "Authorization: Bearer $TOKEN"

curl -s "$BASE/robot/status" -H "Authorization: Bearer $TOKEN"
curl -s -X POST "$BASE/robot/control" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"command":"STOP"}'
```

也可用 `exp3/scripts/robot_cli.py` 模拟“按住按钮”：`python scripts/robot_cli.py --user demo_operator hold FORWARD 1.5`。

### 8.3 常见调试顺序

1. `POST /auth/register` 注册 `VIEWER`（可选）。
2. `POST /auth/login` 获取 Token。
3. `GET /auth/me` 验证 Token。
4. `POST /routes` 创建路线，记录 `data.id`。
5. `POST /tasks` 使用该 `routeId` 创建草稿任务。
6. `PUT /tasks/{id}` 转为 `PLANNED`，验证状态流转。
7. `GET /alarms` 查看演示告警，`PATCH /alarms/{id}/ack` 用 MONITOR 确认。
8. `GET /control-logs` 查看上述操作自动生成的日志。
9. `GET /robot/status` 确认 `online=true` 后，用 OPERATOR 调 `POST /robot/control`。

---

## 9. 不在当前范围

exp3 已实现 MQTT 通信、机器人状态/心跳/告警与运动控制（含小车侧桥接节点 `exp3/robot-bridge`）。以下能力仍**没有实现**：

- 前端页面（按住/松开、失焦自动 STOP 等交互需由前端按 5.20 的语义实现）；
- 一键返航 `HOME`、自主导航、SLAM、路径规划、任务调度下发；
- 摄像头、RTSP、WebRTC、AI 视频分析；
- WebSocket 实时推送（状态目前靠轮询 5.19）；
- Redis、Kafka、微服务拆分、集群部署、公网部署；
- MySQL/PostgreSQL 等外部数据库。

任务状态仅表示计划数据，不代表真实机器人执行结果。机器人告警只由真实的 MQTT 事件（断连、心跳丢失、电量越限、底盘无数据）触发，演示告警仍标记 `DEMO_FIXTURE`。
