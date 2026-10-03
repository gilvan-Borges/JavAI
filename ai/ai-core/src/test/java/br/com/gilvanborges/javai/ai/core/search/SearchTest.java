package br.com.gilvanborges.javai.ai.core.search;

import br.com.gilvanborges.javai.ai.core.chunk.Chunk;
import br.com.gilvanborges.javai.ai.core.chunk.SourceKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SearchTest {

    private static final List<String> DOCS = List.of(
            "Gilvan trabalha na Vibetex com Java 21 e Spring Boot em microsserviços",
            "O apiSuporte usa RabbitMQ para publicar eventos de tickets",
            "Formação em Análise e Desenvolvimento de Sistemas na UniFatecie",
            "Aplicativo Flutter offline-first com fila local e sincronização");

    @Test
    void bm25_acha_o_documento_que_tem_o_termo_raro() {
        var hits = new Bm25Index(DOCS).search("RabbitMQ", 3);

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().doc()).isEqualTo(1);
    }

    @Test
    void bm25_ordena_pela_relevancia_e_ignora_acento() {
        var hits = new Bm25Index(DOCS).search("sincronizacao offline", 3);

        assertThat(hits.getFirst().doc()).isEqualTo(3);
        assertThat(hits).isSortedAccordingTo((a, b) -> Double.compare(b.score(), a.score()));
    }

    @Test
    void bm25_sem_termo_em_comum_nao_devolve_nada() {
        assertThat(new Bm25Index(DOCS).search("kubernetes", 5)).isEmpty();
        assertThat(new Bm25Index(List.of()).search("java", 5)).isEmpty();
    }

    @Test
    void termo_repetido_na_pergunta_nao_pesa_em_dobro() {
        var index = new Bm25Index(DOCS);

        assertThat(index.search("rabbitmq rabbitmq rabbitmq", 3)).isEqualTo(index.search("rabbitmq", 3));
    }

    @Test
    void rrf_premia_quem_aparece_bem_nas_duas_listas() {
        var bm25 = List.of(new ScoredDoc(7, 9.0), new ScoredDoc(3, 5.0), new ScoredDoc(1, 1.0));
        var vector = List.of(new ScoredDoc(3, 0.9), new ScoredDoc(7, 0.8), new ScoredDoc(5, 0.7));

        var fused = ReciprocalRankFusion.fuse(List.of(bm25, vector), ReciprocalRankFusion.DEFAULT_K, 10);

        // Os documentos 3 e 7 empatam (1/61 + 1/62 cada, em posições trocadas); o empate vai para o menor id.
        assertThat(fused).extracting(ScoredDoc::doc).containsExactly(3, 7, 1, 5);
        assertThat(fused.get(0).score()).isEqualTo(1.0 / 61 + 1.0 / 62, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void rrf_respeita_o_limite() {
        var ranking = List.of(new ScoredDoc(0, 1), new ScoredDoc(1, 1), new ScoredDoc(2, 1));

        assertThat(ReciprocalRankFusion.fuse(List.of(ranking), 60, 2)).hasSize(2);
    }

    @Test
    void simd_e_escalar_dao_o_mesmo_resultado() {
        var scalar = new ScalarVectorOps();
        var best = VectorOps.best();
        var random = new Random(42);
        for (int length : new int[] {1, 7, 8, 16, 100, 384, 1000}) {
            float[] a = random(random, length);
            float[] b = random(random, length);

            assertThat(best.dot(a, b)).isCloseTo(scalar.dot(a, b), org.assertj.core.data.Offset.offset(1e-3f));
        }
        System.out.println("VectorOps escolhida: " + best.name());
    }

    @Test
    void produto_escalar_exige_vetores_do_mesmo_tamanho() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ScalarVectorOps().dot(new float[2], new float[3]));
        assertThatIllegalArgumentException().isThrownBy(() -> VectorOps.best().dot(new float[2], new float[3]));
    }

    @Test
    void indice_vetorial_devolve_o_mais_parecido() {
        var index = new VectorIndex(List.of(new float[] {1, 0, 0}, new float[] {0, 1, 0}, new float[] {0.9f, 0.1f, 0}), VectorOps.best());

        var hits = index.search(new float[] {2, 0, 0}, 2); // magnitude não importa: o cosseno só olha a direção

        assertThat(hits).extracting(ScoredDoc::doc).containsExactly(0, 2);
        assertThat(hits.getFirst().score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void busca_hibrida_usa_vetor_para_achar_o_que_o_bm25_nao_acha() {
        var chunks = chunks();
        // O vetor do trecho 1 aponta para onde a pergunta aponta, mas as palavras da pergunta não aparecem em nenhum texto.
        var embeddings = List.of(new float[] {1, 0}, new float[] {0, 1}, new float[] {1, 1});

        var hybrid = new HybridSearcher(chunks, embeddings, VectorOps.best());
        var hits = hybrid.search("palavra inexistente", new float[] {0, 1}, null, 3);

        assertThat(hits.getFirst().chunk().id()).isEqualTo("b");
    }

    @Test
    void busca_hibrida_cai_para_bm25_sem_embeddings() {
        var hybrid = new HybridSearcher(chunks(), null, VectorOps.best());

        assertThat(hybrid.hasVectors()).isFalse();
        assertThat(hybrid.search("rabbitmq", null, null, 3)).extracting(h -> h.chunk().id()).containsExactly("b");
    }

    @Test
    void busca_hibrida_filtra_por_idioma() {
        var hybrid = new HybridSearcher(chunks(), null, VectorOps.best());

        assertThat(hybrid.search("java", null, "en", 5)).allSatisfy(h -> assertThat(h.chunk().lang()).isEqualTo("en"));
    }

    @Test
    void embeddings_precisam_de_um_vetor_por_trecho() {
        assertThatIllegalArgumentException().isThrownBy(() -> new HybridSearcher(chunks(), List.of(new float[] {1}), VectorOps.best()));
    }

    private static List<Chunk> chunks() {
        return List.of(
                chunk("a", "pt", "Experiência de Gilvan Borges na Vibetex", "Trabalha com Java 21 e Spring Boot."),
                chunk("b", "pt", "Projeto apiSuporte", "Publica eventos de tickets no RabbitMQ."),
                chunk("c", "en", "Gilvan Borges at Vibetex", "Works with Java 21 and Spring Boot."));
    }

    private static Chunk chunk(String id, String lang, String title, String body) {
        return new Chunk(id, SourceKind.PROJECT, "ref-" + id, lang, title, body, "https://gilvanborges.com.br");
    }

    private static float[] random(Random random, int length) {
        var v = new float[length];
        for (int i = 0; i < length; i++) {
            v[i] = random.nextFloat() * 2 - 1;
        }
        return v;
    }
}
