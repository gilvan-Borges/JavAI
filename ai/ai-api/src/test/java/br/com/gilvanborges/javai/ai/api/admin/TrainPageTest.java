package br.com.gilvanborges.javai.ai.api.admin;

import br.com.gilvanborges.javai.ai.core.chunk.Chunk;
import br.com.gilvanborges.javai.ai.core.chunk.SourceKind;
import br.com.gilvanborges.javai.ai.core.search.HybridSearcher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A tela Treinar: o que ela mostra do pipeline e, principalmente, que nada vindo do modelo ou da pergunta vira HTML. */
class TrainPageTest {

    private static final String XSS = "<script>alert(1)</script>";

    private static TrainRun.Collector played(String... deltas) {
        var c = new TrainRun.Collector();
        c.send("trace", Map.of("step", "classify", "category", "codigo", "ms", 180));
        c.send("meta", Map.of("turnId", "t", "model", "forte/a"));
        for (String d : deltas) {
            c.send("delta", Map.of("text", d));
        }
        c.send("sources", List.of(Map.of("ref", 1, "title", "README " + XSS, "url", "https://github.com/x/y")));
        c.send("done", Map.of("turn", "x", "usage", Map.of("model", "forte/a", "latencyMs", 1200, "costUsd", "0.004")));
        return c;
    }

    private static List<HybridSearcher.Hit> hits() {
        var chunk = new Chunk("pt:1", SourceKind.PROJECT, "project:x", "pt", "Projeto " + XSS, "corpo " + XSS, "https://gilvanborges.com.br/");
        return List.of(new HybridSearcher.Hit(chunk, 0.0321));
    }

    @Test
    void o_coletor_junta_os_eventos_do_pipeline_na_ordem() {
        var run = played("Ele usa ", "RabbitMQ [1].").finish("q", "pt", hits(), true);

        assertThat(run.answer()).isEqualTo("Ele usa RabbitMQ [1].");
        assertThat(run.sources()).hasSize(1);
        assertThat(run.traces()).extracting(t -> t.get("step")).containsExactly("classify");
        assertThat(run.usage()).containsEntry("model", "forte/a");
        assertThat(run.retracted()).isFalse();
    }

    @Test
    void resposta_retratada_pela_guarda_nao_mostra_o_texto() {
        var c = played("texto que vazou");
        c.send("retract", Map.of("text", "x"));

        var run = c.finish("q", "pt", hits(), true);

        assertThat(run.answer()).isEmpty();
        assertThat(AdminPages.train("q", "pt", run, "", "")).contains("retratou").doesNotContain("texto que vazou");
    }

    @Test
    void nada_vindo_do_modelo_da_pergunta_ou_do_indice_vira_html() {
        var run = played("Resposta com " + XSS + " dentro [1].").finish(XSS, "pt", hits(), true);

        String html = AdminPages.train(XSS, "pt", run, "https://admin.exemplo.com", "");

        assertThat(html).doesNotContain(XSS).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @Test
    void o_botao_corrigir_leva_ao_crud_com_a_pergunta_codificada_e_so_existe_com_crud_configurado() {
        var run = played("ok [1].").finish("Ele já usou RabbitMQ & Kafka?", "pt", hits(), true);

        String with = AdminPages.train(run.question(), "pt", run, "https://admin.exemplo.com", "");
        String without = AdminPages.train(run.question(), "pt", run, "", "");

        assertThat(with).contains("https://admin.exemplo.com/admin/faqs?q=Ele+j%C3%A1+usou+RabbitMQ+%26+Kafka%3F&amp;lang=pt")
                .contains("<button type=\"button\">Corrigir no CRUD</button>");
        assertThat(without).doesNotContain("/admin/faqs?q=");
    }

    @Test
    void sem_teste_ainda_mostra_so_o_formulario_e_o_motivo_quando_nao_rodou() {
        assertThat(AdminPages.train("", "pt", null, "", "")).contains("Perguntar").doesNotContain("Resposta<");
        assertThat(AdminPages.train("q", "pt", null, "", "O orçamento diário está esgotado")).contains("orçamento diário");
    }

    @Test
    void recusa_e_erro_aparecem_com_o_motivo() {
        var refused = new TrainRun.Collector();
        refused.send("refusal", Map.of("reason", "attack", "text", "Não posso ajudar com isso."));
        var failed = new TrainRun.Collector();
        failed.send("error", Map.of("message", "Falha temporária."));

        assertThat(AdminPages.train("q", "pt", refused.finish("q", "pt", List.of(), false), "", "")).contains("recusou").contains("Não posso ajudar");
        assertThat(AdminPages.train("q", "pt", failed.finish("q", "pt", List.of(), false), "", "")).contains("Falha temporária.");
    }
}
