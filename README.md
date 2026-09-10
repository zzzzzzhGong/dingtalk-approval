# EWE 审批中心（dingtalk-approval）

基于 Spring Boot 的钉钉审批集成服务：员工在本系统发起审批，审批人在待办页处理，
审批结果实时写回钉钉并同步到本地库，同时支持钉钉消息通知与统计概览。

- 运行环境：JDK 17、Maven Wrapper（`./mvnw`）、Spring Boot 4.0.8
- 数据库：H2 文件库，默认 `./data/dingtalk-approval`
- 联调步骤与权限配置：见 [TESTING.md](TESTING.md)

## 快速开始（一键启动脚本）

```bash
./start.sh          # 检查环境 → 加载凭证 → 构建 → 启动 → 自动打开浏览器，Ctrl+C 停止
./stop.sh           # 停止服务（含 --daemon 后台模式启动的实例）
```

`start.sh` 会自动完成这些事：

- 定位 JDK 17（依次尝试 `JAVA_HOME`、`/usr/libexec/java_home -v 17`、`PATH` 上的 `java`）；
- 按 `环境变量 → .env → loginCredential.txt` 的顺序加载钉钉凭证；
- 检测端口占用，若被本项目上一次的进程占用会先自动停止；
- `~/.m2` 不可写时自动改用项目内的 `.m2repo` 目录，避免构建直接失败；
- 启动后轮询 `/hello` 直到就绪，再打开浏览器，并把各页面地址打印出来；
- 前台模式下 Ctrl+C（或关闭终端、`kill`）会连同 Java 子进程一起优雅停止。

常用参数：

| 命令 | 说明 |
| --- | --- |
| `./start.sh --daemon` | 后台启动后立即返回，用 `./stop.sh` 停止 |
| `./start.sh --port 8081` | 换个端口（等价于 `PORT=8081 ./start.sh`） |
| `./start.sh --no-build` | 跳过构建，直接用已有 jar 启动（改完代码请勿使用） |
| `./start.sh --no-open` | 不自动打开浏览器 |
| `./start.sh --help` | 查看全部用法 |

推荐把凭证放到 `.env`（已在 `.gitignore` 中），而不是长期依赖明文文件：

```bash
cp .env.example .env && chmod 600 .env   # 然后填入 DINGTALK_CLIENT_ID / DINGTALK_CLIENT_SECRET
```

运行时文件位置：日志 `logs/app.log`，进程号 `.run/app.pid`。

### 启动失败怎么办

`start.sh` 会在启动前校验 jar 产物，并在启动失败时直接给出结论，而不是丢一大段 Spring 堆栈。常见情况：

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `Could not resolve placeholder 'dingtalk.process-code'` | jar 只打进了 class、没打进 `application.properties`（`target/` 被其他构建器污染） | `rm -rf target && ./start.sh`；脚本通常已自动识别并重建 |
| 提示端口被占用 | 上一次的实例还在跑 | `./stop.sh`，或 `./start.sh --port 8081` |
| 提示 H2 数据库初始化失败 | 数据库文件被另一个实例占用 | `./stop.sh` 后重试，必要时删掉 `data/*.lock.db` |
| 提示占位符无法解析 | 对应环境变量缺失 | 检查 `.env`，参考 `.env.example` |

脚本默认执行 **`clean package`**（实测只比增量构建多约 4 秒），因为这个项目由 IDE 的 Java 插件共用同一个 `target/` 目录，增量构建可能产出「有 class 但缺 resources」的 jar。

## 功能一览

| 模块 | 说明 |
| --- | --- |
| 钉钉扫码登录 | OAuth2 授权码换 userAccessToken，再以 unionId 换企业 userId，写入 Session |
| 发起审批 | 选择所属部门，调用钉钉工作流接口创建审批实例并登记到本地库 |
| 我的待办 | 列出钉钉指派给当前用户的待处理任务，含表单内容与历史意见 |
| 审批处理 | 同意 / 拒绝 / 追加意见，实时写回钉钉；意见留空时由系统自动生成并标注 |
| 结果同步 | 定时轮询 + Stream 事件 + 页面读取三条路径，互为兜底 |
| 消息通知 | 机器人单聊或企业工作通知，发送结果落库留痕 |
| 审批概览 | 统计与列表，支持按角色 / 部门分层级控制可见范围 |
| 审批审计 | 所有写回钉钉的操作在 `approval_decision` 表留痕，形成可追溯时间线 |

## 目录结构

```
src/main/java/com/ewe/dingtalk_approval/
├── DingtalkApprovalApplication.java    应用入口，开启定时任务
├── HelloController.java                首页跳转与健康检查
├── DingTalkLoginController.java        扫码登录与回调
├── DingTalkTokenService.java           企业内部 accessToken
├── DingTalkUserService.java            用户信息、unionId 换 userId、部门信息
├── DingTalkApprovalService.java        钉钉审批接口：模板、创建、详情、同意/拒绝、评论
├── DingTalkNotificationService.java    钉钉消息发送（机器人 / 工作通知）
├── DingTalkApprovalController.java     审批中心 REST 接口
├── DingTalkStreamStatusController.java Stream 状态查询
├── DingTalkApprovalStreamListener.java Stream 事件订阅（可选开启）
├── ApprovalRecordService.java          审批记录持久化、待办查询、通知去重
├── ApprovalDecisionService.java        审批决策与意见，写回钉钉并留痕
├── ApprovalTodoService.java            我的待办查询
├── ApprovalNotificationService.java    通知编排（通知谁、通知什么、留痕）
├── ApprovalSyncScheduler.java          审批状态定时同步
├── ApprovalAuthorityService.java       角色与数据范围判定
└── ApprovalStatus.java                 本地标准状态与钉钉结果映射
```

前端页面（`src/main/resources/static/`）：`index.html` 首页、`approval-test.html` 发起审批、
`approval-todo.html` 我的待办、`approval-status.html` 审批概览，样式与用户信息组件共用
`approval-ui.css` 与 `approval-profile.js`。

## 数据模型

| 表 | 用途 |
| --- | --- |
| `approval_record` | 审批实例主表，含状态、部门、标题、完成时间与通知去重列 `notified_status` |
| `approval_participant` | 审批参与人（审批人 / 抄送人），用于按人筛选与通知 |
| `approval_decision` | 审批意见与决策审计：动作、意见原文、来源（人工 / 系统） |
| `approval_notification` | 钉钉通知发送记录：接收人、事件、通道、状态与失败原因 |
| `dingtalk_event_receipt` | Stream 事件去重 |

状态归一化：`result=agree → APPROVED`、`result=refuse → REJECTED`、
`status 含 terminate/cancel → TERMINATED`，其余为 `RUNNING`。

## 接口速查

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/dingtalk/login` | 公开 | 跳转钉钉扫码登录 |
| GET | `/api/dingtalk/login/callback` | 公开 | 登录回调，写入 Session |
| GET | `/api/dingtalk/session` | 登录 | 当前用户与权限 |
| GET | `/api/dingtalk/csrf` | 登录 | 获取 CSRF 令牌 |
| GET | `/api/dingtalk/approval/options` | 登录 | 部门列表、可见模板、CSRF 令牌 |
| POST | `/api/dingtalk/approval/test` | 登录 + CSRF | 创建测试审批单 |
| GET | `/api/dingtalk/approvals/todo` | 审批人 | 我的待办 |
| GET | `/api/dingtalk/approvals/{id}` | 发起人 / 审批人 / 管理者 | 审批详情 + 时间线 |
| POST | `/api/dingtalk/approvals/{id}/decision` | 审批人 + CSRF | 同意 / 拒绝 |
| POST | `/api/dingtalk/approvals/{id}/comments` | 发起人 / 审批人 + CSRF | 追加审批意见 |
| GET | `/api/dingtalk/approvals` | 主管 / 开发者 | 审批概览统计与列表 |
| POST | `/api/dingtalk/approvals/{id}/sync` | 可见者 + CSRF | 手工同步单张单据 |
| POST | `/api/dingtalk/approvals/import` | 管理员 / 发起人 + CSRF | 导入已有实例 |
| GET | `/api/dingtalk/stream/status` | 公开 | Stream 运行状态 |

## 配置项

| 配置 | 环境变量 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `dingtalk.client-id` | `DINGTALK_CLIENT_ID` | 必填 | 应用 Client ID |
| `dingtalk.client-secret` | `DINGTALK_CLIENT_SECRET` | 必填 | 应用 Client Secret |
| `dingtalk.process-code` | - | 已内置模板 | 测试审批使用的模板 Code |
| `dingtalk.redirect-uri` | - | `http://localhost:8080/api/dingtalk/login/callback` | 登录回调地址 |
| `dingtalk.manager-user-ids` | `DINGTALK_MANAGER_USER_IDS` | 空 | 主管 userId 名单 |
| `dingtalk.manager-names` | `DINGTALK_MANAGER_NAMES` | `Vincent Wang` | 主管昵称名单 |
| `dingtalk.developer-user-ids` | `DINGTALK_DEVELOPER_USER_IDS` | 内置 | 开发者 userId 名单 |
| `dingtalk.approver-user-ids` | `DINGTALK_APPROVER_USER_IDS` | 空 | 审批人白名单，空表示不限制 |
| `dingtalk.manager-dept-ids` | `DINGTALK_MANAGER_DEPT_IDS` | 空 | 主管可见部门范围 |
| `dingtalk.manager-scope` | `DINGTALK_MANAGER_SCOPE` | 自动推断 | `INVOLVED` / `DEPARTMENT` / `ALL` |
| `dingtalk.stream.enabled` | `DINGTALK_STREAM_ENABLED` | `false` | 是否启用 Stream 事件订阅 |
| `dingtalk.notify.enabled` | `DINGTALK_NOTIFY_ENABLED` | `false` | 是否推送钉钉通知 |
| `dingtalk.notify.channel` | `DINGTALK_NOTIFY_CHANNEL` | `ROBOT` | `ROBOT` / `WORK_NOTICE` |
| `dingtalk.agent-id` | `DINGTALK_AGENT_ID` | 空 | 工作通知通道必需的 AgentId |
| `dingtalk.sync.enabled` | `DINGTALK_SYNC_ENABLED` | `true` | 是否开启定时同步 |
| `dingtalk.sync.interval-ms` | `DINGTALK_SYNC_INTERVAL_MS` | `300000` | 同步间隔 |
| `dingtalk.sync.batch-size` | `DINGTALK_SYNC_BATCH_SIZE` | `20` | 单轮同步条数上限 |

## 构建与测试

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./mvnw test          # 单元测试 + 持久化集成测试，全部使用模拟钉钉响应
./mvnw spring-boot:run
```

> 若 `~/.m2` 不可写（例如受限沙箱环境），追加 `-Dmaven.repo.local=<可写目录>`。

## 安全注意事项

- `loginCredential.txt` 中保存了明文 Client Secret，已被版本控制跟踪。
  建议改为仅通过环境变量注入，并**轮换该密钥**，同时把该文件加入 `.gitignore`。
- 所有写操作都要求 `X-CSRF-Token` 与 Session 中的令牌一致；发起人身份一律取自 Session，
  不接受前端传入。
- 对外错误响应不回传可能包含 `access_token` 的钉钉异常 URL。
