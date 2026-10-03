package br.com.gilvanborges.javai.ai.core.eval;

import java.util.List;

/**
 * Métricas de busca (spec 05, "Avaliação da busca"). Cada pergunta tem um trecho certo conhecido; a métrica diz em
 * que posição da lista ele apareceu. Medir antes de decidir: modelo, rerank e pesos mudam por número, não por impressão.
 */
public final class SearchMetrics {

    private SearchMetrics() {
    }

    /** O resultado de uma pergunta: a posição (1 = primeiro) do trecho certo, ou 0 se não apareceu. */
    public record Outcome(String question, int rank) {
    }

    /** @param rankings os ids devolvidos pela busca, do melhor para o pior */
    public static Outcome outcome(String question, String expectedId, List<String> rankings) {
        int index = rankings.indexOf(expectedId);
        return new Outcome(question, index < 0 ? 0 : index + 1);
    }

    /** Fração das perguntas em que o trecho certo ficou entre os {@code k} primeiros. */
    public static double hitAt(List<Outcome> outcomes, int k) {
        if (outcomes.isEmpty()) {
            return 0;
        }
        return (double) outcomes.stream().filter(o -> o.rank() >= 1 && o.rank() <= k).count() / outcomes.size();
    }

    /** MRR: média de 1 / posição. Premia o trecho certo ficar no topo, e não só "entre os 5". */
    public static double mrr(List<Outcome> outcomes) {
        if (outcomes.isEmpty()) {
            return 0;
        }
        return outcomes.stream().mapToDouble(o -> o.rank() == 0 ? 0 : 1.0 / o.rank()).average().orElse(0);
    }
}
