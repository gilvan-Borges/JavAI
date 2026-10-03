package br.com.gilvanborges.javai.ai.embeddings;

import br.com.gilvanborges.javai.ai.core.chunk.Chunk;
import br.com.gilvanborges.javai.ai.core.chunk.SnapshotChunker;
import br.com.gilvanborges.javai.ai.core.eval.SearchMetrics;
import br.com.gilvanborges.javai.ai.core.search.Bm25Index;
import br.com.gilvanborges.javai.ai.core.search.ReciprocalRankFusion;
import br.com.gilvanborges.javai.ai.core.search.ScoredDoc;
import br.com.gilvanborges.javai.ai.core.search.VectorIndex;
import br.com.gilvanborges.javai.ai.core.search.VectorOps;
import br.com.gilvanborges.javai.ai.core.snapshot.SnapshotData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Avaliação da busca (spec 05): mede, com o conteúdo REAL publicado, quanto a busca acha o trecho certo em cada modo
 * (só BM25, só vetorial e híbrida). Serve para decidir com número, e não por impressão, se a busca híbrida vale a
 * pena. Precisa do modelo (JAVAI_MODEL_DIR) e de uma cópia do snapshot publicado em JAVAI_EVAL_SNAPSHOT_DIR
 * (pt.json e en.json, baixados de conteudo.gilvanborges.com.br).
 */
@EnabledIfEnvironmentVariable(named = "JAVAI_MODEL_DIR", matches = ".+")
@EnabledIfEnvironmentVariable(named = "JAVAI_EVAL_SNAPSHOT_DIR", matches = ".+")
class SearchEvaluationTest {

    private record Question(String lang, String pergunta, List<String> esperado) {
    }

    private static final int TOP = 5;

    @Test
    void compara_os_modos_de_busca_nas_perguntas_de_avaliacao() throws Exception {
        var json = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        Path snapshotDir = Path.of(System.getenv("JAVAI_EVAL_SNAPSHOT_DIR"));
        List<Question> questions = json.readValue(
                getClass().getResourceAsStream("/eval/perguntas.json").readAllBytes(), new TypeReference<>() { });

        var results = new java.util.LinkedHashMap<String, List<SearchMetrics.Outcome>>();
        for (String mode : List.of("BM25", "vetorial", "híbrida")) {
            results.put(mode, new ArrayList<>());
        }

        try (var embedder = new OnnxEmbedder(Path.of(System.getenv("JAVAI_MODEL_DIR")), 2)) {
            for (String lang : List.of("pt", "en")) {
                var snapshot = json.readValue(Files.readAllBytes(snapshotDir.resolve(lang + ".json")), SnapshotData.class);
                List<Chunk> chunks = SnapshotChunker.chunk(snapshot);
                List<float[]> vectors = chunks.stream().map(c -> embedder.embedPassage(c.searchText())).toList();
                var bm25 = new Bm25Index(chunks.stream().map(Chunk::searchText).toList());
                var vector = new VectorIndex(vectors, VectorOps.best());

                for (Question q : questions.stream().filter(x -> x.lang().equals(lang)).toList()) {
                    var byBm25 = bm25.search(q.pergunta(), TOP * 4);
                    var byVector = vector.search(embedder.embedQuery(q.pergunta()), TOP * 4);
                    var hybrid = ReciprocalRankFusion.fuse(List.of(byBm25, byVector), ReciprocalRankFusion.DEFAULT_K, TOP * 4);
                    results.get("BM25").add(outcome(q, chunks, byBm25));
                    results.get("vetorial").add(outcome(q, chunks, byVector));
                    results.get("híbrida").add(outcome(q, chunks, hybrid));
                }
            }
        }

        System.out.println("AVALIAÇÃO DA BUSCA (" + questions.size() + " perguntas)");
        results.forEach((mode, outcomes) -> System.out.printf("  %-9s hit@1 %.0f%% | hit@%d %.0f%% | MRR %.3f%n", mode,
                SearchMetrics.hitAt(outcomes, 1) * 100, TOP, SearchMetrics.hitAt(outcomes, TOP) * 100, SearchMetrics.mrr(outcomes)));
        System.out.println("  perguntas que a híbrida não acha entre os " + TOP + " primeiros:");
        results.get("híbrida").stream().filter(o -> o.rank() == 0 || o.rank() > TOP).forEach(o -> System.out.println("    - " + o.question()));

        // Piso baixo e honesto: o relatório acima é o que importa; isto só pega uma regressão grande.
        assertThat(SearchMetrics.hitAt(results.get("híbrida"), TOP)).isGreaterThanOrEqualTo(0.7);
    }

    /** A posição do primeiro trecho cujo sourceRef começa com algum dos esperados. */
    private static SearchMetrics.Outcome outcome(Question q, List<Chunk> chunks, List<ScoredDoc> ranking) {
        for (int i = 0; i < ranking.size(); i++) {
            String ref = chunks.get(ranking.get(i).doc()).sourceRef();
            if (q.esperado().stream().anyMatch(ref::startsWith)) {
                return new SearchMetrics.Outcome(q.pergunta(), i + 1);
            }
        }
        return new SearchMetrics.Outcome(q.pergunta(), 0);
    }
}
