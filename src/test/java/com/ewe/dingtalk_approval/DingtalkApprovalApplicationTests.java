package com.ewe.dingtalk_approval;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "dingtalk.client-id=test-client", "dingtalk.client-secret=test-secret",
    "dingtalk.process-code=test-template", "dingtalk.redirect-uri=http://localhost/callback"
    , "spring.datasource.url=jdbc:h2:mem:dingtalk-test;DB_CLOSE_DELAY=-1"
    // 测试环境关闭定时同步与钉钉通知，避免向真实钉钉发起请求。
    , "dingtalk.sync.enabled=false", "dingtalk.notify.enabled=false"
})
class DingtalkApprovalApplicationTests {

	@Test
	void contextLoads() {
	}

}
