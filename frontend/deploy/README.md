# exp3 前端部署（React 19 + Vite 7）

前端是 React 19 + TypeScript + Vite 7 的单页应用，构建产物在 `dist/`。生产环境由 nginx 同源提供静态文件，并把 `/api/` 反向代理到只监听 `127.0.0.1:8080` 的 Spring Boot 后端。

架构与约定：

- 源码：`/opt/remote_carctl/frontend`
- 部署目录：**`/var/www/agilex`**（不要用源码目录当 nginx root）
- 构建产物：`dist/`（`index.html` + `assets/` 哈希文件）
- 域名：`agilex.yoyoyan.cn` → 服务器公网 IP
- 生产同源，不需要 `VITE_API_BASE_URL`；开发环境由 Vite 代理 `/api` 到本机 8080

## 1. 本地构建并上传（推荐）

```powershell
cd E:\qq157\Desktop\class\software\exp3\frontend
npm ci
npm run typecheck
npm test
npm run build
```

如果已有 `frontend/build/agilex-deploy.zip`（内含 `web/` 与 `deploy/`），可直接上传：

```powershell
scp .\build\agilex-deploy.zip root@47.109.132.238:/tmp/
```

服务器：

```bash
cd /tmp
rm -rf agilex-deploy
unzip -o agilex-deploy.zip -d agilex-deploy
sudo install -d -m 755 /var/www/agilex
sudo rsync -a --delete /tmp/agilex-deploy/web/ /var/www/agilex/
```

或上传 `dist/`：

```powershell
scp -r .\dist\* root@47.109.132.238:/tmp/agilex-web/
scp .\deploy\security-headers.conf root@47.109.132.238:/tmp/agilex-security-headers.conf
scp .\deploy\nginx-http.conf root@47.109.132.238:/tmp/agilex-http.conf
```

服务器：

```bash
sudo apt update
sudo apt install -y nginx certbot
sudo systemctl enable --now nginx
sudo install -d -m 755 /var/www/agilex /var/www/certbot
sudo install -d -m 755 /etc/nginx/snippets

sudo rsync -a --delete /tmp/agilex-web/ /var/www/agilex/
sudo install -m 644 /tmp/agilex-security-headers.conf \
  /etc/nginx/snippets/agilex-security-headers.conf
sudo install -m 644 /tmp/agilex-http.conf \
  /etc/nginx/sites-available/agilex.yoyoyan.cn
sudo ln -sfn /etc/nginx/sites-available/agilex.yoyoyan.cn \
  /etc/nginx/sites-enabled/agilex.yoyoyan.cn
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl reload nginx

curl -sS -I http://agilex.yoyoyan.cn/
```

预期 `HTTP/1.1 200`，页面是 React 控制台。若 403/404，检查 `/var/www/agilex/index.html` 是否存在、nginx `root` 是否为 `/var/www/agilex`。

## 2. 在服务器构建（可选，需要 Node）

服务器没有 Node 时不要安装整套工具链，直接上传本地 `dist/`。有 Node 20.19+ / 22.12+ 时：

```bash
cd /opt/remote_carctl/frontend
node --version
bash deploy/build-and-deploy.sh
```

脚本会执行 `npm ci → typecheck → test → build`，再 `rsync dist/ /var/www/agilex/`。也可以手动：

```bash
cd /opt/remote_carctl/frontend
npm ci
npm run typecheck
npm test
npm run build

sudo install -d -m 755 /var/www/agilex
sudo rsync -a --delete dist/ /var/www/agilex/
ls -l /var/www/agilex/index.html
```

## 3. 后端配置

后端必须只监听本机，并显式允许生产 Origin：

```bash
cd /opt/remote_carctl/backend
mvn clean install

if grep -q '^BIND_ADDRESS=' /etc/remote-carctl/backend.env; then
  sudo sed -i 's/^BIND_ADDRESS=.*/BIND_ADDRESS=127.0.0.1/' /etc/remote-carctl/backend.env
else
  echo 'BIND_ADDRESS=127.0.0.1' | sudo tee -a /etc/remote-carctl/backend.env
fi

sudo sed -i '/^PATROL_WEB_ALLOWED_ORIGINS=/d' /etc/remote-carctl/backend.env
printf '\nPATROL_WEB_ALLOWED_ORIGINS=https://agilex.yoyoyan.cn\n' \
  | sudo tee -a /etc/remote-carctl/backend.env

sudo systemctl restart remote-carctl
sleep 3
sudo systemctl is-active remote-carctl
sudo ss -ltnp 'sport = :8080'
curl -sS -o /dev/null -w 'local_api=%{http_code}\n' \
  http://127.0.0.1:8080/api/v1/robot/status
```

期望 `active`、`127.0.0.1:8080`、`401`。CORS 未包含生产 Origin 时，浏览器登录会返回 403。

## 4. 申请证书并切换 HTTPS

```bash
sudo certbot certonly --webroot -w /var/www/certbot \
  -d agilex.yoyoyan.cn \
  --agree-tos -m 你的邮箱@example.com --no-eff-email
```

成功后：

```bash
sudo install -m 644 /opt/remote_carctl/frontend/deploy/nginx-tls.conf \
  /etc/nginx/sites-available/agilex.yoyoyan.cn
sudo nginx -t
sudo systemctl reload nginx
```

如果部署包在 `/tmp/agilex-deploy`，把路径换成 `/tmp/agilex-deploy/deploy/nginx-tls.conf`。

## 5. 验收

```bash
curl -sS -I http://agilex.yoyoyan.cn/ | grep -Ei 'HTTP/|location'
curl -sS -I https://agilex.yoyoyan.cn/ | grep -Ei 'HTTP/|strict-transport|content-security'
curl -sS -i https://agilex.yoyoyan.cn/api/v1/robot/status | head -15
curl -sS -o /dev/null -w 'swagger=%{http_code}\n' \
  https://agilex.yoyoyan.cn/swagger-ui.html
```

期望：

- HTTP 301 到 HTTPS
- HTTPS 200，包含 HSTS/CSP
- API 未登录返回 **JSON 401**
- `swagger=404`

自动检查：

```bash
cd /opt/remote_carctl/frontend
bash deploy/smoke-test.sh agilex.yoyoyan.cn
```

脚本检查跳转、HTML、HSTS、CSP、`X-Content-Type-Options`、`X-Frame-Options`、API 401、Swagger 404；只做未登录检查，不提交密码。

浏览器打开 <https://agilex.yoyoyan.cn/>，用演示账号登录，检查总览、巡检、控制、告警、日志。前端令牌只在内存，刷新页面即退出，这是预期行为。

## 6. 宝塔/BT 面板

若 `ls -d /www/server/panel` 存在，按面板操作，不要直接覆盖系统 nginx 配置：

1. 网站 → 添加站点：`agilex.yoyoyan.cn`，根目录 `/var/www/agilex`，纯静态。
2. SSL → Let's Encrypt，申请后开启强制 HTTPS。
3. 反向代理：代理目录 `/api` → 目标 `http://127.0.0.1:8080`，发送域名 `$host`。
4. 把 `security-headers.conf` 放到 `/www/server/panel/vhost/nginx/agilex-security-headers.conf`，在站点 `server {}` 里 include。
5. 重载：

```bash
sudo /www/server/nginx/sbin/nginx -t
sudo /www/server/nginx/sbin/nginx -s reload
```

## 7. 更新与回滚

更新：

```bash
cd /opt/remote_carctl/frontend
bash deploy/build-and-deploy.sh
# 或上传新的 dist/ 后：
sudo rsync -a --delete /tmp/agilex-web/ /var/www/agilex/
```

回滚：

```bash
sudo cp -a /var/www/agilex /var/www/agilex.bak-$(date +%F-%H%M)
sudo rsync -a --delete /tmp/agilex-web-old/ /var/www/agilex/
```

`index.html` 为 `no-store`，`assets/` 是内容哈希文件，可长期缓存；发版后刷新页面即可加载新 bundle。

## 8. 常见问题

| 现象 | 处理 |
| --- | --- |
| 登录 403 | `PATROL_WEB_ALLOWED_ORIGINS` 必须包含 `https://agilex.yoyoyan.cn`，并确认 `server.forward-headers-strategy=framework` |
| 页面 404/403 | nginx `root` 是否为 `/var/www/agilex`，`index.html` 是否存在 |
| 刷新子路径 404 | `location /` 必须有 `try_files $uri $uri/ /index.html;` |
| 页面空白，控制台 CSP 报错 | 生产构建不能有内联脚本/样式；本配置已按 Vite 构建验证。若自行改动构建，请同步调整 CSP |
| 证书续期失败 | 80 端口或 `/.well-known/acme-challenge/` 被拦截；`sudo certbot renew --dry-run -v` |
| `git pull` 后只有 `backend/` | 仓库启用了 sparse-checkout：`git sparse-checkout disable`，再 `git ls-files frontend \| wc -l` |
| 登录 429 | nginx 对登录接口限流 10 次/分钟（burst 5），等待后重试 |

## 9. 安全检查清单

- [ ] 后端只监听 `127.0.0.1:8080`，公网只开 80/443
- [ ] `PATROL_WEB_ALLOWED_ORIGINS=https://agilex.yoyoyan.cn`
- [ ] HTTPS 证书有效，HTTP 301 到 HTTPS
- [ ] HSTS、CSP、`X-Content-Type-Options`、`X-Frame-Options` 存在
- [ ] `/swagger-ui`、`/v3/api-docs` 返回 404
- [ ] 未登录 `/api/v1/**` 返回 JSON 401
- [ ] 登录接口出现 429 限流（压测时）
- [ ] 浏览器控制台无 CSP/网络错误
- [ ] 前端令牌不落 localStorage/sessionStorage/URL
