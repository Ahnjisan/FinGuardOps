package com.aifds.backend.behavior.repository;

import com.aifds.backend.behavior.entity.BehaviorEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BehaviorEventRepository extends JpaRepository<BehaviorEvent, Long> {

    Optional<BehaviorEvent> findByEventId(UUID eventId);
}
