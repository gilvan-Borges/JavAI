package br.com.gilvanborges.javai.admin.content;

import br.com.gilvanborges.javai.admin.audit.AuditLog;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1/experiences")
@Tag(name = "Experiências")
class ExperienceController extends ContentController<ExperienceInput> {

    ExperienceController(ExperienceRepository repository, TransactionTemplate tx, AuditLog audit) {
        super("experience", repository, tx, audit);
    }
}
