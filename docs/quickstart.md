# 本地快速启动

只启动 `yu-picture-backend` 和 `yu-picture-frontend`。阿里云账号、服务器和 DDD 后端都不是本地运行的前提。

## 环境

| 组件 | 要求 |
| --- | --- |
| Java / Maven | JDK 17 / Maven 3.9.x；先用 `java -version` 和 `mvn -v` 核对 |
| Node.js | 22.14 或更新的 22 LTS；安装依赖使用 `npm ci` |
| MySQL | 8.0，创建专用数据库及数据库用户 |
| Redis | 启用认证，只对本机 / 可信网络开放 |
| 腾讯 COS | 私有桶、区域、受限权限凭据；处理图片需要数据万象能力 |

## 1. 初始化数据库

使用数据库客户端创建新的 `yu_picture` 数据库，字符集选择 `utf8mb4`，然后在该数据库执行 [完整表结构](../yu-picture-backend/sql/schema.sql)。该文件不负责升级已有库；不要覆盖自己的业务数据库。

数据库存用户、空间、成员关系和图片元数据；图片二进制文件保存在 COS。

## 2. 配置后端

将 [application-local.example.yml](../yu-picture-backend/application-local.example.yml) 复制为同目录的 `application-local.yml`，填写 MySQL、Redis、COS 信息。示例中的值必须替换为自己的配置。

本地配置放在 `pom.xml` 旁边，**不放在 `src/main/resources`**。文件被 Git 忽略，也不打入 jar。执行命令时应在后端目录。

COS 桶使用私有读。浏览器裁剪要求 COS 允许前端站点 GET/HEAD 的 CORS；本地来源为 `http://localhost:5173`、`http://127.0.0.1:5173`。详情见 [部署说明](deployment.md)。

```shell
cd yu-picture-backend
mvn spring-boot:run
```

健康检查：[http://localhost:8123/api/health](http://localhost:8123/api/health)，正常返回 `code=0` 和 `data=ok`。

## 3. 启动前端

另开一个终端：

```shell
cd yu-picture-frontend
npm ci
npm run dev
```

访问 [http://localhost:5173](http://localhost:5173)。Vite 将 `/api` 和 WebSocket 请求转给 8123 端口。更换后端地址时按 [前端说明](../yu-picture-frontend/README.md) 修改环境变量，不改写生成的 API 文件。

## 4. 建立自己的数据

注册账号 → 登录 → 创建私人空间或团队 → 上传图片。团队管理员可添加成员并设置角色。文件支持 JPEG、PNG、静态 WebP；单张最多 2 MiB、2500 万像素，不接收 SVG、GIF 或动画 WebP。

如需体验公开图库的审核，在专用本地库中核对**自己的测试账号**后赋予管理员角色，再退出重新登录：

```sql
SELECT id, userAccount, userRole FROM user WHERE userAccount = 'your-account';
UPDATE user SET userRole = 'admin'
WHERE userAccount = 'your-account' AND isDelete = 0;
```

这只用于本地初始化；不要建立共享默认管理员账号。

## 常见问题

| 现象 | 先检查 |
| --- | --- |
| 编译或 Lombok 报错 | Maven 是否真正使用 JDK 17 |
| 启动无法连接数据库 / Redis | 服务是否运行，账号、密码和端口是否匹配，本地 YAML 是否在运行目录 |
| Windows 报 NIO / UnixDomainSockets 错误 | 按部署说明设置进程级短路径临时目录，不关闭系统防火墙 |
| 图片能显示，裁剪失败 | COS CORS 是否允许当前前端来源 |
| 页面久置后图片失效 | 签名默认 15 分钟有效；刷新页面重新获取授权链接 |
| 上传成功但公共主页为空 | 公开上传默认待审核；私人和团队图片不会进入公共图库 |
| 访问空间被拒绝 | 当前账号是否是所有者 / 团队成员，成员角色是否允许该操作 |

测试和已知限制见 [验证记录](verification.md)。本地能运行不代表已部署到公网。
