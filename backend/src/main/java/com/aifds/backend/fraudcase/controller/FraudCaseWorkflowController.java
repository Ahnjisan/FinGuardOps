package com.aifds.backend.fraudcase.controller;

import com.aifds.backend.common.trace.TraceIdFilter;
import com.aifds.backend.fraudcase.command.FraudCaseWorkflowCommand;
import com.aifds.backend.fraudcase.dto.FraudCaseAssigneeChangeRequest;
import com.aifds.backend.fraudcase.dto.FraudCaseMutationResponse;
import com.aifds.backend.fraudcase.dto.FraudCaseStatusChangeRequest;
import com.aifds.backend.fraudcase.service.FraudCaseWorkflowService;
import com.aifds.backend.fraudcase.validation.FraudCaseWorkflowValidator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cases")
public class FraudCaseWorkflowController {

    private final FraudCaseWorkflowValidator validator;
    private final FraudCaseWorkflowService service;

    public FraudCaseWorkflowController(
            FraudCaseWorkflowValidator validator,
            FraudCaseWorkflowService service
    ) {
        this.validator = validator;
        this.service = service;
    }

    @PatchMapping("/{caseId}/status")
    public ResponseEntity<FraudCaseMutationResponse> changeStatus(
            @PathVariable String caseId,
            @RequestBody FraudCaseStatusChangeRequest request,
            @RequestAttribute(TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE)
            String traceId
    ) {
        FraudCaseWorkflowCommand.StatusChange command =
                validator.validateStatus(caseId, request);
        return ResponseEntity.ok(service.changeStatus(command, traceId));
    }

    @PatchMapping("/{caseId}/assignee")
    public ResponseEntity<FraudCaseMutationResponse> changeAssignee(
            @PathVariable String caseId,
            @RequestBody FraudCaseAssigneeChangeRequest request,
            @RequestAttribute(TraceIdFilter.TRACE_ID_REQUEST_ATTRIBUTE)
            String traceId
    ) {
        FraudCaseWorkflowCommand.AssigneeChange command =
                validator.validateAssignee(caseId, request);
        return ResponseEntity.ok(service.changeAssignee(command, traceId));
    }
}
