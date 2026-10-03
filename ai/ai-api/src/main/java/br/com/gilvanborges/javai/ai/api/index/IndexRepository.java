package br.com.gilvanborges.javai.ai.api.index;

import java.util.Optional;

/** De onde vem o índice ativo. A implementação de produção lê o banco da IA com o papel {@code ai_reader}. */
public interface IndexRepository {

    /** O id da versão ativa, ou vazio se ainda não houver índice. É uma consulta barata, feita a cada recarga. */
    Optional<Long> activeVersionId();

    /**
     * O que o ai-indexer gravou sobre a versão ativa. {@code snapshotEtag} e {@code stats} são JSON em texto.
     */
    record VersionMeta(long id, java.time.Instant activatedAt, String embeddingModel, String snapshotEtag, String stats) {
    }

    default Optional<VersionMeta> activeMeta() {
        return Optional.empty();
    }

    /** Carrega a versão ativa inteira, ou vazio se não houver nenhuma. */
    Optional<ActiveIndex> loadActive();
}
