package com.ewe.dingtalk_approval;

import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DingTalkStreamStatusController {
    private final ObjectProvider<DingTalkApprovalStreamListener> listener;
    private final Environment environment;

    public DingTalkStreamStatusController(ObjectProvider<DingTalkApprovalStreamListener> listener,
            Environment environment) {
        this.listener = listener;
        this.environment = environment;
    }

    @GetMapping("/api/dingtalk/stream/status")
    public Map<String, Object> status() {
        boolean enabled = environment.getProperty("dingtalk.stream.enabled", Boolean.class, false);
        DingTalkApprovalStreamListener current = listener.getIfAvailable();
        return Map.of("enabled", enabled, "running", current != null && current.isRunning());
    }
}
