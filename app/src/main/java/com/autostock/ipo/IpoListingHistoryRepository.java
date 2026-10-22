package com.autostock.ipo;

import org.springframework.data.jpa.repository.JpaRepository;

/** 공모주 판단 재료·상장일 성과(V19) — 회사마다 한 행. 작은 표라 맥락 계산은 전부 읽어 한다. */
public interface IpoListingHistoryRepository extends JpaRepository<IpoListingHistoryEntity, String> {
}
