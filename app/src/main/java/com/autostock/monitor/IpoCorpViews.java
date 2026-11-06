package com.autostock.monitor;

import com.autostock.ipo.IpoDealEntity;
import com.autostock.ipo.IpoStatus;
import com.autostock.monitor.view.IpoDealView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 공모주 화면 — 회사마다 한 줄(2026-11-06, 사용자 "옵토닉스가 예정·진행 모두에 나타난다, 최종 상태에만 보여야").
 *
 * <p>정정 신고서마다 딜 행이 따로 있어(전체 이력은 DB에 그대로 둔다) 일정이 없는 옛 행은 UPCOMING에 머물고 최신 행은 SUBSCRIBING이
 * 되어 같은 회사가 두 탭에 보였다. 알림 계획기(IpoAlertPlanner)와 같은 기준으로 묶는다:
 * <ul>
 *   <li>대표 행 = 청약 일정이 있는 가장 최신 신고서, 없으면 가장 최신 신고서 — 화면의 id(청약·배정 입력이 저장되는 행)</li>
 *   <li>상태 = 회사 행들 중 가장 진행된 상태(상장 &gt; 청약 종료 &gt; 청약 중 &gt; 예정)</li>
 *   <li>그 밖의 칸 = 대표 행 값, 비어 있으면 최신 신고서부터 처음 채워진 값(봇·화면으로 넣은 청약·배정 기록이 다른 행에 있어도 보인다)</li>
 * </ul>
 */
final class IpoCorpViews {

    private IpoCorpViews() {
    }

    /** 회사마다 한 줄 — 입력 순서(청약 시작일 내림차순 등)대로 처음 나온 회사 순서를 지킨다. */
    static List<IpoDealView> byCorp(List<IpoDealEntity> deals) {
        Map<String, List<IpoDealEntity>> byCorp = deals.stream()
                .collect(Collectors.groupingBy(IpoDealEntity::getCorpCode, LinkedHashMap::new, Collectors.toList()));
        List<IpoDealView> out = new ArrayList<>();
        byCorp.values().forEach(rows -> out.add(merge(rows)));
        return out;
    }

    static IpoDealView merge(List<IpoDealEntity> rows) {
        List<IpoDealEntity> latestFirst = rows.stream()
                .sorted(Comparator.comparing(IpoDealEntity::getRceptNo).reversed()).toList();
        IpoDealEntity rep = latestFirst.stream().filter(d -> d.getSubscriptionStart() != null).findFirst()
                .orElse(latestFirst.get(0));
        List<IpoDealEntity> order = new ArrayList<>();
        order.add(rep);
        latestFirst.stream().filter(d -> d != rep).forEach(order::add);
        IpoStatus status = rows.stream().map(IpoDealEntity::getStatus).max(Comparator.comparingInt(IpoCorpViews::rank))
                .orElse(rep.getStatus());
        return new IpoDealView(
                rep.getId(), rep.getCorpCode(), first(order, IpoDealEntity::getCorpName), rep.getRceptNo(),
                first(order, IpoDealEntity::getOfferPriceLow), first(order, IpoDealEntity::getOfferPriceHigh),
                first(order, IpoDealEntity::getOfferPriceConfirmed), first(order, IpoDealEntity::getOfferPriceFinal),
                first(order, IpoDealEntity::getSubscriptionStart), first(order, IpoDealEntity::getSubscriptionEnd),
                first(order, IpoDealEntity::getRefundDate), first(order, IpoDealEntity::getListingDate),
                first(order, IpoDealEntity::getStockCode), first(order, IpoDealEntity::getLeadManager),
                first(order, IpoDealEntity::getInstitutionalCompetitionRate), first(order, IpoDealEntity::getLockupCommitRate),
                rep.getMetricsSource() == null ? null : rep.getMetricsSource().name(),
                first(order, IpoDealEntity::getMetricsRceptNo),
                status.name(), rep.getRecommendation().name(), rep.getRecommendReason(),
                first(order, IpoDealEntity::getAppliedQty), first(order, IpoDealEntity::getDeposit),
                first(order, IpoDealEntity::getAllocatedQty), first(order, IpoDealEntity::getSellPrice),
                first(order, IpoDealEntity::getSellDate), first(order, IpoDealEntity::getMemo),
                rep.getAdviceGrade(), rep.getAdviceScore(), first(order, IpoDealEntity::getFloatRatio),
                first(order, IpoDealEntity::getListedShares), first(order, IpoDealEntity::getListingOpen),
                first(order, IpoDealEntity::getListingClose));
    }

    /** 진행 순서 — 상장 &gt; 청약 종료 &gt; 청약 중 &gt; 예정. */
    static int rank(IpoStatus status) {
        return switch (status) {
            case UPCOMING -> 0;
            case SUBSCRIBING -> 1;
            case PASSED -> 2;
            case LISTED -> 3;
        };
    }

    private static <T> T first(List<IpoDealEntity> order, Function<IpoDealEntity, T> field) {
        return order.stream().map(field).filter(Objects::nonNull).findFirst().orElse(null);
    }
}
