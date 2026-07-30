package com.aifds.backend.detection.repository;

import com.aifds.backend.detection.entity.DetectionEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DetectionEvidenceRepository
        extends JpaRepository<DetectionEvidence, Long> {

    Optional<DetectionEvidence> findByEvidenceId(UUID evidenceId);

    List<DetectionEvidence>
    findAllByDetectionResult_DetectionResultIdOrderBySortOrderAscIdAsc(
            UUID detectionResultId
    );
}
