package com.autostock.ipo;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 공모주 딜 수동 입력(청약 기록·지표) 명령 — 조회·변경·재평가·저장을 한 트랜잭션으로 묶는다.
 * 없는 딜이면 빈 결과를 돌려주고, 호출부(monitor.IpoController)가 404로 바꾼다.
 */
@Service
public class IpoDealCommandService {

    private final IpoDealRepository repository;
    private final IpoSyncScheduler syncScheduler;
    private final Clock clock;

    public IpoDealCommandService(IpoDealRepository repository, IpoSyncScheduler syncScheduler, Clock clock) {
        this.repository = repository;
        this.syncScheduler = syncScheduler;
        this.clock = clock;
    }

    /** 내 청약/배정/매도 기록 부분 갱신 — null 필드는 기존 값 유지. */
    @Transactional
    public Optional<IpoDealEntity> recordMyDeal(Long id, RecordCommand command) {
        return repository.findById(id).map(entity -> {
            entity.applyRecord(command.appliedQty(), command.deposit(), command.allocatedQty(),
                    command.sellPrice(), command.sellDate(), command.memo(), clock.instant());
            repository.save(entity);
            return entity;
        });
    }

    /** 기관경쟁률·확약률·상장일 부분 갱신 후 필터(권고)와 상태를 즉시 재평가한다. */
    @Transactional
    public Optional<IpoDealEntity> updateMetrics(Long id, MetricsCommand command) {
        return repository.findById(id).map(entity -> {
            entity.applyMetrics(command.institutionalCompetitionRate(), command.lockupCommitRate(),
                    command.listingDate(), clock.instant());
            syncScheduler.evaluateFilter(entity);
            syncScheduler.refreshStatus(entity);
            repository.save(entity);
            return entity;
        });
    }

    /** 청약 기록 입력값. 전부 선택값(null이면 유지). */
    public record RecordCommand(Integer appliedQty, BigDecimal deposit, Integer allocatedQty,
                                BigDecimal sellPrice, LocalDate sellDate, String memo) {
    }

    /** 지표 입력값. 전부 선택값(null이면 유지). */
    public record MetricsCommand(BigDecimal institutionalCompetitionRate, BigDecimal lockupCommitRate,
                                 LocalDate listingDate) {
    }
}
