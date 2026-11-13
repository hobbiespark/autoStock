package com.autostock.ipo;

import org.springframework.data.jpa.repository.JpaRepository;

/** DART 거꾸로 채우기 진행 위치(V35). */
public interface IpoBackfillProgressRepository extends JpaRepository<IpoBackfillProgressEntity, String> {
}
