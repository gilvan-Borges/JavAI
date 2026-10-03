package br.com.gilvanborges.javai.ai.api.mcp;

import br.com.gilvanborges.javai.ai.api.AiApiProperties;
import br.com.gilvanborges.javai.ai.api.ApiFixtures;
import br.com.gilvanborges.javai.ai.api.index.QueryEmbedder;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolsTest {

    private static final AiApiProperties PROPS = new AiApiProperties(List.of("https://gilvanborges.com.br"), 60,
            Duration.ofSeconds(30), "/nao-existe", 1, "https://gilvanborges.com.br");

    private List<SyncToolSpecification> tools;

    @BeforeEach
    void monta() {
        tools = new McpTools(ApiFixtures.holder(ApiFixtures.index()), new QueryEmbedder(PROPS), PROPS).specifications();
    }

    private CallToolResult call(String name, Map<String, Object> args) {
        var spec = tools.stream().filter(t -> t.tool().name().equals(name)).findFirst().orElseThrow();
        return spec.callHandler().apply(null, CallToolRequest.builder().name(name).arguments(args).build());
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    @Test
    void as_nove_ferramentas_sao_so_de_leitura_e_declaram_a_saida() {
        assertThat(tools).extracting(t -> t.tool().name()).containsExactly("get_profile", "list_projects",
                "get_project", "list_experience", "get_skills", "find_evidence", "list_repositories", "get_repository", "search");
        for (var spec : tools) {
            assertThat(spec.tool().annotations().readOnlyHint()).as(spec.tool().name()).isTrue();
            assertThat(spec.tool().annotations().destructiveHint()).as(spec.tool().name()).isFalse();
            assertThat(spec.tool().outputSchema()).as(spec.tool().name()).isNotEmpty();
            assertThat(spec.tool().inputSchema()).as(spec.tool().name()).containsEntry("additionalProperties", false);
        }
    }

    @Test
    void get_profile_traz_o_perfil_no_idioma_pedido_e_os_links() {
        var en = call("get_profile", Map.of());
        assertThat(en.isError()).isFalse();
        assertThat(text(en)).contains("Summary in English.").contains("https://github.com/gilvan-Borges");

        var pt = call("get_profile", Map.of("lang", "pt"));
        assertThat(text(pt)).contains("Resumo em português.");
    }

    @Test
    void idioma_invalido_e_erro_de_entrada() {
        var result = call("get_profile", Map.of("lang", "fr"));
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("lang");
    }

    @Test
    void list_projects_mostra_slug_e_stack_sem_as_partes_internas() {
        var result = call("list_projects", Map.of());
        assertThat(structured(result)).containsEntry("count", 1);
        @SuppressWarnings("unchecked")
        var project = ((List<Map<String, Object>>) structured(result).get("projects")).get(0);
        assertThat(project).containsEntry("slug", "api-suporte");
        assertThat(project.get("stack")).isEqualTo(List.of("Java 21", "RabbitMQ"));
        assertThat(project.get("summary").toString()).doesNotContain("Stack:");
    }

    @Test
    void get_project_junta_as_partes_e_slug_desconhecido_lista_os_validos() {
        var ok = call("get_project", Map.of("slug", "api-suporte"));
        assertThat(ok.isError()).isFalse();
        assertThat(text(ok)).contains("Publishes events to RabbitMQ.").contains("Resultado.");

        var bad = call("get_project", Map.of("slug", "nao-existe"));
        assertThat(bad.isError()).isTrue();
        assertThat(text(bad)).contains("api-suporte");

        assertThat(call("get_project", Map.of("slug", "../../etc/passwd")).isError()).isTrue();
        assertThat(call("get_project", Map.of()).isError()).isTrue();
    }

    @Test
    void list_experience_na_ordem_do_cv() {
        var result = call("list_experience", Map.of("lang", "pt"));
        assertThat(structured(result)).containsEntry("count", 1);
        assertThat(text(result)).contains("Vibetex");
    }

    @Test
    void get_skills_conta_as_evidencias_e_filtra_sem_acento_nem_caixa() {
        var all = call("get_skills", Map.of());
        assertThat(structured(all)).containsEntry("count", 2);

        var filtered = call("get_skills", Map.of("skill", "rabbit"));
        assertThat(structured(filtered)).containsEntry("count", 1);
        assertThat(text(filtered)).contains("RabbitMQ (2 places");

        assertThat(call("get_skills", Map.of("skill", "x")).isError()).isTrue();
    }

    @Test
    void find_evidence_acha_com_link_e_diz_que_ausencia_nao_e_desconhecimento() {
        var found = call("find_evidence", Map.of("technology", "RabbitMQ"));
        assertThat(structured(found)).containsEntry("found", true).containsEntry("total", 2);
        assertThat(text(found)).contains("Publisher.java:42").contains("#L42");

        var none = call("find_evidence", Map.of("technology", "Kafka"));
        assertThat(none.isError()).isFalse();
        assertThat(structured(none)).containsEntry("found", false);
        assertThat(text(none)).contains("does not mean he does not know it");
    }

    @Test
    void find_evidence_valida_o_tamanho() {
        assertThat(call("find_evidence", Map.of("technology", "a")).isError()).isTrue();
        assertThat(call("find_evidence", Map.of("technology", "x".repeat(81))).isError()).isTrue();
        assertThat(call("find_evidence", Map.of()).isError()).isTrue();
    }

    @Test
    void repositorios_so_os_indexados() {
        var list = call("list_repositories", Map.of());
        assertThat(structured(list)).containsEntry("count", 1);
        assertThat(text(list)).contains("FightConnect2025/apiSuporte");

        var repo = call("get_repository", Map.of("full_name", "FightConnect2025/apiSuporte"));
        assertThat(repo.isError()).isFalse();
        assertThat(text(repo)).contains("RabbitMQ").contains("Support API");

        assertThat(call("get_repository", Map.of("full_name", "outro/repo")).isError()).isTrue();
        assertThat(call("get_repository", Map.of("full_name", "nao e um nome")).isError()).isTrue();
    }

    @Test
    void search_acha_o_trecho_e_cai_para_bm25_sem_modelo() {
        var result = call("search", Map.of("query", "RabbitMQ events"));
        assertThat(result.isError()).isFalse();
        assertThat(structured(result)).containsEntry("mode", "bm25");
        assertThat(text(result)).contains("RabbitMQ").contains("https://");
        @SuppressWarnings("unchecked")
        var results = (List<Object>) structured(result).get("results");
        assertThat(results).hasSizeLessThanOrEqualTo(McpTools.SEARCH_LIMIT);
    }

    @Test
    void search_valida_e_limpa_a_pergunta() {
        assertThat(call("search", Map.of("query", "a")).isError()).isTrue();
        assertThat(call("search", Map.of("query", "x".repeat(201))).isError()).isTrue();
        assertThat(call("search", Map.of("query", "​\u0000 ⁠")).isError()).isTrue();
        assertThat(McpTools.cleanQuery("  Olá​   mundo\u0007 ")).isEqualTo("Olá mundo");
    }

    @Test
    void sem_indice_as_ferramentas_avisam_em_vez_de_falhar() {
        var empty = new McpTools(ApiFixtures.holder(null), new QueryEmbedder(PROPS), PROPS).specifications();
        var result = empty.get(0).callHandler().apply(null, CallToolRequest.builder().name("get_profile").arguments(Map.of()).build());
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("not available yet");
    }
}
