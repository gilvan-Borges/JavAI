package br.com.gilvanborges.javai.admin.domain.publish;

import br.com.gilvanborges.javai.admin.domain.snapshot.ContentSnapshot;

/** Porta: as versões publicadas e os snapshots de cada uma (schema {@code publish}). */
public interface PublicationStore {

    /** Cria uma versão com status {@code building} e devolve o id. */
    long createVersion();

    void saveSnapshot(ContentSnapshot snapshot);

    /** Ativa a versão, aposenta a anterior e exporta o snapshot público. Ou tudo acontece, ou nada. */
    void activate(long version);

    void markFailed(long version);
}
