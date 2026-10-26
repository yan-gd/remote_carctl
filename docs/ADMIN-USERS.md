# 管理员账号、用户权限与紧凑控制台

## 范围

- `admin` 是唯一的引导系统管理员，拥有所有业务操作权限及「用户管理」权限。数据库仍保留原四种业务角色 `OPERATOR`、`MONITOR`、`MAINTAINER`、`VIEWER`；管理员标记独立存储，不重建旧表。
- 管理员可以创建、停用、恢复、修改普通业务账号的角色和逐项权限，也可以选择性重设其密码；不能通过管理接口改动/停用管理员自身。
- 细分权限：`MOVE` 驾驶、`STOP` 紧急停止与取消、`MAP_EDIT` 建图定位、`NAVIGATE` 启动导航、`ALARM_ACK` 确认告警、`ALL_LOGS` 全员日志、`PATROL_EDIT` 原路线任务维护。驾驶/建图/导航授予时必须同时具有 `STOP`。所有写操作由后端校验，隐藏 UI 不等于授权。
- 停用账号后其现有 JWT 立即失效；保留 `sys_user` 行以维护 `patrol_task`、`alarm_record`、`control_log` 外键与历史记录。用相同用户名再次创建会显式恢复该账号并重置密码。
- 默认四角色能力维持旧版规则；只有管理员明确设置自定义权限后才覆盖。改动立刻影响后续 API 请求，前端每 15 秒同步当前登录用户的最新授权。
- 告警/日志提供服务端关键字搜索（参数 `q`，最多 80 字符），与原级别/状态/类型/时间范围、分页组合，搜索不是仅查当前页。

## 首次创建 admin

**没有内置默认密码。** 在服务器完成代码构建后，先确认 `/etc/remote-carctl/backend.env` 已存在且权限为 `600`。通过以下方式编辑现有配置，而不是覆盖数据库或环境文件：

```bash
sudoedit /etc/remote-carctl/backend.env
```

新增一行，替换为自己设置的 8–72 位非空格 ASCII 密码：

```ini
PATROL_ADMIN_PASSWORD=替换为独立强密码
```

然后 `sudo systemctl restart remote-carctl`。首次启动创建 `admin`，密码采用现有 BCrypt，不在 API 或日志中返回。确认使用 `admin` 成功登录后，从上述环境文件删除 `PATROL_ADMIN_PASSWORD` 行并再次重启；已有管理员不会被删除，后续重启不会重置密码。**不要把真实密码提交到 GitHub，也不要在聊天或命令行参数中展示。**

如果数据库里原来已经存在普通用户 `admin`，启动会拒绝自动提权；应先人工核对这个账号的来源，不能直接修改数据库绕过检查。

`schema.sql` 只新增 `sys_user_access` 表；现有用户、SQLite 数据和外键均保留。首次部署前建议自行备份 SQLite 数据库和 JWT 密钥文件。恢复旧应用版本时，这张新增表可以保留，不影响旧应用读取原用户表。

## 服务器更新示例（现有宝塔站点）

先确认小车处于静止、没有正在执行的导航任务，并按现场实际仓库路径调整以下命令：

```bash
cd /opt/remote_carctl
git pull --ff-only origin main

cd backend
mvn clean install
sudo systemctl restart remote-carctl
sudo systemctl status remote-carctl --no-pager

cd ../frontend
npm ci
npm run typecheck
npm test
npm run build
sudo rsync -a dist/ /www/wwwroot/agilex.yoyoyan.cn/
```

实际 nginx 根目录应先用已生效的 vhost 配置核实；**不要覆盖宝塔 vhost、SSL 证书、媒体转发或后端环境文件。** 静态文件部署通常无需重启 nginx。首次创建管理员时，在重启后端之前按上节添加 `PATROL_ADMIN_PASSWORD`。

## API

仅管理员 JWT 可调用：

- `GET /api/v1/admin/users`：返回全部用户（含已停用）的角色、权限、状态。
- `POST /api/v1/admin/users`：创建/恢复普通用户，需 `username`、`password`、`role`，`permissions` 可选。
- `PUT /api/v1/admin/users/{id}`：修改角色和权限；`password` 可空，空时不重设。
- `DELETE /api/v1/admin/users/{id}`：软停用普通账号。

公开注册仍固定新建 VIEWER，不能传入自定义角色、权限或管理员标记。管理员专属 API 由 Spring Security 和应用层双重校验。登录与 JWT 原有行为保持不变。
