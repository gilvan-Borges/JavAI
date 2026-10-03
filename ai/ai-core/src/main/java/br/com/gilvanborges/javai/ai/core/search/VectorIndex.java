package br.com.gilvanborges.javai.ai.core.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Índice vetorial plano: compara a pergunta com todos os vetores. Para algumas centenas ou poucos milhares de
 * trechos isso é mais simples e mais rápido do que um banco vetorial (spec 01). Os vetores são normalizados na
 * entrada, então o produto escalar é o cosseno.
 */
public final class VectorIndex {

    private final float[][] vectors;
    private final VectorOps ops;

    public VectorIndex(List<float[]> vectors, VectorOps ops) {
        this.ops = ops;
        this.vectors = new float[vectors.size()][];
        for (int i = 0; i < vectors.size(); i++) {
            this.vectors[i] = normalized(vectors.get(i));
        }
    }

    public int size() {
        return vectors.length;
    }

    public List<ScoredDoc> search(float[] query, int limit) {
        if (vectors.length == 0 || limit <= 0) {
            return List.of();
        }
        float[] q = normalized(query);
        // Fila com os melhores até agora; o pior fica na cabeça para ser trocado.
        var best = new PriorityQueue<ScoredDoc>(limit, Comparator.comparingDouble(ScoredDoc::score));
        for (int doc = 0; doc < vectors.length; doc++) {
            double score = ops.dot(q, vectors[doc]);
            if (best.size() < limit) {
                best.add(new ScoredDoc(doc, score));
            } else if (score > best.peek().score()) {
                best.poll();
                best.add(new ScoredDoc(doc, score));
            }
        }
        var hits = new ArrayList<>(best);
        hits.sort(Comparator.comparingDouble(ScoredDoc::score).reversed().thenComparingInt(ScoredDoc::doc));
        return List.copyOf(hits);
    }

    static float[] normalized(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        if (sum == 0) {
            return v.clone();
        }
        float inv = (float) (1.0 / Math.sqrt(sum));
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = v[i] * inv;
        }
        return out;
    }
}
