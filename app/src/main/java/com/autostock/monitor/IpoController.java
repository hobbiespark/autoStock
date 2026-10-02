package com.autostock.monitor;

import com.autostock.ipo.IpoDealCommandService;
import com.autostock.ipo.IpoDealEntity;
import com.autostock.ipo.IpoDealRepository;
import com.autostock.ipo.IpoStatus;
import com.autostock.monitor.view.IpoDealView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 공모주 반자동 파이프라인 API (FE-3, PLAN.md ADR-9 트랙 E2) —
 * {@code GET /api/ipo?status=}, {@code POST /api/ipo/{id}/record}, {@code POST /api/ipo/{id}/metrics}.
 *
 * <p>조회는 ipo 모듈의 {@link IpoDealRepository}를 직접 참조한다 — {@code OrderHistoryController}가
 * trading 모듈을 직접 참조하는 기존 패턴과 동일(단일 소스 조회라 별도 Facade 없음,
 * ARCHITECTURE.md 규칙 20). 수동 입력(명령)은 트랜잭션 경계를 가진 {@link IpoDealCommandService}에
 * 맡긴다(ARCHITECTURE.md 10절 "Command는 Application Service"). 청약 "실행"은 이 API의 범위가
 * 아니다 — 영웅문S#에서 수동(ADR-9 확정).
 */
@RestController
@RequestMapping("/api/ipo")
public class IpoController {

    private final IpoDealRepository repository;
    private final IpoDealCommandService commandService;

    public IpoController(IpoDealRepository repository, IpoDealCommandService commandService) {
        this.repository = repository;
        this.commandService = commandService;
    }

    @GetMapping
    public List<IpoDealView> deals(@RequestParam(required = false) String status) {
        List<IpoDealEntity> entities = status == null || status.isBlank()
                ? repository.findAllByOrderBySubscriptionStartDesc()
                : repository.findByStatusOrderBySubscriptionStartAsc(parseStatus(status));
        // 상장사 유상증자로 판정된 딜은 공모주가 아니다 — 행은 남기고 목록에서만 뺀다(2026-10-02, aiDoc/ipo-demand-forecast.md)
        return entities.stream().filter(e -> !e.isRightsOffering()).map(IpoController::toView).toList();
    }

    /** 내 청약/배정/매도 기록 upsert — 부분 갱신(null 필드는 기존 값 유지). */
    @PostMapping("/{id}/record")
    public ResponseEntity<IpoDealView> record(@PathVariable Long id, @Valid @RequestBody IpoRecordRequest request) {
        return toResponse(() -> commandService.recordMyDeal(id, new IpoDealCommandService.RecordCommand(
                request.appliedQty(), request.deposit(), request.allocatedQty(),
                request.sellPrice(), request.sellDate(), request.memo())));
    }

    /**
     * 기관경쟁률·의무보유확약비율·상장(예정)일 수동 입력. 지표는 [발행조건확정] 수요예측 결과에서 자동으로도 채워지며,
     * 여기서 넣은 지표는 자동 입력이 덮지 않는다(2026-10-02). null 필드는 기존 값 유지(부분 갱신). 입력 즉시 필터(권고)와
     * 상태(상장일 → LISTED)를 재평가한다(다음 배치까지 기다리지 않음).
     */
    @PostMapping("/{id}/metrics")
    public ResponseEntity<IpoDealView> metrics(@PathVariable Long id, @Valid @RequestBody IpoMetricsRequest request) {
        return toResponse(() -> commandService.updateMetrics(id, new IpoDealCommandService.MetricsCommand(
                request.institutionalCompetitionRate(), request.lockupCommitRate(), request.listingDate())));
    }

    /** 없는 딜은 NOT_FOUND. 배치와의 동시 갱신 충돌은 ApiExceptionHandler가 409로 바꾼다. */
    private static ResponseEntity<IpoDealView> toResponse(Supplier<Optional<IpoDealEntity>> command) {
        return command.get().map(entity -> ResponseEntity.ok(toView(entity)))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "공모주 딜을 찾을 수 없습니다."));
    }

    private static IpoStatus parseStatus(String raw) {
        try {
            return IpoStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER, "알 수 없는 status입니다.",
                    Map.of("allowed", Arrays.stream(IpoStatus.values()).map(Enum::name).toList()));
        }
    }

    private static IpoDealView toView(IpoDealEntity e) {
        return new IpoDealView(
                e.getId(), e.getCorpCode(), e.getCorpName(), e.getRceptNo(),
                e.getOfferPriceLow(), e.getOfferPriceHigh(), e.getOfferPriceConfirmed(),
                e.getSubscriptionStart(), e.getSubscriptionEnd(), e.getRefundDate(), e.getListingDate(),
                e.getLeadManager(), e.getInstitutionalCompetitionRate(), e.getLockupCommitRate(),
                e.getMetricsSource() == null ? null : e.getMetricsSource().name(), e.getMetricsRceptNo(),
                e.getStatus().name(), e.getRecommendation().name(), e.getRecommendReason(),
                e.getAppliedQty(), e.getDeposit(), e.getAllocatedQty(), e.getSellPrice(), e.getSellDate(),
                e.getMemo());
    }
}
