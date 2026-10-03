package br.com.gilvanborges.javai.admin.repo;

import br.com.gilvanborges.javai.admin.audit.AuditAction;
import br.com.gilvanborges.javai.admin.audit.AuditLog;
import br.com.gilvanborges.javai.admin.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Liga e desliga os repositórios do GitHub que entram no snapshot (spec 03). Era a última parte do conteúdo sem
 * endpoint: até aqui, repositório só entrava por SQL. O fluxo é: ligar aqui → publicar → o ai-indexer lê o snapshot
 * novo em até 5 minutos e indexa o conteúdo sozinho.
 */
@RestController
@RequestMapping("/admin/v1/repositories")
@Tag(name = "Repositórios do GitHub")
class RepositoryController {

    private final RepositoryStore store;
    private final GitHubLookup github;
    private final TransactionTemplate tx;
    private final AuditLog audit;

    RepositoryController(RepositoryStore store, GitHubLookup github, TransactionTemplate tx, AuditLog audit) {
        this.store = store;
        this.github = github;
        this.tx = tx;
        this.audit = audit;
    }

    @GetMapping
    @Operation(summary = "Lista os repositórios, ligados ou não, com os metadados lidos do GitHub")
    public List<RepositoryStore.Row> list() {
        return store.list();
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Liga (ou atualiza) um repositório pelo dono/nome",
            description = "Busca fork, branch e tamanho na API do GitHub na hora. Repositório privado, inexistente ou "
                    + "fork é recusado. Depois de ligar, publique: o indexador lê o snapshot novo em até 5 minutos.")
    public RepositoryStore.Row upsert(@Valid @RequestBody RepositoryInput input, Authentication authentication) {
        String actor = Actor.of(authentication);
        GitHubLookup.Meta meta = github.fetch(input.fullName()).orElseThrow(() -> problem(HttpStatus.UNPROCESSABLE_CONTENT,
                "Não encontrei esse repositório público no GitHub. Ele é privado, não existe ou o nome está errado."));
        if (input.enabled() && meta.fork()) {
            throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "Fork não pode ser ligado: o snapshot só leva repositório próprio.");
        }
        return tx.execute(status -> {
            RepositoryStore.Row before = store.find(meta.fullName()).orElse(null);
            RepositoryStore.Row saved = store.upsert(meta, input, actor);
            audit.record(actor, AuditAction.REPO, "github_repo", meta.fullName(), before, saved);
            return saved;
        });
    }

    @DeleteMapping("/{owner}/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove o repositório da lista (sai do snapshot na próxima publicação)")
    public void delete(@PathVariable String owner, @PathVariable String name, Authentication authentication) {
        String actor = Actor.of(authentication);
        String fullName = owner + "/" + name;
        tx.executeWithoutResult(status -> store.find(fullName).ifPresent(row -> {
            store.delete(fullName);
            audit.record(actor, AuditAction.REPO, "github_repo", fullName, row, null);
        }));
    }

    private static ErrorResponseException problem(HttpStatus status, String detail) {
        var e = new ErrorResponseException(status);
        e.setDetail(detail);
        return e;
    }
}
