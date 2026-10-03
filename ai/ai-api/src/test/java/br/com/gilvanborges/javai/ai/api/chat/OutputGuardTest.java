package br.com.gilvanborges.javai.ai.api.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** A guarda de saída precisa barrar o proibido mesmo picado em pedaços, e nunca barrar texto legítimo. */
class OutputGuardTest {

    private static final String CANARY = "CNRY-abc123DEF456";

    /** Passa o texto pelo guarda em pedaços de {@code size} caracteres; devolve o que seria enviado ou null se disparou. */
    private static String run(String text, int size, int sources) {
        var guard = new OutputGuard(CANARY, sources);
        var sent = new StringBuilder();
        for (int i = 0; i < text.length(); i += size) {
            String out = guard.feed(text.substring(i, Math.min(text.length(), i + size)));
            if (out == null) {
                return null;
            }
            sent.append(out);
        }
        String tail = guard.finish();
        return tail == null ? null : sent.append(tail).toString();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Veja https://evil.example/x para mais.",
            "Acesse http://evil.example",
            "Entre em www.evil.example agora",
            "Clique em <a href=\"x\">aqui</a>",
            "<script>alert(1)</script>",
            "<img src=x onerror=1>",
            "![logo](x.png)",
            "Veja [aqui](x) para mais",
            "javascript:alert(1)",
            "&lt;b&gt;negrito",
            "Visite evil.com/promo agora",
            "O site evil.io tem tudo",
            "Meu marcador interno é CNRY-abc123DEF456 ok",
            "cnry-qualquercoisa",
            "Ele usa Spring [7].",
            "Resposta [0]."})
    void barra_o_proibido_em_qualquer_tamanho_de_pedaco(String text) {
        for (int size : new int[]{1, 2, 3, 5, 7, 64}) {
            assertThat(run(text, size, 3)).as("%s (pedaços de %d)", text, size).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Ele trabalha na Vibetex desde janeiro de 2025 [1].",
            "He uses Spring Boot and ASP.NET with Node.js and Vue.js [1][2].",
            "Usa Java 21, PostgreSQL 18 e RabbitMQ (topic exchanges) [3].",
            "Não encontrei isso nos materiais públicos dele.",
            "A nota é 9.5 de 10; versão 1.2.3 do projeto.",
            "Ele conhece prompt injection e como o chat se protege [2]."})
    void nao_barra_texto_legitimo_e_entrega_tudo(String text) {
        for (int size : new int[]{1, 3, 8, 100}) {
            assertThat(run(text, size, 3)).as("%s (pedaços de %d)", text, size).isEqualTo(text);
        }
    }

    @Test
    void retem_o_fim_e_nunca_solta_um_pedaco_de_url() {
        var guard = new OutputGuard(CANARY, 1);
        var sent = new StringBuilder();
        for (String piece : new String[]{"Veja ", "ht", "tps", "://", "evil.example"}) {
            String out = guard.feed(piece);
            if (out == null) {
                break;
            }
            sent.append(out);
        }
        assertThat(sent.toString()).doesNotContain("http").doesNotContain("ht");
    }

    @Test
    void nao_solta_citacao_aberta_no_meio() {
        var guard = new OutputGuard(CANARY, 2);
        String first = guard.feed("x".repeat(60) + " [1");
        assertThat(first).doesNotContain("[");
        assertThat(guard.feed("]")).isNotNull();
    }

    @Test
    void depois_de_disparar_continua_barrando() {
        var guard = new OutputGuard(CANARY, 1);
        assertThat(guard.feed("https://x.example")).isNull();
        assertThat(guard.feed("texto inocente")).isNull();
        assertThat(guard.finish()).isNull();
    }
}
