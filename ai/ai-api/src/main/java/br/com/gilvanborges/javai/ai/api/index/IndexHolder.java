package br.com.gilvanborges.javai.ai.api.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Guarda o índice ativo e o recarrega quando o ai-indexer ativa uma versão nova. A conferência é uma consulta
 * barata do id da versão a cada poucos segundos: mais simples e mais robusta que um LISTEN/NOTIFY, que exigiria uma
 * conexão dedicada e a lógica de reconectar. Se o banco cair, o índice que já está em memória continua servindo.
 */
@Component
public class IndexHolder {

    private static final Logger LOG = LoggerFactory.getLogger(IndexHolder.class);

    private final IndexRepository repository;
    private volatile ActiveIndex current;

    public IndexHolder(IndexRepository repository) {
        this.repository = repository;
    }

    public Optional<ActiveIndex> current() {
        return Optional.ofNullable(current);
    }

    /** Metadados da versão ativa para a página de admin (uma consulta pequena; vazio se o banco falhar). */
    public Optional<IndexRepository.VersionMeta> meta() {
        try {
            return repository.activeMeta();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${aiapi.reload-interval:PT30S}")
    public void refresh() {
        try {
            Optional<Long> active = repository.activeVersionId();
            if (active.isEmpty()) {
                return;
            }
            ActiveIndex loaded = current;
            if (loaded != null && loaded.versionId() == active.get()) {
                return;
            }
            repository.loadActive().ifPresent(index -> {
                current = index;
                LOG.info("Índice {} carregado: {} trechos, vetores: {}.", index.versionId(), index.chunkCount(),
                        index.hasVectors());
            });
        } catch (RuntimeException e) {
            // Sem mensagem: ela pode trazer detalhe do banco. O índice anterior segue valendo.
            LOG.warn("Não consegui recarregar o índice ({}); mantenho o que está em memória.", e.getClass().getSimpleName());
        }
    }
}
