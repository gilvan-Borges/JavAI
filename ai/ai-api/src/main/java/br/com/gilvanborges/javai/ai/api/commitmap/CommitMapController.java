package br.com.gilvanborges.javai.ai.api.commitmap;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;

/**
 * {@code GET /v1/commit-map} (spec 09). Só repositório público e que não é fork, e só hash curto, data e a primeira linha
 * da mensagem de cada commit. O CORS é o das rotas {@code /v1/**} (ver {@code ChatConfig}).
 */
@RestController
public class CommitMapController {

    private final CommitMapService service;

    public CommitMapController(CommitMapService service) {
        this.service = service;
    }

    @GetMapping("/v1/commit-map")
    public ResponseEntity<?> commitMap() {
        return service.snapshot()
                .<ResponseEntity<?>>map(body -> ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic()).body(body))
                .orElseGet(() -> {
                    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                            "The knowledge index is not loaded yet.");
                    pd.setType(URI.create("/problems/maintenance"));
                    pd.setTitle("Commit map unavailable");
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                            .header("Cache-Control", "no-store").header("Retry-After", "60").body(pd);
                });
    }
}
