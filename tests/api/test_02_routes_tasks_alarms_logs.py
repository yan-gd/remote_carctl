"""路线、任务、告警、操作日志。"""

import uuid

from harness import api, route_body


def test_route_and_task_crud(tokens):
    op, viewer, monitor = tokens["demo_operator"], tokens["demo_viewer"], tokens["demo_monitor"]
    created = api(
        "ROUTE-01",
        "操作员创建路线",
        "POST",
        "/api/v1/routes",
        category="正常",
        expected_status=201,
        token=op,
        json_body=route_body("路线_" + uuid.uuid4().hex[:6]),
        check=lambda _r, data: _ok(data["data"]["points"][0]["sequence"] == 0 and data["data"]["points"][1]["yaw"] is None),
    )[1]["data"]
    rid = created["id"]
    api(
        "ROUTE-02",
        "查看者分页查询路线",
        "GET",
        "/api/v1/routes",
        category="正常",
        expected_status=200,
        token=viewer,
        params={"page": 1, "size": 20},
        check=lambda _r, data: _ok(data["data"]["total"] >= 1 and any(item["id"] == rid for item in data["data"]["items"])),
    )
    api(
        "ROUTE-03",
        "路线详情",
        "GET",
        f"/api/v1/routes/{rid}",
        category="正常",
        expected_status=200,
        token=monitor,
        check=lambda _r, data: _ok(data["data"]["id"] == rid),
    )
    api(
        "ROUTE-04",
        "操作员完整更新路线",
        "PUT",
        f"/api/v1/routes/{rid}",
        category="正常",
        expected_status=200,
        token=op,
        json_body=route_body(created["name"] + "_改", yaw=3.141592653589793),
        check=lambda _r, data: _ok(abs(data["data"]["points"][0]["yaw"] - 3.141592653589793) < 1e-9),
    )
    api(
        "ROUTE-05",
        "查看者创建路线被拒绝",
        "POST",
        "/api/v1/routes",
        category="权限",
        expected_status=403,
        expected_code="FORBIDDEN",
        token=viewer,
        json_body=route_body(),
    )
    api(
        "ROUTE-06",
        "监控员修改路线被拒绝",
        "PUT",
        f"/api/v1/routes/{rid}",
        category="权限",
        expected_status=403,
        expected_code="FORBIDDEN",
        token=monitor,
        json_body=route_body(),
    )
    api(
        "ROUTE-07",
        "未登录查询路线",
        "GET",
        "/api/v1/routes",
        category="权限",
        expected_status=401,
        expected_code="UNAUTHORIZED",
    )
    api(
        "ROUTE-08",
        "点位为空",
        "POST",
        "/api/v1/routes",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        token=op,
        json_body={"name": "空点", "points": []},
    )
    api(
        "ROUTE-09",
        "点位序号重复",
        "POST",
        "/api/v1/routes",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        token=op,
        json_body={"name": "重复序号", "points": [{"sequence": 1, "x": 0, "y": 0}, {"sequence": 1, "x": 1, "y": 1}]},
    )
    api(
        "ROUTE-10",
        "yaw 超出 ±π",
        "POST",
        "/api/v1/routes",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        token=op,
        json_body={"name": "yaw越界", "points": [{"sequence": 0, "x": 0, "y": 0, "yaw": 3.15}]},
    )
    api(
        "ROUTE-11",
        "未知 JSON 字段",
        "POST",
        "/api/v1/routes",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        token=op,
        json_body={"name": "多余字段", "points": [{"sequence": 0, "x": 0, "y": 0}], "creatorId": 1},
    )
    api(
        "ROUTE-12",
        "路线不存在",
        "GET",
        "/api/v1/routes/999999",
        category="异常",
        expected_status=404,
        expected_code="NOT_FOUND",
        token=viewer,
    )
    api(
        "ROUTE-13",
        "路线 ID 非正整数",
        "GET",
        "/api/v1/routes/0",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        token=viewer,
    )
    api(
        "ROUTE-14",
        "分页 size 超上限",
        "GET",
        "/api/v1/routes",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        token=viewer,
        params={"page": 1, "size": 101},
    )
    api(
        "ROUTE-15",
        "分页 page 为 0",
        "GET",
        "/api/v1/routes",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        token=viewer,
        params={"page": 0, "size": 10},
    )

    task = api(
        "TASK-01",
        "创建草稿任务",
        "POST",
        "/api/v1/tasks",
        category="正常",
        expected_status=201,
        token=op,
        json_body={"name": "早班计划", "routeId": rid, "plannedAt": None, "status": "DRAFT"},
        check=lambda _r, data: _ok(data["data"]["status"] == "DRAFT" and data["data"]["routeId"] == rid),
    )[1]["data"]
    tid = task["id"]
    api(
        "TASK-02",
        "按状态筛选任务",
        "GET",
        "/api/v1/tasks",
        category="正常",
        expected_status=200,
        token=viewer,
        params={"status": "DRAFT", "page": 1, "size": 50},
        check=lambda _r, data: _ok(any(item["id"] == tid for item in data["data"]["items"])),
    )
    api(
        "TASK-03",
        "更新为已计划",
        "PUT",
        f"/api/v1/tasks/{tid}",
        category="正常",
        expected_status=200,
        token=op,
        json_body={"name": "早班计划", "routeId": rid, "plannedAt": "2026-10-20T00:00:00Z", "status": "PLANNED"},
        check=lambda _r, data: _ok(data["data"]["status"] == "PLANNED" and data["data"]["plannedAt"].startswith("2026-10-20")),
    )
    api(
        "TASK-04",
        "新建任务不能直接取消",
        "POST",
        "/api/v1/tasks",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        token=op,
        json_body={"name": "直接取消", "routeId": rid, "status": "CANCELLED"},
    )
    api(
        "TASK-05",
        "已计划任务缺少时间",
        "POST",
        "/api/v1/tasks",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        token=op,
        json_body={"name": "无时间", "routeId": rid, "status": "PLANNED"},
    )
    api(
        "TASK-06",
        "引用不存在的路线",
        "POST",
        "/api/v1/tasks",
        category="异常",
        expected_status=404,
        expected_code="NOT_FOUND",
        token=op,
        json_body={"name": "无线路", "routeId": 999999, "status": "DRAFT"},
    )
    api(
        "TASK-07",
        "非法任务状态",
        "POST",
        "/api/v1/tasks",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        token=op,
        json_body={"name": "运行中", "routeId": rid, "status": "RUNNING"},
    )
    api(
        "TASK-08",
        "监控员删除任务被拒绝",
        "DELETE",
        f"/api/v1/tasks/{tid}",
        category="权限",
        expected_status=403,
        expected_code="FORBIDDEN",
        token=monitor,
    )
    api(
        "TASK-09",
        "被任务引用的路线不能删除",
        "DELETE",
        f"/api/v1/routes/{rid}",
        category="异常",
        expected_status=409,
        expected_code="BUSINESS_CONFLICT",
        token=op,
    )
    api(
        "TASK-10",
        "取消任务",
        "PUT",
        f"/api/v1/tasks/{tid}",
        category="正常",
        expected_status=200,
        token=op,
        json_body={"name": "早班计划", "routeId": rid, "plannedAt": "2026-10-20T00:00:00Z", "status": "CANCELLED"},
        check=lambda _r, data: _ok(data["data"]["status"] == "CANCELLED"),
    )
    api(
        "TASK-11",
        "已取消任务不能再修改",
        "PUT",
        f"/api/v1/tasks/{tid}",
        category="异常",
        expected_status=409,
        expected_code="BUSINESS_CONFLICT",
        token=op,
        json_body={"name": "再改", "routeId": rid, "status": "DRAFT"},
    )
    api(
        "TASK-12",
        "删除任务后可以删除路线",
        "DELETE",
        f"/api/v1/tasks/{tid}",
        category="正常",
        expected_status=200,
        token=op,
        check=lambda _r, data: _ok(data["data"] is None),
    )
    api(
        "ROUTE-16",
        "删除路线",
        "DELETE",
        f"/api/v1/routes/{rid}",
        category="正常",
        expected_status=200,
        token=op,
    )
    api(
        "ROUTE-17",
        "删除后详情不存在",
        "GET",
        f"/api/v1/routes/{rid}",
        category="异常",
        expected_status=404,
        expected_code="NOT_FOUND",
        token=viewer,
    )


def test_alarms_and_logs(tokens):
    viewer, monitor, operator, maintainer = (
        tokens["demo_viewer"],
        tokens["demo_monitor"],
        tokens["demo_operator"],
        tokens["demo_maintainer"],
    )
    listed = api(
        "ALARM-01",
        "按来源关键字查询演示告警",
        "GET",
        "/api/v1/alarms",
        category="正常",
        expected_status=200,
        token=viewer,
        params={"q": "DEMO_FIXTURE", "page": 1, "size": 20},
        check=lambda _r, data: _ok(data["data"]["total"] >= 1 and data["data"]["items"][0]["source"] == "DEMO_FIXTURE"),
    )[1]["data"]["items"][0]
    aid = listed["id"]
    api(
        "ALARM-02",
        "告警详情",
        "GET",
        f"/api/v1/alarms/{aid}",
        category="正常",
        expected_status=200,
        token=viewer,
        check=lambda _r, data: _ok(data["data"]["status"] == "UNACKNOWLEDGED"),
    )
    api(
        "ALARM-03",
        "操作员不能确认告警",
        "PATCH",
        f"/api/v1/alarms/{aid}/ack",
        category="权限",
        expected_status=403,
        expected_code="FORBIDDEN",
        token=operator,
    )
    api(
        "ALARM-04",
        "监控员确认告警",
        "PATCH",
        f"/api/v1/alarms/{aid}/ack",
        category="正常",
        expected_status=200,
        token=monitor,
        check=lambda _r, data: _ok(
            data["data"]["status"] == "ACKNOWLEDGED" and data["data"]["acknowledgedBy"] == tokens["demo_monitor_id"]
        ),
    )
    api(
        "ALARM-05",
        "重复确认冲突",
        "PATCH",
        f"/api/v1/alarms/{aid}/ack",
        category="异常",
        expected_status=409,
        expected_code="BUSINESS_CONFLICT",
        token=monitor,
    )
    api(
        "ALARM-06",
        "告警不存在",
        "GET",
        "/api/v1/alarms/999999",
        category="异常",
        expected_status=404,
        expected_code="NOT_FOUND",
        token=viewer,
    )
    api(
        "ALARM-07",
        "非法告警级别",
        "GET",
        "/api/v1/alarms",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        token=viewer,
        params={"level": "FATAL"},
    )
    api(
        "ALARM-08",
        "开始时间晚于结束时间",
        "GET",
        "/api/v1/alarms",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        token=viewer,
        params={"from": "2026-11-01T00:00:00Z", "to": "2026-10-01T00:00:00Z"},
    )
    api(
        "ALARM-09",
        "搜索词超过 80 字符",
        "GET",
        "/api/v1/alarms",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        token=viewer,
        params={"q": "x" * 81},
    )
    api(
        "ALARM-10",
        "未登录查询告警",
        "GET",
        "/api/v1/alarms",
        category="权限",
        expected_status=401,
        expected_code="UNAUTHORIZED",
    )
    api(
        "LOG-01",
        "操作员只能看到本人日志",
        "GET",
        "/api/v1/control-logs",
        category="权限",
        expected_status=200,
        token=operator,
        params={"page": 1, "size": 50, "operation": "ROUTE_CREATE"},
        check=lambda _r, data: _ok(all(item["actorId"] == tokens["demo_operator_id"] for item in data["data"]["items"])),
    )
    api(
        "LOG-02",
        "查看者可以查询全部日志",
        "GET",
        "/api/v1/control-logs",
        category="正常",
        expected_status=200,
        token=viewer,
        params={"page": 1, "size": 20, "operation": "ALARM_ACK"},
        check=lambda _r, data: _ok(data["data"]["total"] >= 1),
    )
    api(
        "LOG-03",
        "运维员按关键字检索日志",
        "GET",
        "/api/v1/control-logs",
        category="正常",
        expected_status=200,
        token=maintainer,
        params={"q": "ALARM_ACK", "page": 1, "size": 10},
        check=lambda _r, data: _ok(data["data"]["total"] >= 1),
    )
    api(
        "LOG-04",
        "监控员看不到操作员的路线日志",
        "GET",
        "/api/v1/control-logs",
        category="权限",
        expected_status=200,
        token=monitor,
        params={"operation": "ROUTE_CREATE", "page": 1, "size": 50},
        check=lambda _r, data: _ok(all(item["actorId"] == tokens["demo_monitor_id"] for item in data["data"]["items"])),
    )
    api(
        "LOG-05",
        "未登录查询日志",
        "GET",
        "/api/v1/control-logs",
        category="权限",
        expected_status=401,
        expected_code="UNAUTHORIZED",
    )


def _ok(condition: bool) -> None:
    if not condition:
        raise AssertionError("响应字段与约定不符")
