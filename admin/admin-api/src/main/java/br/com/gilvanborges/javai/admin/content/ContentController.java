package br.com.gilvanborges.javai.admin.content;

import br.com.gilvanborges.javai.admin.audit.AuditLog;
import br.com.gilvanborges.javai.admin.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.List;
import java.util.UUID;

/** As rotas comuns a todo recurso de conteúdo (spec 03, "API do admin"). Cada recurso só define o caminho. */
public abstract class ContentController<I extends ContentInput> {

    private final AuditedContent<I> content;

    protected ContentController(String entity, ContentRepository<I> repository, TransactionTemplate tx, AuditLog audit) {
        this.content = new AuditedContent<>(entity, repository, tx, audit);
    }

    @GetMapping
    @Operation(summary = "Lista todos os itens, inclusive rascunhos e privados")
    public List<ContentItem<I>> list() {
        return content.list();
    }

    @GetMapping("/{id}")
    public ContentItem<I> get(@PathVariable UUID id) {
        return content.get(id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Cria um item, sempre como rascunho")
    public ContentItem<I> create(@Valid @RequestBody I input, Authentication authentication) {
        return content.create(input, Actor.of(authentication));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ContentItem<I> update(@PathVariable UUID id, @Valid @RequestBody I input, Authentication authentication) {
        return content.update(id, input, Actor.of(authentication));
    }

    @PatchMapping(path = "/{id}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Publica ou volta para rascunho. Publicar exige os campos obrigatórios nos dois idiomas")
    public ContentItem<I> changeStatus(
            @PathVariable UUID id, @Valid @RequestBody StatusChange change, Authentication authentication) {
        return content.changeStatus(id, change.status(), Actor.of(authentication));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication authentication) {
        content.delete(id, Actor.of(authentication));
    }
}
