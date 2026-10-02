package com.autostock.market;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** 종목명 사전(V10) — {@link StockNameDirectory}만 쓴다. */
public interface StockNameRepository extends JpaRepository<StockNameEntity, String> {

    /**
     * 한 문장 upsert — 같은 종목을 두 스레드가 동시에 배워도 중복 키 오류 없이 마지막 값이 남는다
     * (조회 후 저장 방식은 그 사이 끼어든 insert와 부딪친다).
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO stock_names (symbol, name, source, updated_at)
            VALUES (:symbol, :name, :source, :updatedAt)
            ON CONFLICT (symbol) DO UPDATE
               SET name = EXCLUDED.name, source = EXCLUDED.source, updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    int upsert(@Param("symbol") String symbol, @Param("name") String name, @Param("source") String source,
               @Param("updatedAt") Instant updatedAt);

    /** 마지막 확인이 기준 시각보다 오래된 행 — 다시 조회할 대상. */
    List<StockNameEntity> findByUpdatedAtBefore(Instant before);
}
