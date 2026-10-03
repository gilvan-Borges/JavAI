package br.com.gilvanborges.javai.ai.core.source;

import br.com.gilvanborges.javai.ai.core.chunk.Chunk;
import br.com.gilvanborges.javai.ai.core.chunk.SourceKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicSourcesTest {

    @Test
    void html_vira_texto_visivel_sem_script_estilo_nem_comentario() {
        String html = "<html><head><title>x</title><style>.a{}</style></head><body><script>alert(1)</script>"
                + "<!-- segredo --><h1>Gilvan</h1><p>Engenheiro &amp; arquiteto</p><h2>Projetos</h2><ul><li>FightConnect</li><li>Cedae</li></ul></body></html>";

        String text = HtmlText.toText(html);

        assertThat(text).contains("# Gilvan").contains("Engenheiro & arquiteto").contains("## Projetos").contains("FightConnect").contains("Cedae");
        assertThat(text).doesNotContain("alert").doesNotContain("segredo").doesNotContain(".a{}").doesNotContain("<");
    }

    @Test
    void detecta_html_e_nao_confunde_markdown() {
        assertThat(HtmlText.looksLikeHtml("<!doctype html><html></html>")).isTrue();
        assertThat(HtmlText.looksLikeHtml("<div>oi</div>")).isTrue();
        assertThat(HtmlText.looksLikeHtml("# Título\n\nTexto com a < b e c > d.")).isFalse();
    }

    @Test
    void markdown_vira_trechos_por_secao_com_a_fonte_no_titulo_e_o_link() {
        String md = "# Gilvan Borges\n\nEngenheiro de software com foco em arquitetura de sistemas Java.\n\n"
                + "## Experiência\n\nTrabalha na Vibetex desde janeiro de 2025 como engenheiro de software.\n\n"
                + "## Skills\n\nJava, Spring Boot, PostgreSQL, RabbitMQ e Docker em produção.";

        List<Chunk> chunks = PublicTextChunker.chunk(SourceKind.SITE, "Site gilvanborges.com.br", "https://gilvanborges.com.br/llms-full.txt",
                "https://gilvanborges.com.br/llms-full.txt", md);

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(3);
        assertThat(chunks).extracting(Chunk::title).anyMatch(t -> t.equals("Site gilvanborges.com.br: Experiência"));
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.kind()).isEqualTo(SourceKind.SITE);
            assertThat(c.url()).isEqualTo("https://gilvanborges.com.br/llms-full.txt");
            assertThat(c.sourceRef()).startsWith("site:https://gilvanborges.com.br/llms-full.txt");
            assertThat(c.id()).isNotBlank();
        });
        assertThat(chunks.stream().filter(c -> c.title().endsWith("Experiência")).findFirst().orElseThrow().body()).contains("Vibetex");
    }

    @Test
    void texto_sem_titulos_vira_um_trecho_e_trecho_longo_e_dividido_com_ids_unicos() {
        String longText = "Frase sobre arquitetura hexagonal em Java. ".repeat(120);

        List<Chunk> chunks = PublicTextChunker.chunk(SourceKind.DOCUMENT, "Currículo (PDF)", "cv", "https://x.example/cv.pdf", longText);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).extracting(Chunk::id).doesNotHaveDuplicates();
        assertThat(chunks).allSatisfy(c -> assertThat(c.body().length()).isLessThanOrEqualTo(PublicTextChunker.MAX_CHARS + 50));
        assertThat(chunks.get(0).title()).contains("(1/");
    }

    @Test
    void secoes_vazias_ou_minusculas_sao_descartadas() {
        List<Chunk> chunks = PublicTextChunker.chunk(SourceKind.SITE, "Site", "r", "https://x.example/",
                "# Vazio\n\n## Outro\n\nok\n\n## Bom\n\nUm parágrafo de verdade, com conteúdo suficiente.");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).title()).isEqualTo("Site: Bom");
    }

    @Test
    void url_que_nao_e_https_e_recusada() {
        assertThatThrownBy(() -> PublicTextChunker.chunk(SourceKind.SITE, "Site", "r", "http://x.example/", "texto"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fontes_publicas_ignoram_o_idioma_na_busca() {
        assertThat(SourceKind.SITE.isLanguageNeutral()).isTrue();
        assertThat(SourceKind.DOCUMENT.isLanguageNeutral()).isTrue();
        assertThat(SourceKind.GITHUB_PROFILE.isLanguageNeutral()).isTrue();
        assertThat(SourceKind.FAQ.isLanguageNeutral()).isFalse();
        assertThat(SourceKind.fromDb("github_profile")).isEqualTo(SourceKind.GITHUB_PROFILE);
    }
}
