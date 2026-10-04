package br.com.gilvanborges.javai.ai.api.chat;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class AbuseGuardTest {

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
    private final AbuseGuard guard = new AbuseGuard(clock, Duration.ofHours(12), 0.90, 3);

    @Test
    void ataque_com_confianca_alta_bloqueia_na_hora_por_12_horas() {
        assertThat(guard.blockedFor("a")).isEmpty();

        assertThat(guard.recordAttack("a", 0.95)).isTrue();

        assertThat(guard.blockedFor("a")).hasValueSatisfying(left -> assertThat(left).isEqualTo(Duration.ofHours(12)));
    }

    @Test
    void ataque_de_confianca_media_so_conta_strike_e_o_terceiro_bloqueia() {
        assertThat(guard.recordAttack("a", 0.60)).isFalse();
        assertThat(guard.recordAttack("a", 0.75)).isFalse();
        assertThat(guard.blockedFor("a")).isEmpty();

        assertThat(guard.recordAttack("a", 0.55)).isTrue();
        assertThat(guard.blockedFor("a")).isPresent();
    }

    @Test
    void o_bloqueio_acaba_depois_de_12_horas_e_a_ficha_fica_limpa() {
        guard.recordAttack("a", 0.99);

        clock.now = clock.now.plus(Duration.ofHours(11)).plus(Duration.ofMinutes(59));
        assertThat(guard.blockedFor("a")).isPresent();

        clock.now = clock.now.plus(Duration.ofMinutes(2));
        assertThat(guard.blockedFor("a")).isEmpty();
        // Depois do bloqueio, um strike isolado não bloqueia de novo.
        assertThat(guard.recordAttack("a", 0.60)).isFalse();
    }

    @Test
    void os_strikes_expiram_com_a_mesma_janela() {
        guard.recordAttack("a", 0.60);
        guard.recordAttack("a", 0.60);

        clock.now = clock.now.plus(Duration.ofHours(13));

        assertThat(guard.recordAttack("a", 0.60)).isFalse();
    }

    @Test
    void cada_visitante_tem_o_seu_estado() {
        guard.recordAttack("a", 0.99);

        assertThat(guard.blockedFor("a")).isPresent();
        assertThat(guard.blockedFor("b")).isEmpty();
    }

    @Test
    void com_zero_horas_o_bloqueio_esta_desligado() {
        var off = new AbuseGuard(clock, Duration.ZERO, 0.90, 3);

        assertThat(off.enabled()).isFalse();
        assertThat(off.recordAttack("a", 1.0)).isFalse();
        assertThat(off.blockedFor("a")).isEmpty();
    }

    @Test
    void o_mapa_nao_cresce_sem_limite() {
        for (int i = 0; i < 25_000; i++) {
            guard.recordAttack("ip-" + i, 0.60);
        }
        assertThat(guard.blockedFor("ip-1")).isEmpty();
    }

    @Test
    void lotado_de_ips_diferentes_nunca_solta_um_bloqueio_ativo() {
        var clock = new MutableClock();
        var guard = new AbuseGuard(clock, Duration.ofHours(12), 0.90, 3, 10);
        assertThat(guard.recordAttack("atacante", 0.99)).isTrue();
        assertThat(guard.blockedFor("atacante")).isPresent();

        // Uma enxurrada de visitantes com 1 strike cada enche o mapa muito além do teto.
        for (int i = 0; i < 100; i++) {
            clock.now = clock.now.plusSeconds(1);
            guard.recordAttack("curioso-" + i, 0.6);
        }

        // Antes isto era clear(): o atacante de 12 h saía livre. Agora o bloqueio continua de pé.
        assertThat(guard.blockedFor("atacante")).as("o bloqueio ativo sobreviveu ao transbordo").isPresent();
        assertThat(guard.blockedCount()).isEqualTo(1);
    }

    @Test
    void lotado_so_de_bloqueios_ativos_o_visitante_novo_nao_derruba_ninguem() {
        var clock = new MutableClock();
        var guard = new AbuseGuard(clock, Duration.ofHours(12), 0.90, 3, 3);
        for (int i = 0; i < 3; i++) {
            assertThat(guard.recordAttack("preso-" + i, 0.99)).isTrue();
        }

        assertThat(guard.recordAttack("novo", 0.99)).as("sem espaço, o novo não entra").isFalse();

        assertThat(guard.blockedCount()).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            assertThat(guard.blockedFor("preso-" + i)).isPresent();
        }
    }
}
