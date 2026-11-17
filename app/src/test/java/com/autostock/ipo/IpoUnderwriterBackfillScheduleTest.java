package com.autostock.ipo;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

/** 공모 인수인 거꾸로 채우기 예약은 PC 켜짐 시간(07~20시)의 장 밖에서만 돈다(VALUNIVO 891, D351). */
class IpoUnderwriterBackfillScheduleTest {

    @Test
    void runsBeforeTheOpenAndAfterTheCloseWhileThePcIsOn() throws Exception {
        Method method = IpoUnderwriterBackfill.class.getMethod("scheduledPass");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo(IpoUnderwriterBackfill.CRON);
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");

        CronExpression cron = CronExpression.parse(IpoUnderwriterBackfill.CRON);
        LocalDateTime at = LocalDateTime.of(2026, 11, 17, 0, 0);
        List<String> runs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            at = cron.next(at);
            runs.add(at.toLocalTime().toString());
        }
        assertThat(runs).containsExactly("07:40", "16:40", "17:40", "18:40", "19:40");
        // 다음 날 첫 실행은 다시 07:40이다(20~07시 꺼짐 시간에는 돌지 않음).
        assertThat(cron.next(at)).isEqualTo(LocalDateTime.of(2026, 11, 18, 7, 40));
    }
}
