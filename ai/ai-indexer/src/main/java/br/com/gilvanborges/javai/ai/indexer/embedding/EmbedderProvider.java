package br.com.gilvanborges.javai.ai.indexer.embedding;

import br.com.gilvanborges.javai.ai.core.embedding.Embedder;
import br.com.gilvanborges.javai.ai.embeddings.ModelStore;
import br.com.gilvanborges.javai.ai.embeddings.OnnxEmbedder;
import br.com.gilvanborges.javai.ai.indexer.IndexerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Entrega o modelo de embeddings quando ele estiver disponível. Se o download ou a verificação do hash falharem,
 * devolve vazio e o índice sai só com BM25; a próxima indexação tenta de novo (spec 01: a busca nunca depende do modelo).
 */
@Component
public class EmbedderProvider implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EmbedderProvider.class);

    private final IndexerProperties properties;
    private OnnxEmbedder embedder;

    public EmbedderProvider(IndexerProperties properties) {
        this.properties = properties;
    }

    public synchronized Optional<Embedder> get() {
        if (embedder == null) {
            try {
                Path dir = new ModelStore(Path.of(properties.modelDir())).ensure(ModelStore.E5_SMALL_INT8);
                embedder = new OnnxEmbedder(dir, properties.embeddingThreads());
                LOG.info("Modelo de embeddings carregado: {}", embedder.modelId());
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOG.warn("Modelo de embeddings indisponível ({}); indexando só com BM25.", e.getClass().getSimpleName());
                return Optional.empty();
            }
        }
        return Optional.of(embedder);
    }

    @Override
    public synchronized void close() {
        if (embedder != null) {
            embedder.close();
        }
    }
}
