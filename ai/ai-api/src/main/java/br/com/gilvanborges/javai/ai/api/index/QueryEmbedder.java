package br.com.gilvanborges.javai.ai.api.index;

import br.com.gilvanborges.javai.ai.api.AiApiProperties;
import br.com.gilvanborges.javai.ai.embeddings.ModelStore;
import br.com.gilvanborges.javai.ai.embeddings.OnnxEmbedder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Gera o vetor da pergunta, dentro da JVM (nenhum texto sai do servidor). O modelo é o que o ai-indexer baixou e
 * verificou; aqui só se confere o hash, sem baixar. Sem modelo, a busca cai para só BM25 em vez de falhar (spec 01).
 */
@Component
public class QueryEmbedder implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(QueryEmbedder.class);

    private final AiApiProperties properties;
    private OnnxEmbedder embedder;
    private long lastAttemptNanos;

    public QueryEmbedder(AiApiProperties properties) {
        this.properties = properties;
    }

    /**
     * Aquece no boot, fora do caminho de quem pergunta: carregar o ONNX e gerar o primeiro vetor custa segundos, e
     * sem isso a primeira busca depois de um reinício pagava essa espera (spec 06, pendência 6).
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void warmUpOnBoot() {
        Thread.ofVirtual().name("embedder-warmup").start(this::warmUp);
    }

    synchronized void warmUp() {
        OnnxEmbedder model = model();
        if (model == null) {
            return; // volume ainda sem modelo: a primeira pergunta tenta de novo
        }
        try {
            model.embedQuery("aquecimento");
            LOG.info("Modelo de embeddings aquecido.");
        } catch (RuntimeException e) {
            LOG.warn("Aquecimento do embedder falhou ({}).", e.getClass().getSimpleName());
        }
    }

    /**
     * O vetor da pergunta, ou vazio se o modelo não estiver disponível ou for diferente do que gerou o índice
     * (vetores de modelos diferentes não se comparam).
     */
    public synchronized Optional<float[]> embed(String query, String indexModel) {
        OnnxEmbedder model = model();
        if (model == null || !model.modelId().equals(indexModel)) {
            return Optional.empty();
        }
        try {
            return Optional.of(model.embedQuery(query));
        } catch (RuntimeException e) {
            LOG.warn("Falha ao gerar o vetor da pergunta ({}); busca só com BM25.", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private OnnxEmbedder model() {
        if (embedder != null) {
            return embedder;
        }
        // Se o modelo ainda não está no volume, tenta de novo no máximo uma vez por minuto.
        long now = System.nanoTime();
        if (lastAttemptNanos != 0 && now - lastAttemptNanos < 60_000_000_000L) {
            return null;
        }
        lastAttemptNanos = now == 0 ? 1 : now;
        Optional<Path> dir = new ModelStore(Path.of(properties.modelDir())).findVerified(ModelStore.E5_SMALL_INT8);
        if (dir.isEmpty()) {
            LOG.info("Modelo de embeddings ainda não está no volume; busca só com BM25 por enquanto.");
            return null;
        }
        try {
            embedder = new OnnxEmbedder(dir.get(), properties.embeddingThreads());
            LOG.info("Modelo de embeddings carregado: {}", embedder.modelId());
        } catch (RuntimeException e) {
            LOG.warn("Não consegui carregar o modelo ({}); busca só com BM25.", e.getClass().getSimpleName());
        }
        return embedder;
    }

    @Override
    public synchronized void close() {
        if (embedder != null) {
            embedder.close();
        }
    }
}
