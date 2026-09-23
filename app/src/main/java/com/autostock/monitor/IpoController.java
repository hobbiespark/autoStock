package com.autostock.monitor;

import com.autostock.ipo.IpoDealEntity;
import com.autostock.ipo.IpoDealRepository;
import com.autostock.ipo.IpoStatus;
import com.autostock.ipo.IpoSyncScheduler;
import com.autostock.monitor.view.IpoDealView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 공모주 반자동 파이프라인 API (FE-3, PLAN.md ADR-9 트랙 E2) —
 * {@code GET /api/ipo?status=}, {@code POST /api/ipo/{id}/record}, {@code POST /api/ipo/{id}/metrics}.
 *
 * <p>ipo 모듈의 {@link IpoDealRepository}를 직접 참조한다 — {@code OrderHistoryController}가
 * trading 모듈을 직접 참조하는 기존 패턴과 동일(단일 소스 조회라 별도 Facade 없음,
 * ARCHITECTURE.md 규칙 20). 청약 "실행"은 이 API의 범위가 아니다 — 영웅문S#에서 수동
 * (ADR-9 확정).
 */
@RestController
@RequestMapping("/api/ipo")
public class IpoController {

    private final IpoDealRepository repository;
    private final IpoSyncScheduler syncScheduler;

    public IpoController(IpoDealRepository repository, IpoSyncScheduler syncScheduler) {
        this.repository = repository;
        this.syncScheduler = syncScheduler;
    }

    @GetMapping
    public List<IpoDealView> deals(@RequestParam(required = false) String status) {
        List<IpoDealEntity> entities = status == null || status.isBlank()
                ? repository.findAllByOrderBySubscriptionStartDesc()
                : repository.findByStatusOrderBySubscriptionStartAsc(parseStatus(status));
        return entities.stream().map(IpoController::toView).toList();
    }

    /** 내 청약/배정/매도 기록 upsert — 부분 갱신(null 필드는 기존 값 유지). */
    @PostMapping("/{id}/record")
    public ResponseEntity<IpoDealView> record(@PathVariable Long id, @RequestBody IpoRecordRequest request) {
        IpoDealEntity entity = repository.findById(id).orElse(null);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        entity.applyRecord(request.appliedQty(), request.deposit(), request.allocatedQty(),
                request.sellPrice(), request.sellDate(), request.memo());
        repository.save(entity);
        return ResponseEntity.ok(toView(entity));
    }

    /**
     * 기관경쟁률·의무보유확약비율·상장(예정)일 수동 입력 — 자동 수집 불가 경로(ipo.DartClient Javadoc).
     * null 필드는 기존 값 유지(부분 갱신). 입력 즉시 필터(권고)와 상태(상장일 → LISTED)를 재평가한다
     * (다음 배치까지 기다리지 않음).
     */
    @PostMapping("/{id}/metrics")
    public ResponseEntity<IpoDealView> metrics(@PathVariable Long id, @RequestBody IpoMetricsRequest request) {
        IpoDealEntity entity = repository.findById(id).orElse(null);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        entity.applyMetrics(request.institutionalCompetitionRate(), request.lockupCommitRate(), request.listingDate());
        syncScheduler.evaluateFilter(entity);
        syncScheduler.refreshStatus(entity);
        repository.save(entity);
        return ResponseEntity.ok(toView(entity));
    }

    private static IpoStatus parseStatus(String raw) {
        try {
            return IpoStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "알 수 없는 status: " + raw);
        }
    }

    private static IpoDealView toView(IpoDealEntity e) {
        return new IpoDealView(
                e.getId(), e.getCorpCode(), e.getCorpName(), e.getRceptNo(),
                e.getOfferPriceLow(), e.getOfferPriceHigh(), e.getOfferPriceConfirmed(),
                e.getSubscriptionStart(), e.getSubscriptionEnd(), e.getRefundDate(), e.getListingDate(),
                e.getLeadManager(), e.getInstitutionalCompetitionRate(), e.getLockupCommitRate(),
                e.getStatus().name(), e.getRecommendation().name(), e.getRecommendReason(),
                e.getAppliedQty(), e.getDeposit(), e.getAllocatedQty(), e.getSellPrice(), e.getSellDate(),
                e.getMemo());
    }
}
