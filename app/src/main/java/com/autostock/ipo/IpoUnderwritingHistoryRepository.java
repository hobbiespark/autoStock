package com.autostock.ipo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 지난 공모의 인수인(V35). */
public interface IpoUnderwritingHistoryRepository extends JpaRepository<IpoUnderwritingHistoryEntity, Long> {

    Optional<IpoUnderwritingHistoryEntity> findByCorpCode(String corpCode);
}
