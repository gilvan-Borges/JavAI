package br.com.gilvanborges.javai.ai.api.chat;

import br.com.gilvanborges.javai.ai.api.index.ActiveIndex;
import br.com.gilvanborges.javai.ai.core.chunk.Chunk;
import br.com.gilvanborges.javai.ai.core.chunk.SourceKind;
import br.com.gilvanborges.javai.ai.core.search.ScalarVectorOps;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatResumeAnchorTest {

    private static Chunk chunk(String id, SourceKind kind, String lang, String title, String body) {
        return new Chunk(id, kind, "ref:" + id, lang, title, body, "https://gilvanborges.com.br/Gilvan_Borges_CV.pdf");
    }

    private static final Chunk CV_1 = chunk("pt:cv#0", SourceKind.DOCUMENT, "pt", "Currículo (CV, resume) (1/3)", "RESUMO PROFISSIONAL");
    private static final Chunk CV_2 = chunk("pt:cv#1", SourceKind.DOCUMENT, "pt", "Currículo (CV, resume) (2/3)", "EXPERIÊNCIA PROFISSIONAL");
    private static final Chunk CV_3 = chunk("en:cv#2", SourceKind.DOCUMENT, "en", "Currículo (CV, resume) (3/3)", "Backend: Java, Spring Boot");
    private static final Chunk LINKEDIN = chunk("pt:li", SourceKind.DOCUMENT, "pt", "LinkedIn", "Perfil do LinkedIn");
    private static final Chunk EXPERIENCE = chunk("pt:exp", SourceKind.EXPERIENCE, "pt", "Experiência de Gilvan Borges na Vibetex", "Vibetex");

    private static ActiveIndex index() {
        return new ActiveIndex(1, "m", List.of(CV_1, CV_2, CV_3, LINKEDIN, EXPERIENCE), null, List.of(), new ScalarVectorOps());
    }

    @Test
    void pedido_de_resumo_do_curriculo_recebe_o_curriculo_inteiro_na_frente() {
        var sources = ChatService.withResume(List.of(EXPERIENCE, CV_2), index(), "Me faz um resumo do currículo dele");

        assertThat(sources).extracting(Chunk::id).containsExactly("pt:cv#0", "pt:cv#1", "en:cv#2", "pt:exp");
    }

    @Test
    void outras_perguntas_nao_mudam_as_fontes() {
        var hits = List.of(EXPERIENCE);

        assertThat(ChatService.withResume(hits, index(), "Onde o Gilvan trabalha hoje?")).isSameAs(hits);
    }

    @Test
    void sem_curriculo_no_indice_segue_com_a_busca() {
        var semCv = new ActiveIndex(1, "m", List.of(EXPERIENCE), null, List.of(), new ScalarVectorOps());
        var hits = List.of(EXPERIENCE);

        assertThat(ChatService.withResume(hits, semCv, "resumo do currículo")).isSameAs(hits);
    }

    @Test
    void reconhece_os_jeitos_de_pedir_o_curriculo() {
        assertThat(ResumeIntent.matches("Me faz um resumo do currículo")).isTrue();
        assertThat(ResumeIntent.matches("resume o curiculo dele")).isTrue();
        assertThat(ResumeIntent.matches("posso ver o CV?")).isTrue();
        assertThat(ResumeIntent.matches("Can you summarize his resume?")).isTrue();
        assertThat(ResumeIntent.matches("Curriculum vitae, please")).isTrue();
    }

    @Test
    void resume_como_verbo_nao_e_curriculo() {
        assertThat(ResumeIntent.matches("me resume os projetos dele")).isFalse();
        assertThat(ResumeIntent.matches("resume a experiência na Vibetex")).isFalse();
        assertThat(ResumeIntent.matches("qual o último commit?")).isFalse();
        assertThat(ResumeIntent.matches(null)).isFalse();
    }

    @Test
    void o_prompt_pede_resumo_completo_do_curriculo() {
        var prompt = PromptBuilder.build("resumo do currículo", "pt", List.of(CV_1), List.of(), "CNRY-x");

        assertThat(prompt.system()).contains("summarize the resume").contains("up to 6 short sentences");
    }
}
