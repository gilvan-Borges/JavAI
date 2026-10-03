package br.com.gilvanborges.javai.ai.core.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion: junta rankings de sistemas diferentes (BM25 e vetorial) usando só a posição de cada
 * documento, sem comparar notas que estão em escalas distintas. Nota = soma de 1 / (k + posição).
 */
public final class ReciprocalRankFusion {

    /** O valor padrão da literatura; segura o peso dos primeiros lugares sem apagar os demais. */
    public static final int DEFAULT_K = 60;

    private ReciprocalRankFusion() {
    }

    public static List<ScoredDoc> fuse(List<List<ScoredDoc>> rankings, int k, int limit) {
        Map<Integer, Double> fused = new HashMap<>();
        for (List<ScoredDoc> ranking : rankings) {
            for (int position = 0; position < ranking.size(); position++) {
                fused.merge(ranking.get(position).doc(), 1.0 / (k + position + 1), Double::sum);
            }
        }
        var out = new ArrayList<ScoredDoc>(fused.size());
        fused.forEach((doc, score) -> out.add(new ScoredDoc(doc, score)));
        out.sort(Comparator.comparingDouble(ScoredDoc::score).reversed().thenComparingInt(ScoredDoc::doc));
        return out.size() > limit ? List.copyOf(out.subList(0, limit)) : List.copyOf(out);
    }
}
