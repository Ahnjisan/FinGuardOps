package com.aifds.backend.detection.dto;

public record AdoptedMlEvidenceResponse(String reasonCode, int scoreContribution,
                                        int probabilityBasisPoints) { }
