package com.aifds.backend.health.service;

import com.aifds.backend.health.dto.HealthResponse;
import org.springframework.stereotype.Service;

@Service
public class HealthService {

    private static final String STATUS_UP = "UP";
    private static final String SERVICE_NAME = "backend";

    public HealthResponse getHealth() {
        return new HealthResponse(STATUS_UP, SERVICE_NAME);
    }
}
