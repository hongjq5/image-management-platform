# 项目讲解提纲

这份提纲用于把项目讲明白，并通过源码和验证记录支撑回答。项目基于程序员鱼皮的 [yu-picture](https://github.com/liyupi/yu-picture) 学习与维护；不要把整个上游项目或工具协助完成的改动表述成个人独立原创。介绍自己的贡献时，只说实际参与、理解并能复现的部分。

## 先讲清楚项目做什么

可以用下面的结构组织约一分钟的介绍，再替换成自己的真实经历：

> 这是面向个人素材整理和小型团队协作的图片管理应用，使用 Vue 3 和 Spring Boot，MySQL 保存图片与空间信息，Redis 保存会话，COS 保存图片。它支持公共图库、个人与团队空间、角色权限、图片裁剪和编辑动作同步。我基于上游项目学习和维护，重点理解了权限边界、图片替换的事务与配额，以及连接级协作控制。当前版本有本地运行和回归验证，协作要求单后端实例，尚未宣称生产部署或压测成绩。

最后补充自己确实做过的一项工作：遇到什么可复现的问题、读了哪个入口、改动如何防止复发、什么情况还未覆盖。没有实际完成的步骤不要使用第一人称认领。

| 上游基础 | 当前维护版本重点 |
| --- | --- |
| 图库、审核、个人/团队空间和统计页面 | 详情、列表、搜索和成员接口的权限一致性 |
| 文件/URL 上传模板、COS 图片处理 | 内容和 URL 校验、替换配额、事务补偿清理 |
| WebSocket、Disruptor 协作框架 | 连接持有编辑权、实时重新鉴权、断线和失败处理 |
| 登录、会话和前端业务页面 | PBKDF2 迁移、会话轮换、私有 COS 签名和 ID 精度维护 |

该表描述代码来源与维护范围，不自动等于某位同学的个人贡献。实现详情见[架构说明](architecture.md)，实际测试以[验证记录](verification.md)为准。

## 建议的源码阅读顺序

1. 从 [PictureDetailPage.vue](../yu-picture-frontend/src/pages/PictureDetailPage.vue) 的详情请求进入 [PictureController](../yu-picture-backend/src/main/java/com/yupi/yupicturebackend/controller/PictureController.java)，理解 DTO、VO 和 `BaseResponse`。
2. 阅读 [PictureServiceImpl](../yu-picture-backend/src/main/java/com/yupi/yupicturebackend/service/impl/PictureServiceImpl.java) 的 `uploadPicture` 和 `deletePicture`，沿方法调用追到 Mapper 和上传模板。
3. 阅读 [StpInterfaceImpl](../yu-picture-backend/src/main/java/com/yupi/yupicturebackend/manager/auth/StpInterfaceImpl.java)，区分身份、资源归属和操作权限。
4. 对照 [ImageCropper.vue](../yu-picture-frontend/src/components/ImageCropper.vue) 与 [PictureEditHandler](../yu-picture-backend/src/main/java/com/yupi/yupicturebackend/manager/websocket/PictureEditHandler.java)，追踪连接、编辑权、动作和最终保存。
5. 选择一个已有回归测试，理解输入和断言后在本地运行。能解释测试为什么会失败，比背诵类名更有说服力。

## 六个可以深入讲解的机制

### 1. 权限从可信资源关系推导

**问题：** 浏览者把请求体里的角色改为管理员，或者拿另一个空间的图片 ID 发请求，应该发生什么？

**实现：** 后端只把请求中的 ID 作为查找线索，重新读取用户、图片、空间和成员关系，校验资源属于哪个空间以及当前用户有什么权限。Spring Session 与 Sa-Token 身份还必须一致。详情和分页也执行检查；前端隐藏按钮不能阻止直接请求接口。

**取舍：** 读取数据库会增加查询，但能减少使用过期成员权限的风险。历史缓存分页入口当前直接委托给有鉴权的普通分页，因此不能把它介绍成正在运行的多级缓存方案。

**证据：** [StpInterfaceImpl](../yu-picture-backend/src/main/java/com/yupi/yupicturebackend/manager/auth/StpInterfaceImpl.java)、[PictureControllerSecurityTest](../yu-picture-backend/src/test/java/com/yupi/yupicturebackend/controller/PictureControllerSecurityTest.java)、[权限维护记录](auth-work-report.md)。

**准备回答：** “登录用户”与“有权操作这张图片”有什么不同？团队成员被移除后，下一次访问如何拒绝？

### 2. 并发替换的配额与事务

**问题：** 空间只剩一张图片的额度，两个请求同时上传怎么办？把一张 1 MiB 图片替换为 600 KiB 后，数量和容量应该怎样变化？

**实现：** 配额通过带边界条件的 `UPDATE` 原子调整，受影响行数必须为 1，并与图片写入处于同一数据库事务。替换先用 `SELECT ... FOR UPDATE` 锁定当前图片，再计算 `新大小 - 当前大小`；数量差额为 0，原上传者不变。前述替换例子减少 424 KiB，图片数量不变。

**取舍：** 云对象不属于 MySQL 事务。先上传新对象，数据库回滚后清理新对象；提交后再清理被替换对象。清理属于补偿操作，失败会记录日志，当前没有保证最终完成的持久重试机制。

**证据：** [PictureServiceImpl](../yu-picture-backend/src/main/java/com/yupi/yupicturebackend/service/impl/PictureServiceImpl.java)、[SpaceMapper](../yu-picture-backend/src/main/java/com/yupi/yupicturebackend/mapper/SpaceMapper.java)、[SpaceUsageIntegrationTest](../yu-picture-backend/src/test/java/com/yupi/yupicturebackend/mapper/SpaceUsageIntegrationTest.java)。

**准备回答：** 为什么先查余量再普通更新不够？数据库回滚成功，为什么仍可能留下孤立文件？

### 3. 上传模板与受限 URL 下载

**问题：** 文件名是 `.png` 就一定是图片吗？用户提交指向本机或内网的 URL，后端是否应该下载？

**实现：** 文件/URL 两个实现共用上传模板。限制实际读取的字节和像素，校验图片内容，关闭流并清理临时文件。URL 上传只允许受限公网 HTTP(S) 地址，实际连接使用的 DNS 解析结果也要检查；关闭自动跳转，限制连接、读取时间和下载总量。

**取舍：** JPEG/PNG 可以本地解码；静态 WebP 先做容器、尺寸和动画检查，再要求 COS 完整解码成功。当前不支持 SVG、GIF 或动画 WebP。应用校验仍需配合生产网络出口策略。

**证据：** [PictureUploadTemplate](../yu-picture-backend/src/main/java/com/yupi/yupicturebackend/manager/upload/PictureUploadTemplate.java)、[UrlPictureUpload](../yu-picture-backend/src/main/java/com/yupi/yupicturebackend/manager/upload/UrlPictureUpload.java)、[UploadValidationTest](../yu-picture-backend/src/test/java/com/yupi/yupicturebackend/manager/upload/UploadValidationTest.java)。

**准备回答：** MIME、扩展名、文件内容各有什么用途？为什么只检查第一次 DNS 结果、只信任 `Content-Length` 或 HEAD 响应仍不够？

### 4. 私有存储与响应时签名

**问题：** 接口拒绝了未授权用户，但图片裸链接公开可读，还算空间隔离吗？

**实现：** COS 使用私有访问；接口完成授权后，在返回副本上生成 15 分钟 GET 签名。数据库保留规范地址，原图和缩略图分别签名，浏览器直接读取 COS。CORS 用于浏览器跨域读取，与业务授权、桶访问控制分工不同。

**取舍：** 签名链接在到期前是可转交的访问凭证。撤销成员权限不等于立即撤销已发出的每一条签名；页面长期停留也需要重新获取链接。当前没有自动续签和逐链接撤销机制。

**证据：** [PictureUrlResponseAdvice](../yu-picture-backend/src/main/java/com/yupi/yupicturebackend/config/PictureUrlResponseAdvice.java)、[PictureUrlSigner](../yu-picture-backend/src/main/java/com/yupi/yupicturebackend/manager/PictureUrlSigner.java)、[PictureUrlSignerTest](../yu-picture-backend/src/test/java/com/yupi/yupicturebackend/manager/PictureUrlSignerTest.java)。

**准备回答：** 为什么不把签名 URL 持久化？为什么配置 CORS 不能使公开桶变成私有桶？

### 5. 协作编辑权属于连接

**问题：** 同一账号打开两个标签页，为什么不能只用用户 ID 判断谁正在编辑？

**实现：** 每张图片有一个内存房间，编辑者记录为一个 WebSocket 连接。获取/释放编辑权、关闭连接与执行动作遵循房间状态更新；返回给每个连接的 `isEditor` 单独计算。动作不回传发送者，避免本地执行后再次收到并重复执行。握手、消息到达和排队后执行时检查权限；断线释放该连接持有的编辑权。

**取舍：** 当前是单实例、单编辑连接的动作同步；Disruptor 只有一个消费者，慢查询或慢连接可能影响其他房间。没有实现跨实例锁与广播、CRDT/OT、编辑历史重放或完整断线恢复。最终裁剪图仍通过 HTTP 上传保存。

**证据：** [PictureEditHandler](../yu-picture-backend/src/main/java/com/yupi/yupicturebackend/manager/websocket/PictureEditHandler.java)、[Disruptor 配置](../yu-picture-backend/src/main/java/com/yupi/yupicturebackend/manager/websocket/disruptor/PictureEditEventDisruptorConfig.java)、[PictureEditHandlerTest](../yu-picture-backend/src/test/java/com/yupi/yupicturebackend/manager/websocket/PictureEditHandlerTest.java)。

**准备回答：** 用户排队期间被移出团队怎么办？Redis 已经共享会话，为什么还不能直接部署多个后端实例？

### 6. 密码渐进迁移与登录态轮换

**问题：** 旧账号保存的是旧版 MD5 摘要，怎样升级而不要求所有用户同时重置密码？

**实现：** 新密码采用带随机盐的 PBKDF2-HMAC-SHA256。登录先验证已有摘要；旧格式验证成功后生成新摘要，并以“用户 ID + 旧摘要”为条件更新，避免覆盖同时发生的密码变更。登录时清理旧会话并建立新会话，使原登录标识不继续沿用。

**取舍：** 密码哈希比普通摘要更耗计算量，仍需要登录限流和监控配合。当前实现不能因此宣称整个认证系统已达到生产安全要求；没有成功登录的旧账号仍保留旧格式，需考虑后续重置策略。

**证据：** [PasswordUtils](../yu-picture-backend/src/main/java/com/yupi/yupicturebackend/utils/PasswordUtils.java)、[UserServiceImpl](../yu-picture-backend/src/main/java/com/yupi/yupicturebackend/service/impl/UserServiceImpl.java)、[PasswordUtilsTest](../yu-picture-backend/src/test/java/com/yupi/yupicturebackend/utils/PasswordUtilsTest.java)。

**准备回答：** 随机盐如何改变查询和校验方式？为什么密码哈希不能说成“可解密的密码加密”？

## 演示和验证怎么讲

先展示团队图库、成员角色和图片编辑，再挑一条链路讲源码，操作见[演示说明](demo.md)。如果只演示单浏览器，不要把它称为已完成双浏览器协作验收。当前记录包含原生客户端的双连接联调，真实浏览器双标签页协作裁剪仍列为待验收项。

可以运行已有检查：后端目录 `mvn clean verify`；前端目录 `npm test`、`npm run lint`、`npm run build`。`build` 包含类型检查。需要真实 MySQL/Redis 的测试和云冒烟有单独环境要求，见[验证记录](verification.md)，不要在业务库上随意执行。

展示测试时解释它验证了哪条约束，例如“同账号第二个连接不能释放第一个连接的编辑权”，同时说明未覆盖的范围。测试通过不能换算为未测量的 QPS、吞吐倍数或可用性百分比。

## 介绍前自查

- 能画出页面 → Controller → Service → Mapper → MySQL 的路径，并说明 COS 和 Redis 分别保存什么。
- 能指出本次维护与上游已有能力的区别，也能准确说明自己的实际参与范围。
- 能解释一个失败场景及对应测试，而不只背设计模式名称。
- 不将停用的 AI、VIP、分库分表和未维护的 DDD 当成本版本已验证功能。
- 对单实例协作、签名到期、补偿清理、依赖告警和生产部署缺口有明确说明。

推荐先练熟两个机制，再按实际学习进度扩展。项目的说服力来自能够解释并验证的细节。
