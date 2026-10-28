"""管理员账号接口。"""

import uuid

from harness import ADMIN_PASSWORD, BASE_NO_MQTT, DEMO_PASSWORD, api, login


def test_admin_user_lifecycle(tokens):
    admin, operator = tokens["admin"], tokens["demo_operator"]
    api(
        "ADMIN-01",
        "非管理员不能列出用户",
        "GET",
        "/api/v1/admin/users",
        category="权限",
        expected_status=403,
        expected_code="FORBIDDEN",
        token=operator,
    )
    api(
        "ADMIN-02",
        "未登录访问用户管理",
        "GET",
        "/api/v1/admin/users",
        category="权限",
        expected_status=401,
        expected_code="UNAUTHORIZED",
    )
    listed = api(
        "ADMIN-03",
        "管理员查询全部账号",
        "GET",
        "/api/v1/admin/users",
        category="正常",
        expected_status=200,
        token=admin,
        check=lambda _r, data: _ok(
            any(user["username"] == "admin" for user in data["data"])
            and all("password" not in user for user in data["data"])
            and DEMO_PASSWORD not in str(data)
        ),
    )[1]["data"]
    admin_id = next(user["id"] for user in listed if user["username"] == "admin")
    name = "api_user_" + uuid.uuid4().hex[:8]
    created = api(
        "ADMIN-04",
        "管理员创建监控员",
        "POST",
        "/api/v1/admin/users",
        category="正常",
        expected_status=201,
        token=admin,
        json_body={"username": name, "password": "MonitorPass1!", "role": "MONITOR"},
        check=lambda _r, data: _ok(data["data"]["role"] == "MONITOR" and "ALARM_ACK" in data["data"]["permissions"]),
    )[1]["data"]
    uid = created["id"]
    fresh = login(name, "MonitorPass1!")
    api(
        "ADMIN-05",
        "修改角色为查看者并重设密码",
        "PUT",
        f"/api/v1/admin/users/{uid}",
        category="正常",
        expected_status=200,
        token=admin,
        json_body={"role": "VIEWER", "password": "ViewerPass1!", "permissions": ["ALL_LOGS"]},
        check=lambda _r, data: _ok(data["data"]["role"] == "VIEWER" and data["data"]["permissions"] == ["ALL_LOGS"]),
    )
    login(name, "ViewerPass1!")
    api(
        "ADMIN-06",
        "驾驶权限必须同时授予停止",
        "PUT",
        f"/api/v1/admin/users/{uid}",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        token=admin,
        json_body={"role": "OPERATOR", "permissions": ["MOVE"]},
    )
    api(
        "ADMIN-07",
        "未知权限",
        "POST",
        "/api/v1/admin/users",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        token=admin,
        json_body={"username": "bad_perm_user", "password": "ExamplePass123!", "role": "VIEWER", "permissions": ["ROOT"]},
    )
    api(
        "ADMIN-08",
        "不能创建 ADMIN 角色",
        "POST",
        "/api/v1/admin/users",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        token=admin,
        json_body={"username": "second_admin", "password": "ExamplePass123!", "role": "ADMIN"},
    )
    api(
        "ADMIN-09",
        "不能修改管理员自身",
        "PUT",
        f"/api/v1/admin/users/{admin_id}",
        category="权限",
        expected_status=403,
        expected_code="ADMIN_PROTECTED",
        token=admin,
        json_body={"role": "VIEWER", "permissions": []},
    )
    api(
        "ADMIN-10",
        "不能停用管理员",
        "DELETE",
        f"/api/v1/admin/users/{admin_id}",
        category="权限",
        expected_status=403,
        expected_code="ADMIN_PROTECTED",
        token=admin,
    )
    api(
        "ADMIN-11",
        "停用普通账号",
        "DELETE",
        f"/api/v1/admin/users/{uid}",
        category="正常",
        expected_status=200,
        token=admin,
    )
    api(
        "ADMIN-12",
        "停用后原 Token 与密码立即失效",
        "GET",
        "/api/v1/auth/me",
        category="权限",
        expected_status=401,
        expected_code="UNAUTHORIZED",
        token=fresh["token"],
    )
    api(
        "ADMIN-13",
        "停用账号不能登录",
        "POST",
        "/api/v1/auth/login",
        category="权限",
        expected_status=401,
        expected_code="LOGIN_FAILED",
        json_body={"username": name, "password": "ViewerPass1!"},
    )
    api(
        "ADMIN-14",
        "同名创建会恢复已停用账号",
        "POST",
        "/api/v1/admin/users",
        category="正常",
        expected_status=201,
        token=admin,
        json_body={"username": name, "password": "RestoredPass1!", "role": "OPERATOR"},
        check=lambda _r, data: _ok(data["data"]["id"] == uid and data["data"]["active"] is True and "MOVE" in data["data"]["permissions"]),
    )
    login(name, "RestoredPass1!")
    api(
        "ADMIN-15",
        "活跃用户名冲突",
        "POST",
        "/api/v1/admin/users",
        category="异常",
        expected_status=409,
        expected_code="BUSINESS_CONFLICT",
        token=admin,
        json_body={"username": name, "password": "ExamplePass123!", "role": "VIEWER"},
    )
    api(
        "ADMIN-16",
        "用户不存在",
        "PUT",
        "/api/v1/admin/users/999999",
        category="异常",
        expected_status=404,
        expected_code="NOT_FOUND",
        token=admin,
        json_body={"role": "VIEWER"},
    )
    assert ADMIN_PASSWORD and BASE_NO_MQTT


def _ok(condition: bool) -> None:
    if not condition:
        raise AssertionError("响应字段与约定不符")
