package br.com.gilvanborges.javai.admin.content;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Acesso a uma tabela de {@code content}. Cada implementação escreve o próprio SQL, por inteiro. */
public interface ContentRepository<I extends ContentInput> {

    List<ContentItem<I>> list();

    Optional<ContentItem<I>> find(UUID id);

    ContentItem<I> create(I input, String actor);

    Optional<ContentItem<I>> update(UUID id, I input, String actor);

    Optional<ContentItem<I>> changeStatus(UUID id, ContentStatus status, String actor);

    boolean delete(UUID id);
}
