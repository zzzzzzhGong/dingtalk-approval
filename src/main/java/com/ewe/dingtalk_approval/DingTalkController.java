package com.ewe.dingtalk_approval;

import java.util.HashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dingtalk")
public class DingTalkController {

    private final DingTalkTokenService tokenService;

    public DingTalkController(DingTalkTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @GetMapping("/token/test")
    public Map<String, Object> testToken() {

        DingTalkTokenService.TokenResponse response =
                tokenService.getAccessToken();

        Map<String, Object> result = new HashMap<>();

        if (response != null && response.accessToken() != null) {
            String token = response.accessToken();

            result.put("success", true);
            result.put("expireIn", response.expireIn());
            result.put(
                    "tokenPreview",
                    token.substring(0, Math.min(8, token.length())) + "..."
            );
        } else {
            result.put("success", false);
        }

        return result;
    }
}