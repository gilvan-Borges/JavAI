package br.com.gilvanborges.javai.ai.api.web;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterTest {

    private static final class MutableClock extends Clock {
        long millis = 1_000_000;

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
            return Instant.ofEpochMilli(millis);
        }
    }

    @Test
    void deixa_passar_o_limite_e_barra_o_excesso_com_retry_after() {
        var limiter = new RateLimiter(60, new MutableClock());

        for (int i = 0; i < 60; i++) {
            assertThat(limiter.tryAcquire("1.2.3.4")).as("chamada %d", i).isZero();
        }
        assertThat(limiter.tryAcquire("1.2.3.4")).isEqualTo(1);
    }

    @Test
    void as_fichas_voltam_com_o_tempo() {
        var clock = new MutableClock();
        var limiter = new RateLimiter(60, clock);
        for (int i = 0; i < 60; i++) {
            limiter.tryAcquire("ip");
        }
        assertThat(limiter.tryAcquire("ip")).isPositive();

        clock.millis += 2_000; // 1 ficha por segundo
        assertThat(limiter.tryAcquire("ip")).isZero();
        assertThat(limiter.tryAcquire("ip")).isZero();
        assertThat(limiter.tryAcquire("ip")).isPositive();
    }

    @Test
    void cada_ip_tem_o_seu_balde() {
        var limiter = new RateLimiter(1, new MutableClock());
        assertThat(limiter.tryAcquire("a")).isZero();
        assertThat(limiter.tryAcquire("a")).isPositive();
        assertThat(limiter.tryAcquire("b")).isZero();
    }

    @Test
    void o_mapa_nao_cresce_sem_limite() {
        var limiter = new RateLimiter(5, new MutableClock());
        for (int i = 0; i < 25_000; i++) {
            assertThat(limiter.tryAcquire("ip-" + i)).isZero();
        }
    }

    @Test
    void limite_invalido_e_recusado() {
        assertThatThrownBy(() -> new RateLimiter(0, Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lotado_de_ips_diferentes_nao_zera_o_balde_de_quem_esta_martelando() {
        var clock = new MutableClock();
        var limiter = new RateLimiter(3, clock, 10);
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("martelador"); // esgota as 3 fichas
        }
        assertThat(limiter.tryAcquire("martelador")).isPositive();

        // Uma enxurrada de IPs diferentes enche o mapa e dispara a limpeza (cada um mexe uma vez; ficam com balde não cheio).
        for (int i = 0; i < 40; i++) {
            clock.millis += 1;
            limiter.tryAcquire("visitante-" + i);
        }

        // Antes isto virava clear(): o martelador voltava com 3 fichas novas. Agora ele continua barrado.
        clock.millis += 1;
        assertThat(limiter.tryAcquire("martelador")).as("o balde do martelador sobreviveu à limpeza").isPositive();
    }
}
