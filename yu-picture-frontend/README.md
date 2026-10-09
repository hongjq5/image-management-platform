# yu-picture-frontend

Vue 3 + TypeScript + Vite 前端。项目原作者：程序员鱼皮 / [编程导航](https://www.codefather.cn)。完整服务端配置和部署说明见根目录文档。

## 本地运行

使用 Node.js 22 或 24，先启动 8123 端口的后端，再执行：

```sh
npm ci
npm run dev
```

默认 API 基址为 `/api`；开发服务器将 HTTP 和 WebSocket 代理到 `http://127.0.0.1:8123`，避免 localhost 解析为 IPv6 与后端 IPv4 监听不一致。浏览器始终访问 Vite 提示的前端地址。后端需允许这个地址作为 HTTP / WebSocket 来源。

需要不同地址时，把 `.env.example` 复制为 `.env.local` 后编辑。`VITE_API_BASE_URL` 包含 API 前缀，例如 `https://gallery.example.com/api`。`VITE_WS_BASE_URL` 可独立覆盖 WebSocket 的 API 基址；未设置时跟随 API 地址，HTTPS 自动使用 WSS。所有 `VITE_*` 值都会进入浏览器构建，不能存密钥。

生产部署需要把 `/api`（包括 `/api/ws/picture/edit` 的 Upgrade 请求）反向代理到后端，并为 Vue 路由配置 `index.html` 回退。跨域部署还需要配置可信来源和 Cookie；推荐同源部署。

## 验证与构建

```sh
npm test
npm run type-check
npm run lint
npm run build
npm audit --omit=dev
```

`lint` 只检查，不修改文件；需要自动修复时使用 `npm run lint:fix`。`build` 包含严格类型检查，产物位于 `dist/`。测试使用 Node 内置测试器和现有 Vue / TypeScript 编译器，执行真实组件 setup、状态和请求逻辑；不新增测试依赖。

## 功能边界

- 上传支持 JPEG、PNG、静态 WebP；动画 WebP 由后端拒绝。裁切结果上传为 PNG。
- 阿里云 AI 扩图和 VIP 兑换演示未启用，不需要阿里云凭据。
- 以图搜图会使用第三方搜索服务，仅允许已审核的公共图片；空间图片没有入口。
- 协作编辑锁属于单个浏览器连接；同账号其他标签页只能观察。断线后请关闭并重新打开编辑器。
- 对象 URL 使用短期签名；长时间停留后预览过期时刷新页面。跨域裁切依赖 COS 的 CORS 设置。

## 接口维护

`src/api` 保留了生成式 API 的结构，并修正了 Axios 路径、上传选项和 Java Long ID 类型。ID 使用 `API.Id`（字符串或数字），从路由读取时保留字符串，不能通过 `Number()` 转换雪花 ID。

`npm run openapi` 仅供维护接口时使用，依赖显式启用的后端 API 文档。重新生成后必须复查上述适配并运行完整检查，不能直接覆盖发布版本。`vue-cropper` 的上游声明引用了未声明的 SFC，本项目仅对其插件入口提供精确本地类型声明，不关闭全局类型检查。

完整验证结果和剩余依赖风险见 `../docs/frontend-work-report.md`。
