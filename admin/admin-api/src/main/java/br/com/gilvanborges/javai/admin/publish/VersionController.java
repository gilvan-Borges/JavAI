package br.com.gilvanborges.javai.admin.publish;

import br.com.gilvanborges.javai.admin.audit.AuditAction;
import br.com.gilvanborges.javai.admin.audit.AuditLog;
import br.com.gilvanborges.javai.admin.domain.publish.PortfolioNotifier;
import br.com.gilvanborges.javai.admin.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/v1/versions")
@Tag(name = "Publicação")
class VersionController {

    private final JdbcPublicationStore store;
    private final PortfolioNotifier notifier;
    private final TransactionTemplate tx;
    private final AuditLog audit;

    VersionController(JdbcPublicationStore store, PortfolioNotifier notifier, TransactionTemplate tx, AuditLog audit) {
        this.store = store;
        this.notifier = notifier;
        this.tx = tx;
        this.audit = audit;
    }

    @GetMapping
    List<JdbcPublicationStore.VersionView> list() {
        return store.list();
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Rollback: reativa uma versão guardada, reexporta os arquivos públicos e avisa o portfólio")
    Map<String, Object> activate(@PathVariable long id, Authentication authentication) {
        String actor = Actor.of(authentication);
        // Auditoria primeiro e na mesma transação: os arquivos só são trocados se tudo o mais deu certo.
        tx.executeWithoutResult(status -> {
            audit.record(actor, AuditAction.ACTIVATE, "version", String.valueOf(id), null, Map.of("version", id));
            store.activate(id);
        });
        return Map.of("version", id, "portfolioNotified", notifier.contentPublished(id));
    }
}
