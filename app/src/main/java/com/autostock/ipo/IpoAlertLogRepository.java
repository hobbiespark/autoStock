package com.autostock.ipo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** 보낸 공모주 알림 기록(V18) — {@link IpoSyncScheduler}가 보내기 전에 보고, 보낸 뒤 남긴다. */
public interface IpoAlertLogRepository extends JpaRepository<IpoAlertLogEntity, Long> {

    /** 이 회사들에 보낸 알림 전부. */
    List<IpoAlertLogEntity> findByCorpCodeIn(Collection<String> corpCodes);
}
