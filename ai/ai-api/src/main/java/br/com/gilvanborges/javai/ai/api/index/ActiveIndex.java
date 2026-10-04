package br.com.gilvanborges.javai.ai.api.index;

import br.com.gilvanborges.javai.ai.core.brain.BrainLayout;
import br.com.gilvanborges.javai.ai.core.chunk.Chunk;
import br.com.gilvanborges.javai.ai.core.chunk.SourceKind;
import br.com.gilvanborges.javai.ai.core.evidence.Evidence;
import br.com.gilvanborges.javai.ai.core.search.HybridSearcher;
import br.com.gilvanborges.javai.ai.core.search.VectorOps;

import java.util.List;

/**
 * O índice ativo inteiro, em memória e imutável. Cada recarga cria um objeto novo e troca a referência de uma vez,
 * então uma chamada em andamento nunca enxerga um índice pela metade.
 *
 * @param embeddingModel o modelo que gerou os vetores; a busca só usa o vetor da pergunta se for o mesmo modelo
 */
public final class ActiveIndex {

    private final long versionId;
    private final String embeddingModel;
    private final List<Chunk> chunks;
    private final List<Evidence> evidence;
    private final HybridSearcher searcher;
    private final List<float[]> embeddings;
    private volatile List<BrainLayout.Node> brainLayout;

    /** @param embeddings um vetor por trecho, na mesma ordem; {@code null} se algum faltar (só BM25). */
    public ActiveIndex(long versionId, String embeddingModel, List<Chunk> chunks, List<float[]> embeddings,
                       List<Evidence> evidence, VectorOps ops) {
        this.versionId = versionId;
        this.embeddingModel = embeddingModel;
        this.chunks = List.copyOf(chunks);
        this.evidence = List.copyOf(evidence);
        this.searcher = new HybridSearcher(this.chunks, embeddings, ops);
        this.embeddings = embeddings;
    }

    public long versionId() {
        return versionId;
    }

    public String embeddingModel() {
        return embeddingModel;
    }

    public boolean hasVectors() {
        return searcher.hasVectors();
    }

    /**
     * O mapa do "cérebro" desta versão do índice (spec 08): um ponto por origem de conhecimento, perto de quem fala de coisas
     * parecidas. É calculado na primeira vez que alguém pede e reaproveitado até o índice mudar.
     */
    public List<BrainLayout.Node> brainLayout() {
        List<BrainLayout.Node> layout = brainLayout;
        if (layout == null) {
            synchronized (this) {
                layout = brainLayout;
                if (layout == null) {
                    layout = BrainLayout.of(chunks, embeddings);
                    brainLayout = layout;
                }
            }
        }
        return layout;
    }

    public int chunkCount() {
        return chunks.size();
    }

    public List<Chunk> chunks() {
        return chunks;
    }

    public List<Evidence> evidence() {
        return evidence;
    }

    /** Os trechos de um tipo no idioma pedido, na ordem em que o snapshot os trouxe. */
    public List<Chunk> ofKind(SourceKind kind, String lang) {
        return chunks.stream().filter(c -> c.kind() == kind && c.lang().equals(lang)).toList();
    }

    /** Os trechos de uma fonte (um texto longo vira vários trechos com a mesma {@code sourceRef}). */
    public List<Chunk> withRef(String sourceRef, String lang) {
        return chunks.stream().filter(c -> c.sourceRef().equals(sourceRef) && c.lang().equals(lang)).toList();
    }

    public List<HybridSearcher.Hit> search(String query, float[] queryVector, String lang, int limit) {
        return searcher.search(query, queryVector, lang, limit);
    }
}
