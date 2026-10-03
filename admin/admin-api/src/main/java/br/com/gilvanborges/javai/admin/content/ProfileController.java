package br.com.gilvanborges.javai.admin.content;

import br.com.gilvanborges.javai.admin.audit.AuditAction;
import br.com.gilvanborges.javai.admin.audit.AuditLog;
import br.com.gilvanborges.javai.admin.security.Actor;
import br.com.gilvanborges.javai.admin.web.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1/profile")
@Tag(name = "Perfil")
class ProfileController {

    private static final String ENTITY = "profile";

    private final ProfileRepository repository;
    private final TransactionTemplate tx;
    private final AuditLog audit;

    ProfileController(ProfileRepository repository, TransactionTemplate tx, AuditLog audit) {
        this.repository = repository;
        this.tx = tx;
        this.audit = audit;
    }

    @GetMapping
    ProfileItem get() {
        return repository.find().orElseThrow(() -> new NotFoundException(ENTITY));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Cria ou substitui o perfil. O status não muda por aqui")
    ProfileItem save(@Valid @RequestBody ProfileInput input, Authentication authentication) {
        String actor = Actor.of(authentication);
        return tx.execute(status -> {
            var before = repository.find().orElse(null);
            var after = repository.save(input, actor);
            audit.record(actor, before == null ? AuditAction.CREATE : AuditAction.UPDATE, ENTITY, "1", before, after);
            return after;
        });
    }

    @PatchMapping(path = "/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Publica ou volta para rascunho. Publicar exige título e resumo nos dois idiomas")
    ProfileItem changeStatus(@Valid @RequestBody StatusChange change, Authentication authentication) {
        String actor = Actor.of(authentication);
        return tx.execute(status -> {
            var before = get();
            var after = repository.changeStatus(change.status(), actor).orElseThrow(() -> new NotFoundException(ENTITY));
            audit.record(actor, AuditAction.STATUS, ENTITY, "1", before.status(), after.status());
            return after;
        });
    }
}
