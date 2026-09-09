# 部门选择与审批测试

启动项目时，继续使用原有钉钉配置。打开应用同一域名下的 `/approval-test.html`（旧地址 `/api/dingtalk/approval/test` 会跳转到此页面）。

1. 点击扫码登录，在新窗口完成授权。
2. 返回测试页面，点击“刷新部门”。登录回调与测试页必须使用同一域名，才能共享 Session。
3. 一个部门时自动选中；多个部门时必须选择；没有部门时不能提交。
4. 点击“创建钉钉测试审批单”。成功后页面展示钉钉审批实例 ID，去钉钉查看并处理。

测试使用配置项 `dingtalk.process-code` 对应的模板。模板需包含“申请单号”“金额”“申请说明”三个兼容字段，金额固定为 100。申请单号每次生成。审批人沿用钉钉模板配置；本次没有实现发起人自选审批节点。

创建接口已改为 `POST /api/dingtalk/approval/test`，请求类型为 `application/json`：

```json
{"deptId": 123}
```

单部门可以提交 `{}` 由后端选择；多部门必须提供 `deptId`。发起人只从登录 Session 获取，提交时重新校验部门归属。请求头 `X-CSRF-Token` 使用 `GET /api/dingtalk/approval/options` 返回的 `csrfToken`，测试页自动处理。

页面提交后禁用按钮以减少重复点击，但当前不是具备持久化幂等控制的正式业务提交接口。超时、网络错误或未返回实例 ID 时，应先去钉钉确认是否创建成功，不能直接重试。

本次覆盖部门选择、测试创建、审批实例落库、按需同步最终结果和统计。用户绑定持久化以及钉钉事件主动推送尚未实现。

审批实例现在保存在项目 `data` 目录的本地 H2 数据库中。打开 `/approval-status.html` 可以导入已有实例、同步钉钉最终结果并查看统计。查询详情需要在钉钉应用权限管理中开通“工作流实例读权限”，修改后按钉钉提示发布版本。

## 自动接收审批结果（Stream）

1. 在钉钉开发者后台进入当前应用的“事件与回调/事件订阅”，选择 **Stream 模式**。
2. 添加“审批实例开始、结束、终止”事件 `bpms_instance_change`。建议只订阅当前模板 `PROC-97A5C485-D9B4-4470-85F2-BB98A6DF8AB7` 的 `start`、`finish` 和 `terminate`。
3. 按后台提示保存并发布应用版本。
4. 启动服务前设置 `export DINGTALK_STREAM_ENABLED=true`，并保持原有的 `DINGTALK_CLIENT_ID`、`DINGTALK_CLIENT_SECRET`。
5. 启动后访问 `/api/dingtalk/stream/status`，应返回 `{"enabled":true,"running":true}`。

Stream 模式由应用主动连接钉钉，不要求公网回调 URL。进程必须持续运行，同一个 Client ID 同时只运行一个 Stream 消费实例。收到重复事件时，系统以钉钉 `eventId` 去重；处理失败会返回稍后重试，成功后从详情接口取得实例最终状态并更新数据库。统计页面刷新后显示最新结果。

运行 `./mvnw test`。测试使用虚拟配置及模拟钉钉响应，不创建真实审批单。
