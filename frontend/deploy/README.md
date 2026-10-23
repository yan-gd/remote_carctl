# 前端部署：agilex.yoyoyan.cn

把 Flutter Web 构建产物部署到云服务器，由 nginx 同时提供静态文件和 `/api/` 反向代理。本文假设：

| 项 | 值 |
| --- | --- |
| 域名 | `agilex.yoyoyan.cn` |
| 服务器 | `47.109.132.238`，`root` 登录 |
| 后端 | `/opt/remote_carctl/backend`，`remote-carctl.service`，监听 8080 |
| 前端目录 | `/opt/remote_carctl/frontend`（`index.html` 直接位于此目录） |
| nginx 站点 | `/etc/nginx/sites-available/agilex.yoyoyan.cn` |
| 证书 | Let's Encrypt，`/etc/letsencrypt/live/agilex.yoyoyan.cn/` |

> 后端**只监听 127.0.0.1:8080**，公网只通过 nginx 暴露 80/443。这样 8080 不再对公网开放，Swagger 也可在 nginx 层关闭。

> **2026-10-23 实测状态**：`agilex.yoyoyan.cn` 已解析到 `47.109.132.238`；`http://agilex.yoyoyan.cn/` 返回 **主机面板默认的“没有找到站点”页**（nginx，且已带 HSTS），`/api/*` 为 404，`https://` 尚无可用证书。部署前先确认 nginx 由谁管理：
>
> - 若是系统 nginx（`/etc/nginx`），按本文第 2–5 节执行；
> - 若是**宝塔/BT 面板或主机控制面板**，不要直接覆盖系统配置，按[附录 A](#附录-a宝塔面板部署)在面板里添加站点；
> - SSH 目前不接受本机公钥（`Permission denied (publickey,password)`），需要用户提供密码/密钥或由用户执行命令。

## 1. DNS 与网络

2026-10-23 已确认 A 记录存在，可跳过记录添加，直接验证：

```bash
dig +short agilex.yoyoyan.cn
```

云服务器安全组放行 **TCP 80、443**（`1883` 保留给小车 MQTT，不要删除）。服务器上 UFW 若启用：

```bash
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw status
```

在 nginx 验证通过后再关闭公网 8080：

```bash
sudo ufw delete allow 8080/tcp    # 若之前为 Swagger 放行过
```

## 2. 后端改为只监听本机

编辑 `/etc/remote-carctl/backend.env`：

```bash
sudo sed -i 's/^BIND_ADDRESS=.*/BIND_ADDRESS=127.0.0.1/' /etc/remote-carctl/backend.env
```

确认生产 CORS 来源。**必须显式包含公网 HTTPS 源**，否则浏览器登录会被 Spring 的 CORS 过滤器以 403 拒绝（Spring 只看到 nginx 代理后的 `http://127.0.0.1:8080`，不会自动认为同源）：

```bash
sudo grep -n '^PATROL_WEB_ALLOWED_ORIGINS=' /etc/remote-carctl/backend.env || true
# 删除旧行后追加唯一一条（避免出现重复配置）：
sudo sed -i '/^PATROL_WEB_ALLOWED_ORIGINS=/d' /etc/remote-carctl/backend.env
printf 'PATROL_WEB_ALLOWED_ORIGINS=https://agilex.yoyoyan.cn\n' | sudo tee -a /etc/remote-carctl/backend.env
```

开发来源（`http://127.0.0.1:5173`）只在 `flutter run` 时加入，不要写进生产配置。

```bash
sudo systemctl restart remote-carctl
sudo systemctl status remote-carctl --no-pager
ss -ltnp 'sport = :8080'          # 期望 127.0.0.1:8080，而不是 *:8080
curl --noproxy '*' -sS -i http://127.0.0.1:8080/api/v1/robot/status | head
```

最后一条应返回 **401 JSON**（未带 JWT）；返回 HTML 说明请求没打到后端。

## 3. 安装 nginx 与上传构建产物

服务器：

```bash
sudo apt update
sudo apt install -y nginx certbot
sudo systemctl enable --now nginx
sudo install -d -m 755 /opt/remote_carctl/frontend
sudo install -d -m 755 /var/www/certbot
sudo install -d -m 755 /etc/nginx/snippets
```

本地 Windows（在 `exp3/frontend` 构建后执行）：

```powershell
flutter build web --release --no-web-resources-cdn --no-wasm-dry-run

scp -r .\build\web\* root@47.109.132.238:/tmp/agilex-web/
scp .\deploy\security-headers.conf root@47.109.132.238:/tmp/agilex-security-headers.conf
scp .\deploy\nginx-http.conf root@47.109.132.238:/tmp/agilex-http.conf
```

服务器：

```bash
sudo rsync -a --delete /tmp/agilex-web/ /opt/remote_carctl/frontend/
sudo install -m 644 /tmp/agilex-security-headers.conf /etc/nginx/snippets/agilex-security-headers.conf
sudo install -m 644 /tmp/agilex-http.conf /etc/nginx/sites-available/agilex.yoyoyan.cn
sudo ln -sfn /etc/nginx/sites-available/agilex.yoyoyan.cn /etc/nginx/sites-enabled/agilex.yoyoyan.cn
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl reload nginx
curl -sS -I http://agilex.yoyoyan.cn/
```

预期 `HTTP/1.1 200`，并能看到 `text/html`。若 403/404，检查 `/opt/remote_carctl/frontend/index.html` 是否存在、nginx `root` 是否一致。

## 4. 申请证书并切换 HTTPS

```bash
sudo certbot certonly --webroot -w /var/www/certbot \
  -d agilex.yoyoyan.cn \
  --agree-tos -m 你的邮箱@example.com --no-eff-email
```

成功后确认证书文件：

```bash
sudo ls -l /etc/letsencrypt/live/agilex.yoyoyan.cn/
```

上传并启用正式配置：

```powershell
scp .\deploy\nginx-tls.conf root@47.109.132.238:/tmp/agilex-tls.conf
```

```bash
sudo install -m 644 /tmp/agilex-tls.conf /etc/nginx/sites-available/agilex.yoyoyan.cn
sudo nginx -t
sudo systemctl reload nginx
```

续期由系统 `certbot.timer` 负责，验证：

```bash
systemctl list-timers | grep certbot
sudo certbot renew --dry-run
```

## 5. 验收

```bash
# 1) HTTP 跳转到 HTTPS
curl -sS -I http://agilex.yoyoyan.cn/ | grep -Ei 'HTTP/|location'

# 2) HTTPS 与安全头
curl -sS -D - -o /dev/null https://agilex.yoyoyan.cn/ \
  | grep -Ei 'HTTP/|strict-transport|content-security|x-content-type|x-frame|referrer'

# 3) API 反向代理：未登录应返回 JSON 401，而不是 HTML
curl -sS -i https://agilex.yoyoyan.cn/api/v1/robot/status | head -20

# 4) 证书链
openssl s_client -connect agilex.yoyoyan.cn:443 -servername agilex.yoyoyan.cn </dev/null 2>/dev/null \
  | openssl x509 -noout -subject -issuer -dates
```

浏览器打开 <https://agilex.yoyoyan.cn/>：

1. 用后端账号（如 `demo_operator`）登录；
2. 总览应显示 `在线`、`MQTT 已连接`、真实电压（实车时）；
3. 控制页按住前进，观察 `/api/v1/robot/control` 每 200 ms 一条；
4. 松开后后端状态在 1 秒内回到停止，日志页出现 `机器人控制`。

Swagger 默认被 nginx 屏蔽。需要时通过 SSH 隧道访问：

```bash
ssh -L 8080:127.0.0.1:8080 root@47.109.132.238
# 本机打开 http://127.0.0.1:8080/swagger-ui.html
```

## 6. 更新与回滚

更新前端（本地构建后）：

```powershell
flutter build web --release --no-web-resources-cdn --no-wasm-dry-run
scp -r .\build\web root@47.109.132.238:/tmp/agilex-web-new
```

```bash
sudo cp -a /opt/remote_carctl/frontend /opt/remote_carctl/frontend.bak-$(date +%F-%H%M)
sudo rsync -a --delete /tmp/agilex-web-new/ /opt/remote_carctl/frontend/
```

`index.html` 与 `main.dart.js` 配置为不缓存，刷新浏览器即可加载新版本，无需重启 nginx。

回滚：

```bash
sudo rsync -a --delete /opt/remote_carctl/frontend.bak-YYYY-MM-DD-HHMM/ /opt/remote_carctl/frontend/
```

后端更新仍按原流程：`mvn clean install` 后 `sudo systemctl restart remote-carctl`。前端发版不要求重启后端。

## 7. 安全检查清单

- [ ] 公网只开 80/443；8080 不再对外（后端 `BIND_ADDRESS=127.0.0.1`）。
- [ ] `PATROL_WEB_ALLOWED_ORIGINS` 显式包含 `https://agilex.yoyoyan.cn`；没有其他无关来源。
- [ ] 后端已启用 `server.forward-headers-strategy=framework`，nginx 透传 `Host`、`X-Forwarded-Proto`、`X-Forwarded-Host`。
- [ ] 证书有效，HTTP 301 到 HTTPS，HSTS/CSP 响应头存在。
- [ ] nginx 生产配置默认返回 404 给 `/swagger-ui`、`/v3/api-docs`。
- [ ] 登录接口限流生效：连续错误登录会得到 429。
- [ ] 演示账号使用强密码；不要把密码写进仓库、文档或聊天记录。
- [ ] 构建产物不含 source map：`build/web` 下无 `.map` 文件。
- [ ] 浏览器 DevTools 中没有对 `fonts.gstatic.com` 等外部域名的请求（字体已内置）。
- [ ] UFW 与云安全组一致；`1883` 只允许小车来源或改用 MQTT TLS（后续项）。
- [ ] 定期备份 `/opt/remote_carctl/backend/data/exp2.db` 与 `exp2.db.jwt-key`。
- [ ] `journalctl -u remote-carctl` 不包含密码、JWT 或请求体。

## 8. 常见问题

| 现象 | 排查 |
| --- | --- |
| 502 Bad Gateway | 后端未启动、端口不是 18080/8080，或 `BIND_ADDRESS` 不是 127.0.0.1；`ss -ltnp`、`journalctl -u remote-carctl -n 50` |
| 页面空白 / 白屏 | 浏览器控制台看 CSP；确认 `deploy/security-headers.conf` 与构建匹配；确认 `canvaskit/` 已上传 |
| 刷新子路径 404 | `try_files $uri $uri/ /index.html;` 是否保留 |
| 登录 429 | nginx 登录限流；等待一分钟或调整 `agilex_login` 速率 |
| 登录 401 `LOGIN_FAILED` | 用的是后端账号，不是 MQTT `robot`；密码区分大小写 |
| 控制 503 | 后端到 Broker 的 MQTT 连接；与前端无关 |
| 控制 409 | 机器人心跳超时；检查小车桥接和 `robot/.../heartbeat` |
| CORS 报错 | 生产构建不要传 `API_BASE_URL`，保持同源 |
| 中文显示方框 | 字体子集缺少该字；见 `frontend/README.md` 第 6 节 |
| 证书续期失败 | 80 端口或 `/.well-known/acme-challenge/` 被拦截；`sudo certbot renew --dry-run -v` |

## 附录 A：宝塔面板部署

如果 80 端口由宝塔/BT 面板管理（当前默认页即为该面板的“没有找到站点”），不要去改 `/etc/nginx/sites-available`，在面板中操作：

1. **添加站点**：网站 → 添加站点，域名 `agilex.yoyoyan.cn`，PHP 版本选“纯静态”，根目录设为 `/opt/remote_carctl/frontend`，不创建数据库/FTP。
2. **上传产物**：把本地 `frontend/build/web/*` 上传到该根目录，确认根目录下有 `index.html`、`main.dart.js`、`canvaskit/`、`assets/`。
3. **后端只监听本机**：SSH 执行第 2 节，或让面板终端执行 `sed` + `systemctl restart remote-carctl`；确认 `ss -ltnp` 为 `127.0.0.1:8080`。
4. **反向代理**：站点设置 → 反向代理 → 添加，代理目录 `/api`，目标 URL `http://127.0.0.1:8080`，发送域名 `$host`。保存后 `curl -i https://agilex.yoyoyan.cn/api/v1/robot/status` 应为 JSON 401。
5. **HTTPS**：站点设置 → SSL → Let's Encrypt，申请 `agilex.yoyoyan.cn` 证书，开启“强制 HTTPS”。面板会自动写 80→443 跳转和证书续期任务。
6. **安全头与限流**：站点设置 → 配置文件，在 `server {}` 内加入：

   ```nginx
   include /www/server/panel/vhost/nginx/agilex-security-headers.conf;
   ```

   把本仓库的 `security-headers.conf` 上传到该路径。若面板配置已有 `add_header`，注意 nginx 只在当前层没有 `add_header` 时才继承 server 级头；必要时把安全头放到每个 `location`，或使用面板的“全局配置”。
7. **登录限流**：`limit_req_zone` 必须位于 `http {}` 上下文，不能直接写在站点 `server {}`。可在面板“nginx 配置 → 全局”加入两行 zone，再在站点配置的 `/api/v1/auth/login` location 内加 `limit_req`；不熟悉时先跳过限流，但要在验收清单中标注未启用。
8. **关闭默认站点/默认页**：面板 → 网站，确认 `agilex.yoyoyan.cn` 是唯一的默认站点，删除或停用不相关的默认站点；不要停用其他业务站点。
9. **面板自身安全**：面板端口不要对公网开放；使用强密码、二次验证和 HTTPS；面板账号与服务器 root 密码分开管理。

面板方式与系统 nginx 方式二选一，不要同时配置同一个 `server_name`。部署完成后按第 5 节验收，并在 `PROGRESS.md` 记录实际执行人、时间与输出。

