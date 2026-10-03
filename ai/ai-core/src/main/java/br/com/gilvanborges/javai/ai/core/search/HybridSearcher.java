package br.com.gilvanborges.javai.ai.core.search;

import br.com.gilvanborges.javai.ai.core.chunk.Chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Busca híbrida (spec 01): BM25 mais cosseno, juntos por RRF. Se os embeddings não existirem ou a pergunta não
 * tiver vetor (modelo fora do ar), a busca continua só com BM25 em vez de falhar.
 */
public final class HybridSearcher {

    /** Quantos candidatos cada sistema entrega à fusão. */
    private static final int CANDIDATES = 20;

    private final List<Chunk> chunks;
    private final Bm25Index bm25;
    private final Optional<VectorIndex> vectors;

    /** @param embeddings um vetor por trecho, na mesma ordem; {@code null} para usar só BM25. */
    public HybridSearcher(List<Chunk> chunks, List<float[]> embeddings, VectorOps ops) {
        if (embeddings != null && embeddings.size() != chunks.size()) {
            throw new IllegalArgumentException("É preciso um vetor por trecho.");
        }
        this.chunks = List.copyOf(chunks);
        this.bm25 = new Bm25Index(chunks.stream().map(Chunk::searchText).toList());
        this.vectors = embeddings == null || embeddings.isEmpty()
                ? Optional.empty()
                : Optional.of(new VectorIndex(embeddings, ops));
    }

    public int size() {
        return chunks.size();
    }

    public boolean hasVectors() {
        return vectors.isPresent();
    }

    /**
     * @param queryVector o vetor da pergunta, ou {@code null} se não houver
     * @param lang        quando informado, só devolve trechos desse idioma
     */
    public List<Hit> search(String query, float[] queryVector, String lang, int limit) {
        var rankings = new ArrayList<List<ScoredDoc>>();
        rankings.add(bm25.search(query, CANDIDATES * 2));
        if (vectors.isPresent() && queryVector != null) {
            rankings.add(vectors.get().search(queryVector, CANDIDATES * 2));
        }
        var hits = new ArrayList<Hit>();
        for (ScoredDoc fused : ReciprocalRankFusion.fuse(rankings, ReciprocalRankFusion.DEFAULT_K, chunks.size())) {
            Chunk chunk = chunks.get(fused.doc());
            if (lang == null || lang.equals(chunk.lang()) || chunk.kind().isLanguageNeutral()) {
                hits.add(new Hit(chunk, fused.score()));
                if (hits.size() == limit) {
                    break;
                }
            }
        }
        return List.copyOf(hits);
    }

    public record Hit(Chunk chunk, double score) {
    }
}
