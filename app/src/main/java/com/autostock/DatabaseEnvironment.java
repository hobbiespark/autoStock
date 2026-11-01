package com.autostock;

import java.util.List;
import java.util.Map;

/**
 * DB 접속 값이 환경 변수로 들어왔는지 Spring을 올리기 전에 확인한다(VALUNIVO 4-0/env, D213·D256).
 *
 * <p>2026-11-01 전에는 {@code application.yml}이 {@code ${DB_USER:autostock}}처럼 계정·암호·DB 이름 기본값을 갖고 있어,
 * 값이 빠져도 하드코딩 계정으로 조용히 붙었다. 이제 값의 원본은 Git 밖 VALUNIVO 입력 파일이고, 생성기가 만든 앱 설정
 * ({@code app-config\<환경>\autostock.env})을 {@code scripts/load_env.ps1}이 {@code .env}의 {@code AUTOSTOCK_APP_CONFIG}로 읽어 넣는다.
 * 값이 없으면 기본값으로 붙지 않고 여기서 멈춘다.
 *
 * <p>Spring의 설정 바인딩은 풀리지 않은 {@code ${DB_USER}}를 글자 그대로 남겨 엉뚱한 인증 오류로 끝나므로, 빠진 키 이름을 먼저
 * 알린다. 값은 출력하지 않는다. {@code DB_HOST}는 비밀이 아니고 지금 모두 같은 PC라 {@code localhost} 기본값을 둔다.
 */
final class DatabaseEnvironment {

    static final List<String> REQUIRED = List.of("DB_PORT", "DB_NAME", "DB_USER", "DB_PASSWORD");

    private DatabaseEnvironment() {
    }

    /** 비었거나 없는 필수 키 이름(순서 고정). */
    static List<String> missing(Map<String, String> env) {
        return REQUIRED.stream().filter(key -> {
            String value = env.get(key);
            return value == null || value.isBlank();
        }).toList();
    }
}
