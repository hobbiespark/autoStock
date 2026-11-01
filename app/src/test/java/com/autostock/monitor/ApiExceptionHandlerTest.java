package com.autostock.monitor;

import com.autostock.kiwoom.KiwoomApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 전역 예외 처리(B1) — 모든 오류가 RFC 9457 ProblemDetail + 기계용 code로 나가고, 내부 원인은 새지 않는다.
 */
class ApiExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new ApiExceptionHandler()).addFilters(new TraceIds()).build();

    @Test
    void 검증_실패는_400_VALIDATION_FAILED와_필드별_오류_목록이다() throws Exception {
        mvc.perform(post("/probe/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    @Test
    void 읽을_수_없는_JSON은_400_MALFORMED_REQUEST다() throws Exception {
        mvc.perform(post("/probe/validate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void 업무_예외는_지정한_코드와_확장_필드로_답한다() throws Exception {
        mvc.perform(get("/probe/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.detail").value("지금 상태에서는 할 수 없다"))
                .andExpect(jsonPath("$.currentStatus").value("RUNNING"));
    }

    @Test
    void 동시_갱신_충돌은_409_CONFLICT다() throws Exception {
        mvc.perform(get("/probe/optimistic"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void 브로커_연동_실패는_502이고_원인_메시지를_싣지_않는다() throws Exception {
        mvc.perform(get("/probe/upstream"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UPSTREAM_FAILURE"))
                .andExpect(content().string(not(containsString("appkey"))));
    }

    @Test
    void 예상_못한_예외는_500이고_내부_정보를_싣지_않는다() throws Exception {
        mvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("SELECT"))))
                .andExpect(content().string(not(containsString("NullPointer"))));
    }

    @Test
    void 오류에는_공통_칸_type_retryable_trace_id가_붙고_헤더와_같은_추적_ID다() throws Exception {
        var result = mvc.perform(get("/probe/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:valunivo:CONFLICT"))
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.trace_id").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{32}")))
                .andReturn();
        String header = result.getResponse().getHeader(TraceIds.HEADER);
        org.junit.jupiter.api.Assertions.assertEquals(header,
                com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.trace_id"));
    }

    @Test
    void 프레임워크_오류에도_공통_칸이_붙는다() throws Exception {
        mvc.perform(post("/probe/validate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(jsonPath("$.type").value("urn:valunivo:MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.trace_id").isNotEmpty());
    }

    @Test
    void 올바른_traceparent는_그_trace_id를_잇고_틀리면_새로_만든다() throws Exception {
        String id = "4bf92f3577b34da6a3ce929d0e0e4736";
        mvc.perform(get("/probe/boom").header("traceparent", "00-" + id + "-00f067aa0ba902b7-01"))
                .andExpect(header().string(TraceIds.HEADER, id))
                .andExpect(jsonPath("$.trace_id").value(id));
        mvc.perform(get("/probe/boom").header("traceparent", "00-" + "0".repeat(32) + "-00f067aa0ba902b7-01"))
                .andExpect(header().string(TraceIds.HEADER, not(id)))
                .andExpect(header().string(TraceIds.HEADER, org.hamcrest.Matchers.matchesPattern("[0-9a-f]{32}")));
    }

    @Test
    void 모든_오류_코드는_고유하고_4xx_5xx_상태를_가진다() {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (ErrorCode code : ErrorCode.values()) {
            org.junit.jupiter.api.Assertions.assertTrue(names.add(code.name()));
            org.junit.jupiter.api.Assertions.assertTrue(code.status().isError(), code.name());
            org.junit.jupiter.api.Assertions.assertFalse(code.title().isBlank(), code.name());
        }
    }

    @RestController
    static class ProbeController {
        record NameRequest(@NotBlank String name) {
        }

        @PostMapping("/probe/validate")
        void validate(@Valid @RequestBody NameRequest request) {
        }

        @GetMapping("/probe/conflict")
        void conflict() {
            throw new ApiException(ErrorCode.CONFLICT, "지금 상태에서는 할 수 없다", Map.of("currentStatus", "RUNNING"));
        }

        @GetMapping("/probe/optimistic")
        void optimistic() {
            throw new ObjectOptimisticLockingFailureException(Object.class, 1L);
        }

        @GetMapping("/probe/upstream")
        void upstream() {
            throw new KiwoomApiException("키움 API 오류 [ka10001] appkey=... 응답 본문");
        }

        @GetMapping("/probe/boom")
        void boom() {
            throw new IllegalStateException("SELECT * FROM orders — NullPointer 내부 상세");
        }
    }
}
