package br.com.gilvanborges.javai.core.publish;

import br.com.gilvanborges.javai.core.snapshot.ContentSnapshot;

/** Porta: versões do índice e os snapshots de cada uma (schema {@code ai}). */
public interface IndexVersionStore {

    /** Cria uma versão com status {@code building} e devolve o id. */
    long createVersion();

    void saveSnapshot(ContentSnapshot snapshot);

    /** Ativa a versão e aposenta a anterior, numa transação só. */
    void activate(long indexVersion);

    void markFailed(long indexVersion);
}
