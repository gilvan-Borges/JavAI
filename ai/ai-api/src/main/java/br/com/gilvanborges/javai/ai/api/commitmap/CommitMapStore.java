package br.com.gilvanborges.javai.ai.api.commitmap;

import java.time.Instant;
import java.util.List;

/**
 * De onde vêm os commits do mapa (spec 09). A implementação de produção lê o banco da IA com o papel {@code ai_reader};
 * o RLS já limita à versão ativa, e o indexador só grava repositório público e que não é fork.
 */
public interface CommitMapStore {

    /** Sem banco (testes ou {@code aiapi.db.enabled=false}): o mapa sai vazio. */
    CommitMapStore NOOP = perRepo -> List.of();

    /** Um commit como o indexador gravou: nada além do hash curto, do instante e da primeira linha da mensagem. */
    record Commit(String repoFullName, String sha, Instant at, String subject) {
    }

    /** No máximo {@code perRepo} commits de cada repositório da versão ativa, em qualquer ordem entre repositórios. */
    List<Commit> recent(int perRepo);
}
