package br.com.gilvanborges.javai.ai.core.search;

import br.com.gilvanborges.javai.ai.core.text.Tokenizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * BM25 em memória, escrito aqui de propósito (spec 01): cada linha da fórmula dá para explicar numa entrevista.
 * Os documentos são identificados pela posição na lista recebida.
 */
public final class Bm25Index {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final int docCount;
    private final double avgLength;
    private final int[] lengths;
    /** termo -> documentos que o contêm, com a frequência do termo em cada um. */
    private final Map<String, List<Posting>> postings = new HashMap<>();

    private record Posting(int doc, int frequency) {
    }

    public Bm25Index(List<String> documents) {
        this.docCount = documents.size();
        this.lengths = new int[docCount];
        long total = 0;
        for (int doc = 0; doc < docCount; doc++) {
            List<String> tokens = Tokenizer.tokens(documents.get(doc));
            lengths[doc] = tokens.size();
            total += tokens.size();
            Map<String, Integer> counts = new HashMap<>();
            for (String token : tokens) {
                counts.merge(token, 1, Integer::sum);
            }
            int docId = doc;
            counts.forEach((term, tf) -> postings.computeIfAbsent(term, t -> new ArrayList<>()).add(new Posting(docId, tf)));
        }
        this.avgLength = docCount == 0 ? 0 : (double) total / docCount;
    }

    public int size() {
        return docCount;
    }

    /** Os {@code limit} documentos mais relevantes, do melhor para o pior. Documentos sem nenhum termo em comum não entram. */
    public List<ScoredDoc> search(String query, int limit) {
        if (docCount == 0 || limit <= 0) {
            return List.of();
        }
        double[] scores = new double[docCount];
        boolean[] touched = new boolean[docCount];
        for (String term : new LinkedHashSet<>(Tokenizer.tokens(query))) {
            List<Posting> list = postings.get(term);
            if (list == null) {
                continue;
            }
            double idf = Math.log(1 + (docCount - list.size() + 0.5) / (list.size() + 0.5));
            for (Posting p : list) {
                double norm = 1 - B + B * lengths[p.doc()] / avgLength;
                scores[p.doc()] += idf * (p.frequency() * (K1 + 1)) / (p.frequency() + K1 * norm);
                touched[p.doc()] = true;
            }
        }
        var hits = new ArrayList<ScoredDoc>();
        for (int doc = 0; doc < docCount; doc++) {
            if (touched[doc]) {
                hits.add(new ScoredDoc(doc, scores[doc]));
            }
        }
        hits.sort(Comparator.comparingDouble(ScoredDoc::score).reversed().thenComparingInt(ScoredDoc::doc));
        return hits.size() > limit ? List.copyOf(hits.subList(0, limit)) : List.copyOf(hits);
    }
}
