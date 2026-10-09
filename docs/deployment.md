# 配置与部署

## 环境变量

| 变量 | 用途 / 默认值 |
| --- | --- |
| DB_URL | JDBC MySQL 地址，默认本机 yu_picture |
| DB_USERNAME / DB_PASSWORD | 专用数据库账号与密码 |
| REDIS_HOST / REDIS_PORT / REDIS_PASSWORD / REDIS_DATABASE | Redis 连接，默认本机 6379、数据库 0 |
| COS_SECRET_ID / COS_SECRET_KEY | 后端专用 COS 密钥，绝不能使用 VITE_ 前缀传到浏览器 |
| COS_BUCKET / COS_REGION / COS_HOST | 桶名、区域、HTTPS 对象访问域名 |
| SERVER_PORT / SERVER_ADDRESS | 默认 8123 / 127.0.0.1，容器内需设置 0.0.0.0 |
| COOKIE_SECURE | HTTPS 部署设为 true |
| ALLOWED_ORIGINS | 浏览器来源白名单，逗号分隔；填写完整协议、域名与端口 |
| API_DOCS_ENABLED | 默认 false；仅受控开发环境打开 |

本地也可用与 pom.xml 同目录的 application-local.yml。运行工作目录必须是该后端目录，或显式提供 Spring 配置导入位置。线上优先通过服务管理器注入环境变量，不把秘密写进镜像、仓库或启动命令的可见参数。

生产数据库使用只对业务库授权的独立账号。Redis 应只对可信网络开放，使用密码及本项目独立数据库/命名空间。默认没有自动执行 SQL，启动不会擅自修改数据库结构。

Windows 个别 JDK/临时目录组合可能在 Redis 初始化时出现 `Unable to establish loopback connection` / `UnixDomainSockets ... Invalid argument`。先确认密码正确；如果堆栈属于 NIO selector，可创建短的 ASCII 临时目录，再加 JVM 参数 `-Djdk.net.unixdomain.tmpdir=该目录绝对路径`。此参数只解决本机运行环境，不需要改变 Redis 配置或系统防火墙。

## 腾讯云

需要一个 COS 桶、桶所在区域，以及可操作该桶内项目对象的凭据。图片处理（WebP/缩略图）依赖 COS 数据万象能力，具体开通和收费以控制台为准。不要授予整个账号的管理员权限。

项目后端校验决定谁能取得空间数据，但桶的读权限决定拿到原始 URL 后能否访问对象。私有空间不应使用任何人可读的桶；部署前检查桶策略及已有对象 ACL。不要为了让图片显示就把整个桶改成公有读。

如果浏览器直接读取 COS 图片并用 Canvas 裁剪，COS 的跨域规则也需要允许前端站点的 GET/HEAD 来源；这与后端 CORS 配置是两处独立设置。

接口返回的本站图片地址带有 15 分钟有效期的签名，数据库保存不带签名的规范地址。签名链接在有效期内是持有者访问凭证；页面长时间停留后需重新获取图片数据。私有桶与接口鉴权需要同时生效，签名代码本身不会阻止公有桶的裸链接访问。

本地联调的 COS CORS 来源为 `http://localhost:5173`、`http://127.0.0.1:5173`，方法 GET/HEAD，允许请求头 `*`，暴露响应头 ETag、Content-Length，预检缓存 600 秒。2026-09-19 的历史联调曾在授权测试桶验证私有读、匿名拒绝、签名访问、两个来源的 GET/HEAD 和预检及浏览器裁剪，见 [验证记录](verification.md)。该记录不代表使用者的新桶已经配置；部署时需对自己的桶重新验证。

上线后按真实域名调整 CORS，并通过 `SMOKE_CORS_ORIGINS`（逗号分隔）设置云冒烟使用的来源。修改其他已有桶权限前需核对项目外旧链接的用途；同时检查对象 ACL 和桶策略，避免额外的公开授权。

## 同源部署示例（Nginx）

先配置真实域名的 HTTPS 证书，再把前端 dist 放到静态目录。HTTP 升级到 HTTPS 规则和证书路径按服务器环境设置。

```nginx
# 在 http 块中定义
map $http_upgrade $connection_upgrade {
    default upgrade;
    '' close;
}

server {
    listen 443 ssl;
    server_name gallery.example.com;
    # ssl_certificate /path/to/fullchain.pem;
    # ssl_certificate_key /path/to/privkey.pem;
    root /srv/yu-picture/dist;
    client_max_body_size 11m;

    location /api/ {
        proxy_pass http://127.0.0.1:8123;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_read_timeout 300s;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

后端设置 ALLOWED_ORIGINS=https://gallery.example.com 和 COOKIE_SECURE=true。前端默认使用同源 /api，因此会使用 HTTPS/WSS。不要直接把 Vite 开发服务器作为生产服务。

## GitHub 发布前

- 运行 README 中的验证命令，查看 docs/verification.md 的实际验证范围。
- 确认 application-local.yml、.env、构建产物和 node_modules 不会被提交；不要用 git add -f 绕过忽略规则。
- 检查实际将上传的 Git 历史，不把开发副本中的旧配置、HTTP 会话文件或本地私有数据混入发布副本。历史隔离原则见 [发布记录](private-publication.md)，本副本本次处理见 [发布脱敏说明](publish-sanitization.md)。
- 云密钥通过本地配置或环境变量注入；如已泄露，应先撤销或轮换。
- 核对目标为自己的仓库及预期可见性，不向上游远程误推。当前没有新增许可证；公开发布前应核对上游再分发许可，保留来源说明。
- 本次准备不包含域名、证书、备案、服务器开通、备份监控或负载测试；真实公网服务要单独验收这些条件。

## 数据维护

删除空间/图片前按业务需求备份数据库与对象存储。对象清理失败时要查看后端告警日志，核对该对象已无数据库引用再处理。不要通过清空整个桶或 Redis 来解决测试问题。
