# 本地联调与测试指南

本文档说明如何在本机跑通「发起审批 → 审批人处理 → 结果同步与通知」的完整链路。

## 一、启动前准备

1. 在钉钉开发者后台创建企业内部应用，取得 `Client ID` 与 `Client Secret`。
2. 配置回调域名：`dingtalk.redirect-uri` 默认是 `http://localhost:8080/api/dingtalk/login/callback`，
   使用其他端口或域名时必须同步修改。
3. 开通接口权限（至少）：
   - 通讯录个人信息读权限、部门信息读权限（登录与部门选择）；
   - **工作流实例读权限**（查询审批详情，未开通时详情页会降级为本地缓存数据）；
   - 审批模板可见范围（发起审批）；
   - 若启用工作通知通道，还需消息通知相关权限。
4. 在本地准备数据库目录 `data/`（H2 会自动创建，无需手工建库）。

启动命令见 `loginCredential.txt`，Mac 下等价于：

```bash
export DINGTALK_CLIENT_ID="<你的 Client ID>"
export DINGTALK_CLIENT_SECRET="<你的 Client Secret>"
./mvnw spring-boot:run
```

更省事的方式是直接用一键启动脚本（自动定位 JDK、加载凭证、构建、启动并打开浏览器）：

```bash
./start.sh          # Ctrl+C 停止
./stop.sh           # 或停止后台模式启动的服务
./start.sh --help   # 查看全部参数
```

> 首次在本机构建时，如果 `~/.m2` 不可写，可以指定可写的本地仓库目录：
> `./mvnw -Dmaven.repo.local=/tmp/m2 spring-boot:run`（`start.sh` 会自动处理这种情况）。
> macOS 上还需要确保 `JAVA_HOME` 指向 JDK 17。

## 二、发起审批

打开 `/approval-test.html`（旧地址 `/api/dingtalk/approval/test` 会 302 跳转到此页面）。

1. 点击「钉钉扫码登录」，在新窗口完成授权。
2. 返回测试页，点击「刷新身份与部门」。登录回调与测试页必须使用同一域名，才能共享 Session。
3. 一个部门时自动选中；多个部门时必须选择；没有部门时不能提交。
4. 点击「创建钉钉测试审批单」，成功后页面展示钉钉审批实例 ID，到钉钉中查看并处理。
5. 页面提交后按钮会禁用，但**当前不是具备持久化幂等控制的正式业务提交接口**。
   超时、网络错误或未返回实例 ID 时，应先去钉钉确认是否创建成功，不能直接重试。

测试使用配置项 `dingtalk.process-code` 对应的模板。模板需包含「申请单号」「金额」「申请说明」
三个兼容字段，金额固定为 100，申请单号每次生成。审批人沿用钉钉模板配置；本次没有实现发起人自选审批节点。

创建接口为 `POST /api/dingtalk/approval/test`，请求类型 `application/json`：

```json
{"deptId": 123}
```

单部门可以提交 `{"deptId": null}` 由后端选择；多部门必须提供 `deptId`。发起人只从登录 Session 获取，
提交时重新校验部门归属。请求头 `X-CSRF-Token` 使用 `GET /api/dingtalk/csrf`（或
`GET /api/dingtalk/approval/options`）返回的 `csrfToken`，页面会自动处理。

## 三、审批人处理待办

打开 `/approval-todo.html`。

1. 页面列出**钉钉指派给当前登录用户**的待处理任务。最终以钉钉的流程指派为准：
   即使本地有记录，只要钉钉没有把任务派给你，就不会出现在待办里。
2. 每张卡片展示审批表单内容与本系统已记录的审批意见。
3. 填写审批意见后点击「同意」或「拒绝」，结果会**实时写回钉钉审批流程**；
   点击「仅提交意见」则只追加评论、不改变审批结果。
4. 审批意见留空时由系统自动生成，并明确标注「由 EWE 审批中心自动生成」，
   避免与审批人的真实理由混淆。

对照接口：

| 操作 | 接口 |
| --- | --- |
| 待办列表 | `GET /api/dingtalk/approvals/todo` |
| 审批详情 | `GET /api/dingtalk/approvals/{instanceId}` |
| 同意 / 拒绝 | `POST /api/dingtalk/approvals/{instanceId}/decision`，body `{"action":"agree","remark":"..."}` |
| 追加意见 | `POST /api/dingtalk/approvals/{instanceId}/comments`，body `{"content":"..."}` |

> 审批操作会真实影响钉钉流程，**测试时请只在自己的测试审批单上操作**。

## 四、审批概览与状态同步

打开 `/approval-status.html`：

- 顶部统计与列表的数据范围由角色决定（见第五节）；
- 每行「详情」展示钉钉表单内容、审批节点与本系统审批意见时间线；
- 「立即同步」刷新单张单据，「同步审批中单据」批量刷新；
- 「导入并查询」用于把已有钉钉实例纳入本系统跟踪。

状态同步有三条路径，互为兜底：

1. **定时同步**（默认开启）：每 `dingtalk.sync.interval-ms` 毫秒扫描「审批中」的单据并刷新结果；
2. **Stream 事件**：见第六节，实时性最好；
3. **页面读取**：打开待办或详情时顺带刷新并纠正过期状态。

同一次状态变化只会推送一次通知，由数据库的 `notified_status` 字段做原子抢占去重。

## 五、权限说明

| 角色 | 判定方式 | 能力 |
| --- | --- | --- |
| EMPLOYEE | 默认 | 发起审批，查看自己发起的单据 |
| APPROVER | `dingtalk.approver-user-ids` | 进入「我的待办」处理审批 |
| MANAGER | `dingtalk.manager-user-ids` / `dingtalk.manager-names` | 查看审批概览（范围见下） |
| DEVELOPER | `dingtalk.developer-user-ids` | 查看全量数据，用于交接期排障 |

主管的数据范围由 `dingtalk.manager-scope` 控制：

- `INVOLVED`（默认）：只看与自己发起或参与审批相关的单据；
- `DEPARTMENT`：只看 `dingtalk.manager-dept-ids` 中的部门；
- `ALL`：查看全部审批单。

未显式配置时会自动推断：设置了 `manager-dept-ids` 则为 `DEPARTMENT`，否则为 `INVOLVED`，
以最小权限为默认值，避免升级后意外扩大可见范围。

`dingtalk.approver-user-ids` 留空时任何登录用户都能打开待办页——钉钉只会把属于他的任务返回给他，
因此不构成越权；联调阶段可以填写白名单把测试范围限制在少数账号。

## 六、钉钉通知推送

测试版默认**关闭**通知，避免向同事推送真实钉钉消息。开启方式：

```bash
export DINGTALK_NOTIFY_ENABLED=true
# 可选，默认 ROBOT；使用工作通知需要另外配置 AgentId
export DINGTALK_NOTIFY_CHANNEL=ROBOT
# export DINGTALK_NOTIFY_CHANNEL=WORK_NOTICE
# export DINGTALK_AGENT_ID=123456
```

- `ROBOT`：机器人单聊消息，`robotCode` 取 `client-id`，无需额外配置（需要应用具备机器人能力）；
- `WORK_NOTICE`：企业工作通知，必须同时配置 `dingtalk.agent-id`。

发送结果（`SENT` / `FAILED` / `SKIPPED`）会写入 `approval_notification` 表，
可在审批详情页的「通知记录」中查看条数。通知失败不会中断审批主流程。

## 七、自动接收审批结果（Stream）

1. 在钉钉开发者后台进入当前应用的「事件与回调 / 事件订阅」，选择 **Stream 模式**。
2. 添加「审批实例开始、结束、终止」事件 `bpms_instance_change`。建议只订阅当前模板
   `PROC-97A5C485-D9B4-4470-85F2-BB98A6DF8AB7` 的 `start`、`finish` 和 `terminate`。
3. 按后台提示保存并发布应用版本。
4. 启动服务前设置 `export DINGTALK_STREAM_ENABLED=true`，并保持原有的
   `DINGTALK_CLIENT_ID`、`DINGTALK_CLIENT_SECRET`。
5. 启动后访问 `/api/dingtalk/stream/status`，应返回 `{"enabled":true,"running":true}`。

Stream 模式由应用主动连接钉钉，不要求公网回调 URL。进程必须持续运行，同一个 Client ID
同时只运行一个 Stream 消费实例。收到重复事件时以钉钉 `eventId` 去重；处理失败会返回稍后重试，
成功后从详情接口取得实例最终状态并更新数据库。

## 八、自动化测试

```bash
./mvnw test
```

测试使用虚拟配置与模拟钉钉响应（`MockRestServiceServer` / Mockito），**不会创建真实审批单、
不会发送真实钉钉消息**，数据库使用内存 H2。覆盖内容包括：

- 部门选择、创建审批、缺少实例 ID 不误报成功；
- 同意 / 拒绝 / 评论请求体与失败响应处理；
- 审批详情的 taskId、表单字段与待办任务识别；
- 通知通道选择、消息体拼装、失败不抛出；
- 权限角色、主管数据范围、审批人白名单；
- 新增审计表与通知去重列在真实 H2 上可正常建表。

## 九、尚未实现 / 已知限制

- 用户绑定持久化、审批人自选节点尚未实现；
- 审批人待办来自本地库 + 钉钉详情二次确认，未接入钉钉「待我审批」列表接口，
  因此**只有已经进入本系统跟踪的审批单**才会出现在待办页；完全在钉钉侧产生、
  又未被导入的实例需要先通过「导入并查询」纳入跟踪；
- 钉钉审批表单组件本身不支持 HTML 富文本，本系统的审批意见按多行纯文本处理并在钉钉端原样展示；
- 审批单创建与「同意 / 拒绝」均为一次性操作，没有幂等键，页面只做按钮禁用防护；
- 通知默认关闭，需要显式开启后才会推送。
