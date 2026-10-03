package br.com.gilvanborges.javai.ai.core.embedding;

import java.util.List;

/**
 * Porta: transforma texto em vetor. O ai-core só conhece esta interface; a implementação com ONNX Runtime vive no
 * módulo ai-embeddings. Se o modelo estiver indisponível, quem chama segue só com BM25.
 *
 * <p>Os vetores devolvidos já vêm normalizados (norma 1), então o produto escalar entre eles é o cosseno.
 */
public interface Embedder {

    /** Quantas dimensões tem cada vetor. O banco da IA exige 384. */
    int dimensions();

    /** O identificador do modelo, gravado em {@code index.version.embedding_model}. */
    String modelId();

    /** Vetor de uma pergunta. */
    float[] embedQuery(String text);

    /** Vetor de um trecho do conteúdo. Modelos como o e5 usam prefixos diferentes para pergunta e trecho. */
    float[] embedPassage(String text);

    default List<float[]> embedPassages(List<String> texts) {
        return texts.stream().map(this::embedPassage).toList();
    }
}
