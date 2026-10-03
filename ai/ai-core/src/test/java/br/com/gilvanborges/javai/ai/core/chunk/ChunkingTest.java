package br.com.gilvanborges.javai.ai.core.chunk;

import br.com.gilvanborges.javai.ai.core.snapshot.SnapshotData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ChunkingTest {

    @Test
    void divide_em_paragrafos_sem_passar_do_limite() {
        String text = ("a".repeat(500) + "\n\n" + "b".repeat(500) + "\n\n" + "c".repeat(500));

        var parts = TextSplitter.split(text, 1100);

        assertThat(parts).hasSize(2).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(1100));
        assertThat(String.join("", parts).replace("\n", "")).isEqualTo("a".repeat(500) + "b".repeat(500) + "c".repeat(500));
    }

    @Test
    void junta_paragrafos_pequenos_em_um_trecho_so() {
        assertThat(TextSplitter.split("um\n\ndois\n\ntres", 1000)).containsExactly("um\n\ndois\n\ntres");
    }

    @Test
    void quebra_listas_longas_por_linha_e_nunca_no_meio_de_uma_palavra() {
        String bullets = "- primeiro item da lista\n".repeat(80);

        var parts = TextSplitter.split(bullets, 300);

        assertThat(parts.size()).isGreaterThan(1);
        assertThat(parts).allSatisfy(p -> {
            assertThat(p.length()).isLessThanOrEqualTo(300);
            assertThat(p.lines()).allSatisfy(line -> assertThat(line).isEqualTo("- primeiro item da lista"));
        });
    }

    @Test
    void linha_gigante_e_quebrada_por_palavras() {
        String line = "palavra ".repeat(200).strip();

        assertThat(TextSplitter.split(line, 200)).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(200));
    }

    @Test
    void texto_vazio_nao_gera_pedaco_e_limite_minimo_e_exigido() {
        assertThat(TextSplitter.split("  \n ", 500)).isEmpty();
        assertThat(TextSplitter.split(null, 500)).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> TextSplitter.split("x", 10));
    }

    @Test
    void secoes_do_readme_ignoram_cerquilha_dentro_de_codigo() {
        String md = "# Projeto\nIntro\n## Como rodar\n```bash\n# isto é comentário, não título\nmvn verify\n```\n## Stack\nJava 21";

        var sections = MarkdownSections.split(md);

        assertThat(sections).extracting(MarkdownSections.Section::heading).containsExactly("Projeto", "Como rodar", "Stack");
        assertThat(sections.get(1).text()).contains("# isto é comentário");
    }

    @Test
    void titulos_ficam_em_terceira_pessoa_nos_dois_idiomas() {
        var pt = SnapshotChunker.chunk(snapshot("pt"));
        var en = SnapshotChunker.chunk(snapshot("en"));

        assertThat(pt).extracting(Chunk::title).contains(
                "Perfil de Gilvan Borges",
                "Experiência de Gilvan Borges na Vibetex",
                "Projeto apiSuporte de Gilvan Borges",
                "Habilidade de Gilvan Borges: RabbitMQ");
        assertThat(en).extracting(Chunk::title).contains(
                "Profile of Gilvan Borges",
                "Gilvan Borges' experience at Vibetex",
                "Gilvan Borges' project apiSuporte");
        assertThat(pt).allSatisfy(c -> assertThat(c.lang()).isEqualTo("pt"));
    }

    @Test
    void fontes_apontam_para_links_do_proprio_dono() {
        var chunks = SnapshotChunker.chunk(snapshot("pt"));

        assertThat(chunks).allSatisfy(c -> assertThat(c.url()).matches("https://(gilvanborges\\.com\\.br|github\\.com/).*"));
        var project = chunks.stream().filter(c -> c.sourceRef().equals("project:api-suporte")).findFirst().orElseThrow();
        assertThat(project.url()).isEqualTo("https://github.com/FightConnect2025/apiSuporte");
    }

    @Test
    void campo_vazio_nao_gera_trecho_e_texto_longo_vira_partes_numeradas() {
        var data = snapshot("pt");
        var longSolution = "- item de solução com bastante texto\n".repeat(120);
        var project = new SnapshotData.Project("api-suporte", "apiSuporte", "Resumo.", "Contexto.", longSolution, null,
                List.of("Java"), "FightConnect2025/apiSuporte", null);
        var withLong = new SnapshotData("pt", data.profile(), List.of(), List.of(project), List.of(), List.of(), List.of(), List.of());

        var chunks = SnapshotChunker.chunk(withLong);

        assertThat(chunks).noneMatch(c -> c.sourceRef().endsWith(":results")); // results é nulo
        var solution = chunks.stream().filter(c -> c.sourceRef().endsWith(":solution")).toList();
        assertThat(solution.size()).isGreaterThan(1);
        assertThat(solution.getFirst().title()).endsWith("(1/" + solution.size() + ")");
        assertThat(chunks).extracting(Chunk::id).doesNotHaveDuplicates();
    }

    private static SnapshotData snapshot(String lang) {
        boolean pt = lang.equals("pt");
        return new SnapshotData(lang,
                new SnapshotData.Profile("Gilvan Borges", pt ? "Engenheiro de Software" : "Software Engineer",
                        pt ? "Resumo em português." : "Summary in English.", "Rio de Janeiro", null),
                List.of(new SnapshotData.Experience("Vibetex", "Software Engineer", "2025-01-01", null,
                        pt ? "Sistemas ponta a ponta." : "End-to-end systems.", List.of("Deploy"), List.of("Java 21"))),
                List.of(new SnapshotData.Project("api-suporte", "apiSuporte", "Tickets.", "Contexto.", "Solução.", "Resultado.",
                        List.of("Java 21", "RabbitMQ"), "FightConnect2025/apiSuporte", null)),
                List.of(new SnapshotData.Skill("RabbitMQ", "mensageria", true, "Topic exchanges e DLQ.")),
                List.of(new SnapshotData.Education("COTI Informática", "Java Web Developer", "2024-01-01", "2024-12-31", "concluido")),
                List.of(new SnapshotData.Faq("Qual a stack?", "Java e Spring Boot.")),
                List.of(new SnapshotData.Repository("FightConnect2025/apiSuporte", "destaque", "Exemplo público.")));
    }
}
