package com.ewe.dingtalk_approval;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

@RestController
public class HelloController {

    @GetMapping("/")
    public ResponseEntity<Void> home() {
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION, "/index.html").build();
    }

    @GetMapping("/hello")
    public String hello() {
        return "DingTalk Approval System is running!";
    }
}
