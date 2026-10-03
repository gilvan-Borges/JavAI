package br.com.gilvanborges.javai.admin.publish;

import br.com.gilvanborges.javai.admin.security.Actor;
import br.com.gilvanborges.javai.admin.web.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/v1")
@Tag(name = "Publicação")
class PublishController {

    private final PublishJobs jobs;

    PublishController(PublishJobs jobs) {
        this.jobs = jobs;
    }

    @PostMapping("/publish")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Gera uma versão nova com o que está publicado e público, ativa e avisa o portfólio")
    Map<String, UUID> publish(Authentication authentication) {
        return Map.of("jobId", jobs.start(Actor.of(authentication)));
    }

    @GetMapping("/jobs/{id}")
    PublishJobs.PublishJob job(@PathVariable UUID id) {
        return jobs.find(id).orElseThrow(() -> new NotFoundException("job"));
    }
}
