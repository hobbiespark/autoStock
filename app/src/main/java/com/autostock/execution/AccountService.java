package com.autostock.execution;

import com.autostock.kiwoom.KiwoomRestClient;
import com.autostock.kiwoom.TrId;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 계좌 조회 — Phase 1 검증 대상 (모의계좌 잔고 조회 성공이 완료 기준).
 */
@Service
public class AccountService {

    private final KiwoomRestClient client;

    public AccountService(KiwoomRestClient client) {
        this.client = client;
    }

    public Map<String, Object> balance() {
        return client.call(TrId.ACCOUNT_BALANCE, "/api/dostk/acnt",
                Map.of("qry_tp", "1", "dmst_stex_tp", "KRX"));
    }
}
