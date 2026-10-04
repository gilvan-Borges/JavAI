package br.com.gilvanborges.javai.ai.api.chat;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class DailyLimiterTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    @Test
    void conta_por_visitante_e_zera_so_na_virada_do_dia() {
        var day1 = Clock.fixed(Instant.parse("2026-10-03T15:00:00Z"), SP);
        var limiter = new DailyLimiter(2, day1);

        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
        assertThat(limiter.tryAcquire("b")).isTrue();

        var day2 = new DailyLimiter(2, Clock.fixed(Instant.parse("2026-10-04T15:00:00Z"), SP));
        assertThat(day2.tryAcquire("a")).isTrue();
    }

    @Test
    void limite_zero_desliga_e_nunca_barra() {
        var limiter = new DailyLimiter(0, Clock.fixed(Instant.parse("2026-10-03T15:00:00Z"), SP));

        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire("a")).isTrue();
        }
    }
}
