package com.autostock;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseEnvironmentTest {

    @Test
    void reportsEveryMissingOrBlankKeyInFixedOrder() {
        assertThat(DatabaseEnvironment.missing(Map.of())).containsExactly("DB_PORT", "DB_NAME", "DB_USER", "DB_PASSWORD");
        Map<String, String> env = new HashMap<>(Map.of("DB_PORT", "55438", "DB_NAME", "autostock_development", "DB_USER", " "));
        assertThat(DatabaseEnvironment.missing(env)).containsExactly("DB_USER", "DB_PASSWORD");
    }

    @Test
    void passesWhenAllKeysPresentAndHostIsOptional() {
        Map<String, String> env = Map.of("DB_PORT", "55438", "DB_NAME", "autostock_development",
                "DB_USER", "synthetic_owner", "DB_PASSWORD", "synthetic-password");
        assertThat(DatabaseEnvironment.missing(env)).isEmpty();
    }

    /** 하드코딩 계정 기본값이 application.yml에 다시 들어오지 않게 막는다(4-0/env). */
    @Test
    void applicationYamlHasNoDatabaseCredentialDefaults() throws IOException {
        String yaml;
        try (InputStream in = getClass().getResourceAsStream("/application.yml")) {
            assertThat(in).isNotNull();
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(yaml).contains("username: ${DB_USER}", "password: ${DB_PASSWORD}", ":${DB_PORT}/${DB_NAME}?");
        for (String key : List.of("DB_USER:", "DB_PASSWORD:", "DB_NAME:", "DB_PORT:")) {
            assertThat(yaml).doesNotContain("${" + key);
        }
    }
}
