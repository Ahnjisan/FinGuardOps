package com.aifds.backend.detection.controller;

import com.aifds.backend.detection.dto.AdoptedDetectionResultResponse;
import com.aifds.backend.detection.service.AdoptedDetectionResultQueryService;
import com.aifds.backend.transaction.validation.TransactionValidationException;
import com.aifds.backend.transaction.validation.TransactionValidationType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
public class AdoptedDetectionResultQueryController {
    private final AdoptedDetectionResultQueryService query;

    public AdoptedDetectionResultQueryController(AdoptedDetectionResultQueryService query) {
        this.query = query;
    }

    @GetMapping("/{transactionId}/adopted-detection-result")
    public ResponseEntity<AdoptedDetectionResultResponse> detail(
            @PathVariable String transactionId, HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw new TransactionValidationException(
                    TransactionValidationType.FORMAT, "query", "UNEXPECTED_QUERY",
                    "This endpoint does not accept query parameters"
            );
        }
        return ResponseEntity.ok(query.find(transactionId));
    }
}
