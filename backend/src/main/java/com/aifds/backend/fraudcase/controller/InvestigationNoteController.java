package com.aifds.backend.fraudcase.controller;

import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.fraudcase.dto.InvestigationNoteCreateRequest;
import com.aifds.backend.fraudcase.dto.InvestigationNoteCreateResponse;
import com.aifds.backend.fraudcase.dto.InvestigationNoteListResponse;
import com.aifds.backend.fraudcase.service.InvestigationNoteService;
import com.aifds.backend.fraudcase.validation.InvestigationNoteValidator;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cases/{caseId}/notes")
public class InvestigationNoteController {

    private final InvestigationNoteValidator validator;
    private final InvestigationNoteService service;

    public InvestigationNoteController(
            InvestigationNoteValidator validator,
            InvestigationNoteService service
    ) {
        this.validator = validator;
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<InvestigationNoteCreateResponse> create(
            @PathVariable String caseId,
            @RequestBody InvestigationNoteCreateRequest request,
            @RequestAttribute(TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE) String traceId
    ) {
        InvestigationNoteCreateResponse response = service.create(
                validator.validateCreate(caseId, request), traceId
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<InvestigationNoteListResponse> list(
            @PathVariable String caseId,
            @RequestParam MultiValueMap<String, String> parameters,
            @RequestAttribute(TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE) String traceId
    ) {
        return ResponseEntity.ok(service.list(
                validator.validateList(caseId, parameters), traceId
        ));
    }
}
