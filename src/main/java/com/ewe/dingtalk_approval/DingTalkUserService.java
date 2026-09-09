package com.ewe.dingtalk_approval;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class DingTalkUserService {

    @Value("${dingtalk.client-id}")
    private String clientId;

    @Value("${dingtalk.client-secret}")
    private String clientSecret;

    private final RestClient restClient = RestClient.create();

    /**
     * 用网页登录返回的 authCode 获取当前登录用户个人信息
     */
    public DingTalkUserInfo getCurrentUser(String authCode) {

        UserTokenRequest request = new UserTokenRequest(
                clientId,
                clientSecret,
                authCode,
                "authorization_code"
        );

        UserTokenResponse tokenResponse =
                restClient.post()
                        .uri("https://api.dingtalk.com/v1.0/oauth2/userAccessToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(UserTokenResponse.class);

        if (tokenResponse == null ||
                tokenResponse.accessToken() == null) {

            throw new RuntimeException(
                    "Failed to get DingTalk user access token"
            );
        }

        return restClient.get()
                .uri("https://api.dingtalk.com/v1.0/contact/users/me")
                .header(
                        "x-acs-dingtalk-access-token",
                        tokenResponse.accessToken()
                )
                .retrieve()
                .body(DingTalkUserInfo.class);
    }


    /**
     * unionId -> 企业内部 userId
     */
    @SuppressWarnings("unchecked")
    public String getUserIdByUnionId(String unionId) {

        /*
         * 1. 获取企业内部应用 access_token
         */
        Map<String, Object> tokenResponse =
                restClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .scheme("https")
                                .host("oapi.dingtalk.com")
                                .path("/gettoken")
                                .queryParam("appkey", clientId)
                                .queryParam("appsecret", clientSecret)
                                .build())
                        .retrieve()
                        .body(Map.class);

        if (tokenResponse == null) {
            throw new RuntimeException(
                    "Failed to get DingTalk app access token"
            );
        }

        Number tokenErrCode =
                (Number) tokenResponse.get("errcode");

        if (tokenErrCode != null &&
                tokenErrCode.intValue() != 0) {

            throw new RuntimeException(
                    "DingTalk gettoken failed: "
                            + tokenResponse
            );
        }

        String accessToken =
                (String) tokenResponse.get("access_token");

        if (accessToken == null) {
            throw new RuntimeException(
                    "No access_token returned from DingTalk"
            );
        }


        /*
         * 2. 根据 unionId 获取企业内部 userId
         */
        Map<String, Object> request =
                Map.of("unionid", unionId);

        Map<String, Object> response =
                restClient.post()
                        .uri(
                                "https://oapi.dingtalk.com"
                                        + "/topapi/user/getbyunionid"
                                        + "?access_token="
                                        + accessToken
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(Map.class);

        if (response == null) {
            throw new RuntimeException(
                    "Empty response from DingTalk"
            );
        }

        Number errCode =
                (Number) response.get("errcode");

        if (errCode == null ||
                errCode.intValue() != 0) {

            throw new RuntimeException(
                    "Get userId failed: "
                            + response
            );
        }

        Map<String, Object> result =
                (Map<String, Object>) response.get("result");

        if (result == null ||
                result.get("userid") == null) {

            throw new RuntimeException(
                    "No userid returned: "
                            + response
            );
        }

        return result.get("userid").toString();
    }


    @SuppressWarnings("unchecked")
public java.util.List<Long> getDepartmentIds(String userId) {

    /*
     * 获取企业应用 access_token
     */
    Map<String, Object> tokenResponse =
            restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host("oapi.dingtalk.com")
                            .path("/gettoken")
                            .queryParam("appkey", clientId)
                            .queryParam("appsecret", clientSecret)
                            .build())
                    .retrieve()
                    .body(Map.class);

    if (tokenResponse == null) {
        throw new RuntimeException(
                "Failed to get DingTalk app access token"
        );
    }

    String accessToken =
            (String) tokenResponse.get("access_token");

    if (accessToken == null) {
        throw new RuntimeException(
                "No access_token returned from DingTalk"
        );
    }

    /*
     * 根据 userId 查询用户详情
     */
    Map<String, Object> request =
            Map.of(
                    "userid", userId,
                    "language", "zh_CN"
            );

    Map<String, Object> response =
            restClient.post()
                    .uri(
                            "https://oapi.dingtalk.com"
                            + "/topapi/v2/user/get"
                            + "?access_token="
                            + accessToken
                    )
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(Map.class);

    if (response == null) {
        throw new RuntimeException(
                "Empty response when getting user detail"
        );
    }

    Number errCode =
            (Number) response.get("errcode");

    if (errCode == null ||
            errCode.intValue() != 0) {

        throw new RuntimeException(
                "Get user detail failed: "
                        + response
        );
    }

    Map<String, Object> result =
            (Map<String, Object>) response.get("result");

    java.util.List<Object> deptIds =
            (java.util.List<Object>) result.get("dept_id_list");

    if (deptIds == null || deptIds.isEmpty()) {
        return java.util.List.of();
    }

    return deptIds.stream()
            .map(id -> ((Number) id).longValue())
            .toList();
}





    public record UserTokenRequest(
            String clientId,
            String clientSecret,
            String code,
            String grantType
    ) {
    }


    public record UserTokenResponse(
            String accessToken,
            Long expireIn,
            String refreshToken
    ) {
    }


    public record DingTalkUserInfo(
            String nick,
            String unionId,
            String openId,
            String avatarUrl
    ) {
    }


    @SuppressWarnings("unchecked")
        public Map<String, Object> getDepartmentDetail(Long deptId) {

        Map<String, Object> tokenResponse =
                restClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .scheme("https")
                                .host("oapi.dingtalk.com")
                                .path("/gettoken")
                                .queryParam("appkey", clientId)
                                .queryParam("appsecret", clientSecret)
                                .build())
                        .retrieve()
                        .body(Map.class);

        if (tokenResponse == null ||
                tokenResponse.get("access_token") == null) {

                throw new RuntimeException(
                        "Failed to get DingTalk access token"
                );
        }

        String accessToken =
                tokenResponse.get("access_token").toString();

        Map<String, Object> request =
                Map.of(
                        "dept_id", deptId,
                        "language", "zh_CN"
                );

        Map<String, Object> response =
                restClient.post()
                        .uri(
                                "https://oapi.dingtalk.com"
                                        + "/topapi/v2/department/get"
                                        + "?access_token="
                                        + accessToken
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(Map.class);

        if (response == null) {
                throw new RuntimeException(
                        "Empty department response"
                );
        }

        Number errCode =
                (Number) response.get("errcode");

        if (errCode == null ||
                errCode.intValue() != 0) {

                throw new RuntimeException(
                        "Get department failed: " + response
                );
        }

        return (Map<String, Object>) response.get("result");
        }


        
}