package com.ewe.dingtalk_approval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用入口。
 *
 * <p>开启定时任务用于审批状态兜底同步，见 {@link ApprovalSyncScheduler}。</p>
 */
@SpringBootApplication
@EnableScheduling
public class DingtalkApprovalApplication {

	public static void main(String[] args) {
		SpringApplication.run(DingtalkApprovalApplication.class, args);
	}

}
