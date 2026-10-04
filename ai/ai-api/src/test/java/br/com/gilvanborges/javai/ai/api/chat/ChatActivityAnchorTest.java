package br.com.gilvanborges.javai.ai.api.chat;

import br.com.gilvanborges.javai.ai.api.index.ActiveIndex;
import br.com.gilvanborges.javai.ai.core.chunk.Chunk;
import br.com.gilvanborges.javai.ai.core.chunk.SourceKind;
import br.com.gilvanborges.javai.ai.core.search.ScalarVectorOps;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatActivityAnchorTest {

    private static Chunk chunk(String id, SourceKind kind, String ref, String lang, String body) {
        return new Chunk(id, kind, ref, lang, "titulo " + id, body, "https://github.com/dono");
    }

    private static final Chunk ACTIVITY_PT = chunk("pt:activity", SourceKind.GITHUB_PROFILE, "github-activity:dono", "pt",
            "O último commit de Gilvan Borges foi no repositório controlei em 2026-10-04");
    private static final Chunk ACTIVITY_EN = chunk("en:activity", SourceKind.GITHUB_PROFILE, "github-activity:dono", "en",
            "Gilvan Borges' latest commit was in the repository controlei on 2026-10-04");
    private static final Chunk PROFILE_GITHUB = chunk("pt:gh", SourceKind.GITHUB_PROFILE, "github:dono", "pt", "Perfil no GitHub");
    private static final Chunk LOOSE_FACTS = chunk("pt:facts", SourceKind.REPO_README, "repo_readme:dono/formacao-coti:REPO_FACTS.md:", "pt",
            "Último commit: 2026-10-01");

    private static ActiveIndex index() {
        return new ActiveIndex(1, "m", List.of(ACTIVITY_PT, ACTIVITY_EN, PROFILE_GITHUB, LOOSE_FACTS), null, List.of(), new ScalarVectorOps());
    }

    @Test
    void pergunta_de_atividade_recebe_o_documento_agregado_na_frente() {
        var hits = List.of(LOOSE_FACTS);

        var sources = ChatService.withActivity(hits, index(), "pt", "o que pode me falar do ultimo comit que ele fez?");

        assertThat(sources).extracting(Chunk::id).containsExactly("pt:activity", "pt:facts");
    }

    @Test
    void usa_o_idioma_da_pergunta() {
        var sources = ChatService.withActivity(List.of(), index(), "en", "What was his latest commit?");

        assertThat(sources).extracting(Chunk::id).containsExactly("en:activity");
    }

    @Test
    void nao_duplica_quando_a_busca_ja_trouxe_o_documento() {
        var sources = ChatService.withActivity(List.of(ACTIVITY_PT, LOOSE_FACTS), index(), "pt", "qual o último commit dele?");

        assertThat(sources).extracting(Chunk::id).containsExactly("pt:activity", "pt:facts");
    }

    @Test
    void outras_perguntas_nao_mudam_as_fontes() {
        var hits = List.of(LOOSE_FACTS);

        assertThat(ChatService.withActivity(hits, index(), "pt", "Onde o Gilvan trabalha hoje?")).isSameAs(hits);
    }

    @Test
    void sem_o_documento_no_indice_segue_com_a_busca() {
        var semAtividade = new ActiveIndex(1, "m", List.of(PROFILE_GITHUB), null, List.of(), new ScalarVectorOps());
        var hits = List.of(LOOSE_FACTS);

        assertThat(ChatService.withActivity(hits, semAtividade, "pt", "qual o último commit?")).isSameAs(hits);
    }

    @Test
    void o_prompt_proibe_comparar_so_as_fontes_que_viu() {
        var prompt = PromptBuilder.build("qual o último commit?", "pt", List.of(ACTIVITY_PT), List.of(), "CNRY-x");

        assertThat(prompt.system()).contains("superlatives and comparisons").contains("not all of his work");
        assertThat(PromptBuilder.VERSION).isEqualTo("v2");
    }
}
