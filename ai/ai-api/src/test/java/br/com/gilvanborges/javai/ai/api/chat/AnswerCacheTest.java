package br.com.gilvanborges.javai.ai.api.chat;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerCacheTest {

    private Instant now = Instant.parse("2026-10-03T15:00:00Z");
    private final Clock clock = new Clock() {
        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    };
    private final AnswerCache cache = new AnswerCache(clock);
    private final AnswerCache.Entry entry = new AnswerCache.Entry("Resposta [1].", List.of(), "m/a", "perfil", "rapido");

    @Test
    void a_chave_normaliza_espacos_e_maiusculas_mas_separa_idioma_e_versao_do_indice() {
        String key = cache.key("Onde ele trabalha?", "pt", 7);

        assertThat(cache.key("  onde   ELE trabalha? ", "pt", 7)).isEqualTo(key);
        assertThat(cache.key("Onde ele trabalha?", "en", 7)).isNotEqualTo(key);
        assertThat(cache.key("Onde ele trabalha?", "pt", 8)).isNotEqualTo(key);
        assertThat(cache.key("Onde ele mora?", "pt", 7)).isNotEqualTo(key);
    }

    @Test
    void guarda_devolve_e_expira_pelo_relogio() {
        String key = cache.key("q", "pt", 1);
        cache.put(key, entry);

        assertThat(cache.get(key)).contains(entry);

        now = now.plus(Duration.ofHours(6)).plusSeconds(1);
        assertThat(cache.get(key)).isEmpty();
        assertThat(cache.size()).isZero();
    }

    @Test
    void o_tamanho_e_limitado_descartando_o_menos_usado() {
        for (int i = 0; i < 300; i++) {
            cache.put(cache.key("q" + i, "pt", 1), entry);
        }

        assertThat(cache.size()).isEqualTo(256);
        assertThat(cache.get(cache.key("q299", "pt", 1))).isPresent();
        assertThat(cache.get(cache.key("q0", "pt", 1))).isEmpty();
    }
}
