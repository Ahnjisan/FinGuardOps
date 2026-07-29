package com.aifds.backend.behavior.controller;

import com.aifds.backend.behavior.dto.BehaviorEventCreateRequest;
import com.aifds.backend.behavior.dto.BehaviorEventCreateResponse;
import com.aifds.backend.behavior.dto.BehaviorEventResponseMapper;
import com.aifds.backend.behavior.service.BehaviorEventIntakeResult;
import com.aifds.backend.behavior.service.BehaviorEventIntakeService;
import com.aifds.backend.common.trace.TraceIdFilter;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/behavior-events")
public class BehaviorEventIntakeController {

    private final BehaviorEventIntakeService service;
    private final BehaviorEventResponseMapper responseMapper;

    public BehaviorEventIntakeController(
            BehaviorEventIntakeService service,
            BehaviorEventResponseMapper responseMapper
    ) {
        this.service = service;
        this.responseMapper = responseMapper;
    }

    @PostMapping
    public ResponseEntity<BehaviorEventCreateResponse> receive(
            @Valid @RequestBody BehaviorEventCreateRequest request,
            @RequestAttribute(TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE)
            String traceId
    ) {
        BehaviorEventIntakeResult result = service.receive(request);
        if (result instanceof BehaviorEventIntakeResult.Created created) {
            return ResponseEntity.status(HttpStatus.CREATED).body(
                    responseMapper.toResponse(created.snapshot(), traceId)
            );
        }
        if (result instanceof BehaviorEventIntakeResult.Replay replay) {
            return ResponseEntity.ok(
                    responseMapper.toResponse(replay.snapshot(), traceId)
            );
        }
        throw new IllegalStateException(
                "Unsupported behavior event intake result: "
                        + result.getClass().getName()
        );
    }
}
