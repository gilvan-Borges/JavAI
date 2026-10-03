package br.com.gilvanborges.javai.ai.api.admin;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoginThrottleTest {

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-03T15:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final LoginThrottle throttle = new LoginThrottle(clock, 5, Duration.ofMinutes(15));

    @Test
    void bloqueia_no_quinto_erro_e_libera_depois_de_15_minutos() {
        for (int i = 0; i < 4; i++) {
            throttle.recordFailure("a");
        }
        assertThat(throttle.lockedFor("a")).isEmpty();

        throttle.recordFailure("a");
        assertThat(throttle.lockedFor("a")).isPresent();

        clock.now = clock.now.plus(Duration.ofMinutes(16));
        assertThat(throttle.lockedFor("a")).isEmpty();
    }

    @Test
    void erros_antigos_nao_contam_e_acertar_zera() {
        for (int i = 0; i < 4; i++) {
            throttle.recordFailure("a");
        }
        clock.now = clock.now.plus(Duration.ofMinutes(20));
        throttle.recordFailure("a");
        assertThat(throttle.lockedFor("a")).isEmpty();

        for (int i = 0; i < 3; i++) {
            throttle.recordFailure("b");
        }
        throttle.reset("b");
        for (int i = 0; i < 4; i++) {
            throttle.recordFailure("b");
        }
        assertThat(throttle.lockedFor("b")).isEmpty();
    }

    @Test
    void cada_ip_tem_o_seu_contador() {
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure("a");
        }

        assertThat(throttle.lockedFor("a")).isPresent();
        assertThat(throttle.lockedFor("b")).isEmpty();
    }
}
