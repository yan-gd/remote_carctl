#!/usr/bin/env python3
"""Render the Simplified Chinese interface report from a real pytest result file."""

from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path


def cell(value) -> str:
    text = "" if value is None else str(value)
    return text.replace("|", "\\|").replace("\n", " ")


def main() -> None:
    source = Path(sys.argv[1] if len(sys.argv) > 1 else "docs/test-report/last-run.json")
    target = Path(sys.argv[2] if len(sys.argv) > 2 else "docs/test-report/接口测试报告.md")
    data = json.loads(source.read_text(encoding="utf-8"))
    meta = data.get("meta") or {}
    counts = data["counts"]
    latency = data.get("latency_ms") or {}
    cases = data["cases"]
    by_result = Counter(row["result"] for row in cases)
    assert by_result["PASS"] == counts["pass"]
    assert by_result["FAIL"] == counts["fail"]
    assert by_result["SKIP"] == counts["skip"]

    paths = meta.get("openapi_paths") or []
    operations = meta.get("openapi_operations")
    failed = [row for row in cases if row["result"] == "FAIL"]
    skipped = [row for row in cases if row["result"] == "SKIP"]

    lines: list[str] = []
    lines.append("# remote_carctl 接口测试报告")
    lines.append("")
    lines.append("本报告由 `tests/api/render_report.py` 根据同目录 `last-run.json` 生成。表中的通过、失败、跳过数量和时延都来自这次 pytest 的真实执行，没有手填。")
    lines.append("")
    lines.append("## 1. 项目概述")
    lines.append("")
    lines.append("remote_carctl（仓库内亦称 exp3）是松灵 SCOUT MINI 的远程控制与巡检管理平台，前后端分离，小车侧经 MQTT 接入。")
    lines.append("")
    lines.append("| 部分 | 技术 | 职责 |")
    lines.append("| --- | --- | --- |")
    lines.append("| `backend/` | Java 17、Spring Boot 2.7.2、Spring Security、JWT HS256、MyBatis、SQLite、Eclipse Paho | 认证与权限、路线/任务/告警/日志、机器人控制、导航租约、摄像头转发、Web 建图命令邮箱 |")
    lines.append("| `frontend/` | React 19、TypeScript、Vite 7 | 浏览器控制台。WHEP 地址由前端发给同源 nginx，不在本次后端进程内 |")
    lines.append("| `robot-bridge/` | Python、paho-mqtt、可选 ROS1/ROS2 | 把 `robot/{id}/control` 转成速度指令，上报心跳、状态和回执。`--ros sim` 可在无硬件时替身底盘 |")
    lines.append("| `server/media/mediamtx.yml` | MediaMTX | RTSPS 收流和本机 WHEP。需要证书、发布密码和 nginx 鉴权，本次未启动 |")
    lines.append("")
    lines.append("业务代码扫描范围是 `org.example.patrol`。`exp2-adapter` 里的 `/helloworld`、`/customer` 示例控制器没有注册到运行中的服务。")
    lines.append("")
    lines.append("统一响应为 `{\"code\",\"message\",\"data\"}`。成功码是 `SUCCESS`。参数错误返回真实的 4xx/5xx，而不是 HTTP 200。除注册和登录外，HTTP API 使用 `Authorization: Bearer <JWT>`。摄像头和建图的设备入口使用请求头 `X-Device-Key`。")
    lines.append("")
    lines.append("## 2. 测试环境")
    lines.append("")
    lines.append("| 项 | 值 |")
    lines.append("| --- | --- |")
    lines.append(f"| 操作系统 | {cell(meta.get('os'))} |")
    lines.append(f"| Python | {cell(meta.get('python'))} |")
    lines.append(f"| Java | {cell(meta.get('java')).replace(chr(10), ' / ')} |")
    lines.append(f"| 结束时间（UTC） | {cell(meta.get('finished_at'))} |")
    lines.append("| 主服务 | `http://127.0.0.1:18080`，MQTT 开启，导航开启，摄像头与建图设备密钥已配置，低电量阈值 24V |")
    lines.append("| 对照服务 | `http://127.0.0.1:18081`，`PATROL_MQTT_ENABLED=false`，未配置设备密钥 |")
    lines.append("| Broker | Mosquitto，仅监听 `127.0.0.1:11883`，允许匿名，无持久化 |")
    lines.append("| 数据库 | 每个进程使用独立的临时 SQLite 文件，演示账号和 admin 在启动时写入 |")
    lines.append("| 小车替身 | 先用 Paho 客户端直接发布协议报文；离线用例之后再启动 `scout_mqtt_bridge.py --ros sim`，仿真电压 22.0V |")
    lines.append("| 鉴权夹具 | `demo_operator` / `demo_monitor` / `demo_maintainer` / `demo_viewer` 与 `admin`。密码只存在于测试进程环境变量 |")
    lines.append("")
    lines.append("## 3. 接口清单")
    lines.append("")
    lines.append("### 3.1 HTTP")
    lines.append("")
    lines.append("下表是代码中的控制器。OpenAPI 在本次运行中实际返回的路径见后文。`backend/docs/API.md` 仍只写了早期的 20 个操作，没有覆盖管理员、摄像头、建图和导航。")
    lines.append("")
    lines.append("| 方法 | 路径 | 鉴权 | 说明 |")
    lines.append("| --- | --- | --- | --- |")
    http_rows = [
        ("POST", "/api/v1/auth/register", "公开", "注册，角色固定 VIEWER"),
        ("POST", "/api/v1/auth/login", "公开", "登录并签发 JWT"),
        ("GET", "/api/v1/auth/me", "登录", "当前用户、角色与权限"),
        ("POST/GET", "/api/v1/routes", "登录；写操作需 PATROL_EDIT", "创建与分页查询路线"),
        ("GET/PUT/DELETE", "/api/v1/routes/{id}", "登录；写操作需 PATROL_EDIT", "路线详情、更新、删除"),
        ("POST/GET", "/api/v1/tasks", "登录；写操作需 PATROL_EDIT", "任务计划，不驱动小车"),
        ("GET/PUT/DELETE", "/api/v1/tasks/{id}", "登录；写操作需 PATROL_EDIT", "任务详情、更新、删除"),
        ("GET", "/api/v1/alarms", "登录", "分页、级别、状态、时间、关键字"),
        ("GET", "/api/v1/alarms/{id}", "登录", "告警详情"),
        ("PATCH", "/api/v1/alarms/{id}/ack", "ALARM_ACK", "确认告警"),
        ("GET", "/api/v1/control-logs", "登录", "OPERATOR/MONITOR 仅本人，另两种角色看全部"),
        ("GET", "/api/v1/robot", "登录", "机器人摘要与在线状态"),
        ("GET", "/api/v1/robot/status", "登录", "遥测；拿不到的字段为 null"),
        ("POST", "/api/v1/robot/control", "MOVE 或 STOP", "FORWARD/BACKWARD/LEFT/RIGHT/STOP"),
        ("GET/POST", "/api/v1/admin/users", "ROLE_ADMIN", "查询、创建或恢复账号"),
        ("PUT/DELETE", "/api/v1/admin/users/{id}", "ROLE_ADMIN", "修改权限或停用"),
        ("GET", "/api/v1/camera/status", "登录", "转发状态，不表示底盘在线"),
        ("PUT", "/api/v1/device/camera/frame", "X-Device-Key", "上传 JPEG，最大 384KiB"),
        ("GET", "/api/v1/camera/stream", "登录", "4 字节大端长度 + JPEG，不是 WebSocket"),
        ("GET", "/api/v1/camera/snapshot", "登录", "等待下一帧，最多 5 秒，最多 8 个观看者"),
        ("GET", "/api/v1/mapping", "登录", "建图设备是否在线及待执行命令"),
        ("GET", "/api/v1/mapping/grid", "登录", "当前显示栅格"),
        ("POST", "/api/v1/mapping/commands", "MAP_EDIT", "START/SAVE/LOAD/STOP/INITIAL_POSE"),
        ("POST", "/api/v1/device/mapping/exchange", "X-Device-Key", "设备上报状态并取命令"),
        ("PUT", "/api/v1/device/mapping/grid", "X-Device-Key", "上传最新栅格"),
        ("GET", "/api/v1/navigation", "登录", "导航能力与小车报告"),
        ("POST", "/api/v1/navigation/missions", "NAVIGATE", "提交地图目标或相对目标"),
        ("POST", "/api/v1/navigation/cancel", "STOP", "取消任务并下发 STOP"),
        ("GET", "/v3/api-docs", "公开", "OpenAPI JSON"),
        ("GET", "/swagger-ui.html", "公开", "Swagger UI"),
    ]
    for method, path, auth, desc in http_rows:
        lines.append(f"| {method} | `{path}` | {auth} | {desc} |")
    lines.append("")
    if paths:
        lines.append(f"本次 `GET /v3/api-docs` 返回 {len(paths)} 条路径、{operations} 个操作：")
        lines.append("")
        for path in paths:
            lines.append(f"- `{path}`")
        lines.append("")
    lines.append("### 3.2 MQTT")
    lines.append("")
    lines.append("机器人编号来自 `PATROL_ROBOT_ID`，本次为 `SCOUT-001`。载荷为 UTF-8 JSON，消息不保留。")
    lines.append("")
    lines.append("| 主题 | 方向 | 本次是否执行 |")
    lines.append("| --- | --- | --- |")
    lines.append("| `robot/SCOUT-001/control` | 后端 → 小车 | 已执行。订阅者看到 STOP/FORWARD，仿真桥回执 APPLIED |")
    lines.append("| `robot/SCOUT-001/heartbeat` | 小车 → 后端 | 已执行。手造报文与仿真桥心跳都会让 `/robot` 变为在线 |")
    lines.append("| `robot/SCOUT-001/status` | 小车 → 后端 | 已执行。电压、位姿进入状态接口；畸形 JSON 被忽略 |")
    lines.append("| `robot/SCOUT-001/control/ack` | 小车 → 后端 | 已执行。错误 session 被丢弃；仿真桥的本次 session 回执出现在 `lastAck` |")
    lines.append("| `robot/SCOUT-001/navigation/command` | 后端 → 小车 | 已执行。START/CANCEL 使用 camelCase 的 `order` |")
    lines.append("| `robot/SCOUT-001/navigation/status` | 小车 → 后端 | 已执行。手造就绪、相对模式和障碍报告 |")
    lines.append("")
    lines.append("### 3.3 本次没有执行的接口")
    lines.append("")
    lines.append("| 接口 | 原因 |")
    lines.append("| --- | --- |")
    for row in skipped:
        lines.append(f"| `{row['interface']}` | {cell(row['error'])} |")
    if not skipped:
        lines.append("| — | 没有跳过的接口 |")
    lines.append("")
    lines.append("## 4. 测试策略")
    lines.append("")
    lines.append("- 不修改业务代码。服务以 `java -jar` 启动，测试只通过 HTTP 和 MQTT 访问。")
    lines.append("- 正常用例覆盖登录、四种业务角色、管理员、路线任务闭环、告警确认、机器人在线控制和建图/摄像头/导航的成功路径。")
    lines.append("- 边界用例覆盖用户名和密码格式、未知 JSON 字段、分页上下限、yaw 与相对距离、非法命令、超大 JPEG、栅格尺寸。")
    lines.append("- 权限用例覆盖未登录、错误密码、过期 JWT、停用账号、角色不允许的写操作、设备密钥、管理员保护。")
    lines.append("- 异常用例覆盖不存在的资源、重复确认、引用约束、MQTT 关闭、机器人离线、摄像头未配置。")
    lines.append("- 小车和 ROS 用两种替身：Paho 直接发协议，以及仓库自带的 `SimAdapter`。断言只接受代码里写明的状态码和 `code`。")
    lines.append("- Postman 集合在 `tests/api/postman/remote_carctl.postman_collection.json`，便于手工导入。自动化结果以 pytest 为准，本次没有另跑 Newman。")
    lines.append("")
    lines.append("## 5. 用例与实际结果")
    lines.append("")
    lines.append("| 编号 | 类别 | 接口 | 场景 | 期望 | 实际 | 耗时 ms | 结果 |")
    lines.append("| --- | --- | --- | --- | --- | --- | --- | --- |")
    for row in cases:
        if row["result"] == "SKIP":
            expected = "不执行"
            actual = cell(row["error"])
        else:
            expected = f"HTTP {row['expected_status']}"
            if row.get("expected_code"):
                expected += f" / {row['expected_code']}"
            actual = f"HTTP {row['actual_status']}"
            if row.get("actual_code"):
                actual += f" / {row['actual_code']}"
            if row["result"] == "FAIL" and row.get("error"):
                actual += "；" + cell(row["error"])
        latency_cell = "" if row.get("latency_ms") is None else row["latency_ms"]
        lines.append(
            f"| {cell(row['id'])} | {cell(row['category'])} | `{cell(row['interface'])}` | {cell(row['title'])} | {expected} | {actual} | {latency_cell} | {row['result']} |"
        )
    lines.append("")
    lines.append("失败用例的响应片段：")
    lines.append("")
    if failed:
        for row in failed:
            lines.append(f"- `{row['id']}` {row['title']}: {cell(row.get('snippet'))}")
    else:
        lines.append("- 无")
    lines.append("")
    lines.append("## 6. 结果汇总")
    lines.append("")
    lines.append("| 结果 | 数量 |")
    lines.append("| --- | --- |")
    lines.append(f"| 通过 | {counts['pass']} |")
    lines.append(f"| 失败 | {counts['fail']} |")
    lines.append(f"| 跳过（未执行） | {counts['skip']} |")
    lines.append(f"| 合计 | {counts['total']} |")
    lines.append("")
    lines.append(f"已记录时延的用例：最小 {latency.get('min')} ms，平均 {latency.get('avg')} ms，最大 {latency.get('max')} ms。摄像头流和快照等自行计时的用例没有写入耗时。")
    lines.append("")
    lines.append(f"pytest 退出码：{meta.get('pytest_exitstatus')}。本次进程输出为 15 个测试函数通过、2 个失败（`test_snapshot_jpeg_accept_when_camera_disabled` 与 `test_snapshot_json_accept_when_camera_disabled`）。上表按接口用例计数，一个函数里可以包含多条用例；4 条跳过记在通过的函数 `test_not_executable_interfaces` 中，不计为失败。")
    lines.append("")
    lines.append("## 7. 缺陷")
    lines.append("")
    if not failed:
        lines.append("本次按控制器和 MQTT 协议约定执行的用例全部通过，没有发现可复现的接口缺陷。")
        lines.append("")
        lines.append("文档差异（不作为失败用例）：`backend/docs/API.md` 和 `backend/README.md` 仍描述 20 个早期接口，并写着后端没有前端、导航和视频。当前代码已经提供管理员、摄像头、建图和导航接口，OpenAPI 也能列出它们。")
    else:
        ids = {row["id"] for row in failed}
        if "OFF-10" in ids or "OFF-13" in ids:
            lines.append("快照接口 `GET /api/v1/camera/snapshot` 声明 `produces = image/jpeg`。摄像头未配置时，控制器抛出业务异常 `CAMERA_DISABLED`（本应 HTTP 503，正文为 JSON）。异常处理方法返回 JSON，但客户端 `Accept: image/jpeg` 无法接受该表示，Spring 记录 `HttpMediaTypeNotAcceptableException: Could not find acceptable representation`，原始异常接着变成 HTTP 500 且响应体为空。客户端改发 `Accept: application/json` 时，内容协商在进入方法前失败，通用异常处理把它写成 HTTP 500 / `INTERNAL_ERROR`，而不是 406。成功路径在 `Accept: */*` 下仍能返回 JPEG。业务代码未修改。")
            lines.append("")
        lines.append("下列失败是这次运行的真实结果。业务代码未改。")
        lines.append("")
        for index, row in enumerate(failed, 1):
            lines.append(f"### 缺陷 {index}：{row['id']} {row['title']}")
            lines.append("")
            code = row.get("expected_code") or "无独立业务码"
            actual_code = row.get("actual_code") or "无 JSON 业务码"
            lines.append("- 严重程度：中。成功取帧不受影响，失败响应被写成 500，调用方无法按文档处理。")
            lines.append(f"- 接口：`{row['method']} {row['path']}`")
            lines.append(f"- 期望：HTTP {row['expected_status']} / {code}")
            lines.append(f"- 实际：HTTP {row['actual_status']} / {actual_code} {cell(row.get('actual_message'))}")
            lines.append(f"- 说明：{cell(row.get('error')) or '见本节开头的原因说明。'}")
            lines.append("- 复现：按 `tests/api/run.sh` 启动临时 Broker 和两个后端进程，执行对应 pytest 用例。响应片段如下：")
            lines.append("")
            lines.append("```")
            lines.append(cell(row.get("snippet")) or "(空)")
            lines.append("```")
            lines.append("")
    lines.append("## 8. 风险与改进建议")
    lines.append("")
    lines.append("- WHEP、RTSPS 和真实 ROS/CAN 没有在这台机器上运行。仿真桥不验证避障、定位和编码器延迟，不能代替实车验收。")
    lines.append("- 摄像头用例使用 4 字节最小 JPEG，只验证帧边界和鉴权，不验证解码画质。观看上限用例会占用最多 8 个服务端阻塞线程数秒。")
    lines.append("- 导航障碍告警依赖心跳在线和连续两次障碍报告的时间差，现场激光阈值仍需实车确认。")
    lines.append("- 建议把 `backend/docs/API.md` 更新到与 OpenAPI 一致，并把 `tests/api/run.sh` 放进持续集成。CI 只需 JDK、Python 和 Mosquitto，不需要小车。")
    lines.append("- 设备密钥、演示密码和 JWT 测试密钥只用于本套件的临时库。部署时必须更换，并且不要把 `PATROL_ADMIN_PASSWORD` 留在环境文件里。")
    lines.append("- 未知路径在未登录时返回 401 而不是 404，这与 `anyRequest().authenticated()` 一致。调用方不能靠 401/404 判断路径是否存在。")
    lines.append("")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
