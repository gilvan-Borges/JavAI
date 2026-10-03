package br.com.gilvanborges.javai.admin.audit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/admin/v1/audit")
@Tag(name = "Auditoria")
class AuditController {

    private final AuditLog audit;
    private final Clock clock;

    AuditController(AuditLog audit, Clock clock) {
        this.audit = audit;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "Lista a auditoria do período (padrão: últimos 7 dias, até 500 linhas)")
    List<AuditLog.AuditEntry> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        var end = to != null ? to : OffsetDateTime.now(clock);
        var start = from != null ? from : end.minusDays(7);
        return audit.list(start, end);
    }
}
