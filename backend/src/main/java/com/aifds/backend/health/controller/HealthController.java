package com.aifds.backend.health.controller;

import com.aifds.backend.health.dto.HealthResponse;
import com.aifds.backend.health.service.HealthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping
    public ResponseEntity<HealthResponse> getHealth() {
        return ResponseEntity.ok(healthService.getHealth());
    }
}
