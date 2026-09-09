package com.ewe.dingtalk_approval;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpSession;

@RestController
public class DingTalkLoginController {

    @Value("${dingtalk.client-id}")
    private String clientId;

    @Value("${dingtalk.redirect-uri}")
    private String redirectUri;

    private final DingTalkUserService dingTalkUserService;

    public DingTalkLoginController(DingTalkUserService dingTalkUserService) {
        this.dingTalkUserService = dingTalkUserService;
    }

    @GetMapping("/api/dingtalk/login")
    public ResponseEntity<Void> login(HttpSession session) {

        // 防止别人伪造登录回调
        String state = UUID.randomUUID().toString();
        session.setAttribute("dingtalk_oauth_state", state);

        String url =
                "https://login.dingtalk.com/oauth2/auth"
                + "?redirect_uri="
                + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                + "&response_type=code"
                + "&client_id="
                + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&scope=openid"
                + "&state="
                + URLEncoder.encode(state, StandardCharsets.UTF_8)
                + "&prompt=consent";

        return ResponseEntity.status(302)
                .header(HttpHeaders.LOCATION, url)
                .build();
    }

    @GetMapping("/api/dingtalk/login/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String authCode,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            HttpSession session) {

        if (error != null) {
            return redirect("/?login=failed");
        }

        String savedState =
                (String) session.getAttribute(
                        "dingtalk_oauth_state"
                );

        if (savedState == null ||
                !savedState.equals(state)) {

            return redirect("/?login=invalid-state");
        }

        if (authCode == null ||
                authCode.isBlank()) {

            return redirect("/?login=missing-code");
        }

        DingTalkUserService.DingTalkUserInfo user =
                dingTalkUserService.getCurrentUser(authCode);

        String userId =
                dingTalkUserService.getUserIdByUnionId(
                        user.unionId()
                );
        
        session.setAttribute("dingtalk_user_id", userId);
        session.setAttribute("dingtalk_nick", user.nick());

        return redirect("/approval-test.html?login=success");
    }

    private ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION, location).build();
    }
}
