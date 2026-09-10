package com.ewe.dingtalk_approval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * “我的待办”查询服务。
 *
 * <p>数据来源分两步：</p>
 * <ol>
 *   <li>先在本地库中筛出与当前用户相关且仍为“审批中”的单据，缩小查询范围；</li>
 *   <li>再逐条向钉钉拉取审批实例详情，只有钉钉返回了属于他的 RUNNING 任务，才算真正的待办。</li>
 * </ol>
 *
 * <p>这样既保证不会越权展示（最终以钉钉的流程指派为准），
 * 又能在用户打开页面时顺带把本地状态刷新为最新，避免显示已过期的“审批中”。</p>
 */
@Service
public class ApprovalTodoService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalTodoService.class);

    private final DingTalkApprovalService dingTalk;
    private final ApprovalRecordService records;
    private final ApprovalNotificationService notifications;
    private final ApprovalDecisionService decisions;

    public ApprovalTodoService(DingTalkApprovalService dingTalk, ApprovalRecordService records,
            ApprovalNotificationService notifications, ApprovalDecisionService decisions) {
        this.dingTalk = dingTalk;
        this.records = records;
        this.notifications = notifications;
        this.decisions = decisions;
    }

    /**
     * 查询指定用户的待处理审批任务。
     *
     * <p>单条单据查询失败不影响其他单据，例如某张单在钉钉侧已被删除或当前应用无读权限。</p>
     *
     * @param userId 当前登录用户钉钉 userId
     * @return 待办列表，按创建时间倒序
     */
    public List<TodoItem> listPending(String userId) {
        List<TodoItem> items = new ArrayList<>();
        for (ApprovalRecordService.ApprovalRecord candidate : records.listPendingForUser(userId)) {
            String instanceId = candidate.instanceId();
            try {
                DingTalkApprovalService.ApprovalDetail detail = dingTalk.getApprovalDetail(instanceId);
                // 读取即同步：打开待办页时顺带纠正本地可能已过期的状态，并推送状态变更通知。
                ApprovalRecordService.SyncResult result = records.applyDetail(instanceId, detail);
                notifications.notifyIfStatusChanged(result);

                DingTalkApprovalService.ApprovalTask task = detail.pendingTaskOf(userId);
                if (task == null) {
                    // 该用户在这张单据上已无待办（可能已处理或流程已结束），不展示。
                    continue;
                }
                items.add(new TodoItem(result.after(), task.taskId(), task.activityId(),
                        detail.originatorUserId(), detail.formComponentValues(),
                        decisions.timeline(instanceId)));
            } catch (RuntimeException exception) {
                log.warn("查询待办时获取审批详情失败，instanceId={}，将跳过该单据", instanceId, exception);
            }
        }
        return items;
    }

    /**
     * 统计某张审批单上仍未处理的审批人，用于在详情页提示“还在等谁处理”。
     */
    public Set<String> pendingApprovers(DingTalkApprovalService.ApprovalDetail detail) {
        Set<String> pending = new LinkedHashSet<>();
        if (detail == null || detail.tasks() == null) return pending;
        detail.tasks().stream().filter(java.util.Objects::nonNull)
                .filter(task -> task.isPendingFor(task.userId()))
                .map(DingTalkApprovalService.ApprovalTask::userId)
                .forEach(pending::add);
        return pending;
    }

    /**
     * 一条待办任务。
     *
     * @param record      本地审批记录（已刷新为钉钉最新状态）
     * @param taskId      钉钉审批任务 ID，提交同意 / 拒绝时需要
     * @param activityId  任务所属审批节点 ID
     * @param originator  发起人钉钉 userId
     * @param formValues  审批表单内容，供审批人判断
     * @param timeline    本系统已记录的审批意见
     */
    public record TodoItem(ApprovalRecordService.ApprovalRecord record, Long taskId, String activityId,
            String originator, List<DingTalkApprovalService.FormField> formValues,
            List<ApprovalDecisionService.Decision> timeline) {}
}
