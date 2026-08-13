package com.autostock.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface EventRecordRepository extends JpaRepository<EventRecord, Long> {

    List<EventRecord> findByOccurredAtBetweenOrderByOccurredAtAsc(Instant from, Instant to);
}
