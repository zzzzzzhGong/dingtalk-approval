package com.ewe.dingtalk_approval;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class DingTalkTokenService {

    @Value("${dingtalk.client-id}")
    private String clientId;

    @Value("${dingtalk.client-secret}")
    private String clientSecret;

    private final RestClient restClient = RestClient.create();

    public TokenResponse getAccessToken() {

        TokenRequest request = new TokenRequest(
                clientId,
                clientSecret
        );

        return restClient.post()
                .uri("https://api.dingtalk.com/v1.0/oauth2/accessToken")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(TokenResponse.class);
    }

    public record TokenRequest(
            String appKey,
            String appSecret
    ) {
    }

    public record TokenResponse(
            String accessToken,
            Long expireIn
    ) {
    }
}