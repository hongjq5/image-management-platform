# 开发规范

本版本维护普通后端和 Vue 前端。保留现有模块名称与包名，避免仅为展示而移动大量文件。DDD 不参与当前改动和 CI。

## 分层与命名

- 后端 Controller 负责请求入口与登录检查，Service 负责业务约束和事务，Mapper 负责数据库访问，Manager 封装 COS / WebSocket 等外部能力。
- 参数放在 DTO，返回数据使用 VO；不要向前端返回密码摘要，不接受请求体中的角色作为授权依据。
- 类名使用 PascalCase，方法与变量使用 camelCase，常量使用 UPPER_SNAKE_CASE；Java 使用 4 空格缩进，前端使用 2 空格。
- 前端页面放 `src/pages/`，复用组件放 `src/components/`，API 类型和调用放 `src/api/`。数据库长整型 ID 在前端使用字符串，避免精度丢失。
- 前端使用既有 ESLint、TypeScript 与 Prettier 配置。提交前对改动文件格式化，不为无关文件制造批量 diff。

根目录 `.editorconfig` 统一新编辑文件的 UTF-8、缩进与换行。后端尚未引入 Checkstyle / Spotless，不能把编译通过称为完整 Java 风格检查。

## 行为约束

- 先读取可信成员关系，再检查具体图片所属空间；同样保护详情、分页、搜索、编辑和 WebSocket。
- 图片替换不新增图片数量；额度变化和数据库写入属于同一事务。对象清理应遵循提交 / 回滚边界。
- URL 上传必须经过外部地址校验和下载上限检查，不能跳过 SSRF 防护。
- 数据库保存规范 COS 地址；签名仅在授权响应中生成，不能写回数据库或日志。
- UI 根据权限控制入口，后端仍必须独立验证。不要将隐藏按钮当成安全边界。
- 日志记录必要的业务 ID 和错误类别，不记录 Cookie、密码、访问密钥或完整签名 URL。

## 验证和提交

修复行为缺陷时增加能复现问题的回归测试；纯文档不需要镜像测试。保持改动聚焦，不擅自引入依赖、升级框架主版本或改写历史。

```shell
# yu-picture-backend
mvn clean verify

# yu-picture-frontend
npm test
npm run lint
npm run build

# 仓库根目录
git diff --check
```

真实服务测试使用单独测试库。云冒烟会创建和清理自己的图片；不得对用户业务数据运行批量删除。详见 [验证记录](docs/verification.md)。

提交信息先说明为什么修改，再记录验证和未覆盖范围。例如：

```text
避免图片替换时重复占用空间配额

Constraint: 保留现有接口与作者归属
Confidence: high
Scope-risk: narrow
Tested: 替换计数与容量回归测试
Not-tested: 多实例压力测试
```

发布遵循 [安全说明](SECURITY.md)：检查暂存区、清理秘密、核对目标远程及可见性。原始来源与版权说明随代码保留。
