package br.com.gilvanborges.javai.admin.content;

import br.com.gilvanborges.javai.admin.audit.AuditAction;
import br.com.gilvanborges.javai.admin.audit.AuditLog;
import br.com.gilvanborges.javai.admin.web.NotFoundException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/** Toda escrita no conteúdo e a linha de auditoria correspondente acontecem na mesma transação. */
final class AuditedContent<I extends ContentInput> {

    private final String entity;
    private final ContentRepository<I> repository;
    private final TransactionTemplate tx;
    private final AuditLog audit;

    AuditedContent(String entity, ContentRepository<I> repository, TransactionTemplate tx, AuditLog audit) {
        this.entity = entity;
        this.repository = repository;
        this.tx = tx;
        this.audit = audit;
    }

    List<ContentItem<I>> list() {
        return repository.list();
    }

    ContentItem<I> get(UUID id) {
        return repository.find(id).orElseThrow(() -> new NotFoundException(entity));
    }

    ContentItem<I> create(I input, String actor) {
        return tx.execute(status -> {
            var created = repository.create(input, actor);
            audit.record(actor, AuditAction.CREATE, entity, created.id().toString(), null, created);
            return created;
        });
    }

    ContentItem<I> update(UUID id, I input, String actor) {
        return tx.execute(status -> {
            var before = get(id);
            var after = repository.update(id, input, actor).orElseThrow(() -> new NotFoundException(entity));
            audit.record(actor, AuditAction.UPDATE, entity, id.toString(), before, after);
            return after;
        });
    }

    ContentItem<I> changeStatus(UUID id, ContentStatus newStatus, String actor) {
        return tx.execute(status -> {
            var before = get(id);
            var after = repository.changeStatus(id, newStatus, actor).orElseThrow(() -> new NotFoundException(entity));
            audit.record(actor, AuditAction.STATUS, entity, id.toString(), before.status(), after.status());
            return after;
        });
    }

    void delete(UUID id, String actor) {
        tx.executeWithoutResult(status -> {
            var before = get(id);
            repository.delete(id);
            audit.record(actor, AuditAction.DELETE, entity, id.toString(), before, null);
        });
    }
}
