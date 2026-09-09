package com.ewe.dingtalk_approval;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "dingtalk.client-id=test-client", "dingtalk.client-secret=test-secret",
    "dingtalk.process-code=test-template", "dingtalk.redirect-uri=http://localhost/callback"
    , "spring.datasource.url=jdbc:h2:mem:dingtalk-test;DB_CLOSE_DELAY=-1"
})
class DingtalkApprovalApplicationTests {

	@Test
	void contextLoads() {
	}

}
