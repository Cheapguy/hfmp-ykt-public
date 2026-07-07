package com.bosi.ykt.controller;

import com.bosi.ykt.common.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {
    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        return R.ok(Map.of("status", "UP", "ts", System.currentTimeMillis()));
    }
}
