package br.com.gilvanborges.javai.ai.api.admin;

import br.com.gilvanborges.javai.ai.api.chat.Keys;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class AdminSessionTest {

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
    private final Keys keys = new Keys("segredo-do-servico");

    private AdminSession session(String password) {
        return new AdminSession(keys, new AdminProperties("gilvan", password, 8, 5, 15), clock);
    }

    @Test
    void credenciais_certas_passam_e_qualquer_erro_falha() {
        var s = session("senha-bem-longa-123456");

        assertThat(s.credentialsMatch("gilvan", "senha-bem-longa-123456")).isTrue();
        assertThat(s.credentialsMatch("gilvan", "senha-bem-longa-123457")).isFalse();
        assertThat(s.credentialsMatch("Gilvan", "senha-bem-longa-123456")).isFalse();
        assertThat(s.credentialsMatch(null, null)).isFalse();
        assertThat(s.credentialsMatch("", "")).isFalse();
    }

    @Test
    void token_vale_ate_expirar_e_nao_aceita_adulteracao() {
        var s = session("senha-bem-longa-123456");
        String token = s.issue();

        assertThat(s.valid(token)).isTrue();
        clock.now = clock.now.plus(Duration.ofHours(7));
        assertThat(s.valid(token)).isTrue();
        clock.now = clock.now.plus(Duration.ofHours(2));
        assertThat(s.valid(token)).isFalse();

        clock.now = Instant.parse("2026-10-03T15:00:00Z");
        String[] parts = {token.substring(0, token.indexOf((char) 46)), token.substring(token.indexOf((char) 46) + 1)};
        assertThat(s.valid((Long.parseLong(parts[0]) + 99999) + "." + parts[1])).isFalse();
        assertThat(s.valid(parts[0] + ".x")).isFalse();
        assertThat(s.valid(null)).isFalse();
        assertThat(s.valid("sem-ponto")).isFalse();
        assertThat(s.valid("x".repeat(300))).isFalse();
    }

    @Test
    void trocar_a_senha_derruba_as_sessoes_antigas() {
        String token = session("senha-bem-longa-123456").issue();

        assertThat(session("outra-senha-bem-longa-9").valid(token)).isFalse();
    }
}
