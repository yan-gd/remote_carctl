"""认证接口：注册、登录、当前用户、凭证边界。"""

import time
import uuid

import jwt

from harness import BASE, DEMO_PASSWORD, api, jwt_key


def test_register_login_and_me(tokens):
    name = "viewer_" + uuid.uuid4().hex[:8]
    body = api(
        "AUTH-01",
        "合法注册固定为 VIEWER",
        "POST",
        "/api/v1/auth/register",
        category="正常",
        expected_status=201,
        json_body={"username": name, "password": "ExamplePass123!"},
        check=lambda _r, data: (
            _assert(data["data"]["role"] == "VIEWER" and data["data"]["username"] == name and data["data"]["active"] is True)
        ),
    )[1]
    user_id = body["data"]["id"]
    api(
        "AUTH-02",
        "新用户登录返回 Bearer JWT",
        "POST",
        "/api/v1/auth/login",
        category="正常",
        expected_status=200,
        json_body={"username": name, "password": "ExamplePass123!"},
        check=lambda _r, data: _assert(
            data["data"]["tokenType"] == "Bearer"
            and data["data"]["expiresIn"] == 3600
            and data["data"]["user"]["id"] == user_id
            and "password" not in data["data"]["user"]
        ),
    )
    token = api(
        "AUTH-02b",
        "用户名大小写不敏感登录",
        "POST",
        "/api/v1/auth/login",
        category="边界",
        expected_status=200,
        json_body={"username": name.upper(), "password": "ExamplePass123!"},
    )[1]["data"]["token"]
    api(
        "AUTH-03",
        "携带 Token 查询当前用户",
        "GET",
        "/api/v1/auth/me",
        category="正常",
        expected_status=200,
        token=token,
        check=lambda _r, data: _assert(data["data"]["username"] == name and data["data"]["role"] == "VIEWER"),
    )
    api(
        "AUTH-04",
        "未登录访问 /auth/me",
        "GET",
        "/api/v1/auth/me",
        category="权限",
        expected_status=401,
        expected_code="UNAUTHORIZED",
    )


def test_register_boundaries(tokens):
    api(
        "AUTH-05",
        "注册时提交 role 字段被拒绝",
        "POST",
        "/api/v1/auth/register",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        json_body={"username": "role_user_x", "password": "ExamplePass123!", "role": "OPERATOR"},
    )
    api(
        "AUTH-06",
        "用户名过短",
        "POST",
        "/api/v1/auth/register",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        json_body={"username": "ab", "password": "ExamplePass123!"},
    )
    api(
        "AUTH-07",
        "密码含空格",
        "POST",
        "/api/v1/auth/register",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        json_body={"username": "space_user", "password": "bad pass!"},
    )
    api(
        "AUTH-08",
        "密码短于 8 位",
        "POST",
        "/api/v1/auth/register",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        json_body={"username": "short_pw", "password": "short1A"},
    )
    api(
        "AUTH-09",
        "空 JSON 对象",
        "POST",
        "/api/v1/auth/register",
        category="异常",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        json_body={},
    )
    api(
        "AUTH-10",
        "非法 JSON",
        "POST",
        "/api/v1/auth/register",
        category="异常",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        data=b"{",
        headers={"Content-Type": "application/json"},
    )
    api(
        "AUTH-11",
        "非 JSON 媒体类型",
        "POST",
        "/api/v1/auth/login",
        category="异常",
        expected_status=415,
        expected_code="UNSUPPORTED_MEDIA_TYPE",
        data=b"username=a",
        headers={"Content-Type": "text/plain"},
    )
    api(
        "AUTH-12",
        "已登录时用 GET 调用登录接口",
        "GET",
        "/api/v1/auth/login",
        category="异常",
        expected_status=405,
        expected_code="METHOD_NOT_ALLOWED",
        token=tokens["demo_viewer"],
    )
    api(
        "AUTH-12b",
        "未登录时错误方法先被认证拦截",
        "GET",
        "/api/v1/auth/login",
        category="权限",
        expected_status=401,
        expected_code="UNAUTHORIZED",
    )


def test_login_failures_do_not_enumerate(tokens):
    missing = api(
        "AUTH-13",
        "不存在的用户登录",
        "POST",
        "/api/v1/auth/login",
        category="权限",
        expected_status=401,
        expected_code="LOGIN_FAILED",
        json_body={"username": "no_such_user_zz", "password": "ExamplePass123!"},
    )[1]
    wrong = api(
        "AUTH-14",
        "演示账号密码错误",
        "POST",
        "/api/v1/auth/login",
        category="权限",
        expected_status=401,
        expected_code="LOGIN_FAILED",
        json_body={"username": "demo_operator", "password": "WrongPass123!"},
    )[1]
    assert missing["message"] == wrong["message"]
    api(
        "AUTH-15",
        "重复用户名（大小写不同）",
        "POST",
        "/api/v1/auth/register",
        category="边界",
        expected_status=409,
        expected_code="BUSINESS_CONFLICT",
        json_body={"username": "Demo_Operator", "password": "ExamplePass123!"},
    )


def test_token_errors(tokens):
    api(
        "AUTH-16",
        "畸形 Bearer",
        "GET",
        "/api/v1/auth/me",
        category="权限",
        expected_status=401,
        expected_code="UNAUTHORIZED",
        headers={"Authorization": "Bearer not-a-jwt"},
    )
    api(
        "AUTH-17",
        "错误的认证方案",
        "GET",
        "/api/v1/auth/me",
        category="权限",
        expected_status=401,
        expected_code="UNAUTHORIZED",
        headers={"Authorization": "Token abc"},
    )
    now = int(time.time())
    expired = jwt.encode(
        {"iss": "patrol-management", "sub": str(tokens["demo_viewer_id"]), "iat": now - 20, "exp": now - 5},
        jwt_key(),
        algorithm="HS256",
    )
    api(
        "AUTH-18",
        "过期 JWT",
        "GET",
        "/api/v1/auth/me",
        category="权限",
        expected_status=401,
        expected_code="TOKEN_EXPIRED",
        token=expired,
    )
    ghost = jwt.encode(
        {"iss": "patrol-management", "sub": "999999", "iat": now - 5, "exp": now + 600},
        jwt_key(),
        algorithm="HS256",
    )
    api(
        "AUTH-19",
        "签名有效但用户不存在",
        "GET",
        "/api/v1/auth/me",
        category="权限",
        expected_status=401,
        expected_code="UNAUTHORIZED",
        token=ghost,
        message_contains="不存在",
    )
    api(
        "AUTH-20",
        "演示操作员可访问 /auth/me",
        "GET",
        "/api/v1/auth/me",
        category="正常",
        expected_status=200,
        token=tokens["demo_operator"],
        check=lambda _r, data: _assert(data["data"]["role"] == "OPERATOR" and "MOVE" in data["data"]["permissions"]),
    )
    assert BASE.startswith("http")


def _assert(condition: bool, message: str = "响应字段与约定不符") -> None:
    if not condition:
        raise AssertionError(message)
