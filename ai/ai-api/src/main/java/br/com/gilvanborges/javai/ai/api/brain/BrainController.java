package br.com.gilvanborges.javai.ai.api.brain;

import br.com.gilvanborges.javai.ai.api.chat.Keys;
import br.com.gilvanborges.javai.ai.api.web.ClientIp;
import br.com.gilvanborges.javai.ai.api.web.RateLimiter;
import br.com.gilvanborges.javai.ai.core.text.LanguageGuess;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;

/**
 * {@code GET /v1/brain} e {@code POST /v1/brain/probe} (spec 08). O mapa tem só conteúdo público e números agregados que
 * passaram do mínimo; a sonda mostra onde uma pergunta cairia e não grava nada.
 */
@RestController
public class BrainController {

    static final int MIN_QUESTION = 3;
    static final int MAX_QUESTION = 200;

    public record ProbeRequest(String question, String lang) {
    }

    private final BrainService service;
    private final Keys keys;
    private final Clock clock;
    private final RateLimiter probeLimit;

    public BrainController(BrainService service, Keys keys, Clock clock) {
        this.service = service;
        this.keys = keys;
        this.clock = clock;
        this.probeLimit = new RateLimiter(service.probeLimitPerMinute(), clock);
    }

    @GetMapping("/v1/brain")
    public ResponseEntity<?> brain() {
        return service.snapshot()
                .<ResponseEntity<?>>map(body -> ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic()).body(body))
                .orElseGet(() -> problem(HttpStatus.SERVICE_UNAVAILABLE, "/problems/maintenance", "Brain unavailable",
                        "The knowledge index is not loaded yet.", 60));
    }

    @PostMapping(value = "/v1/brain/probe", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> probe(@RequestBody ProbeRequest request, HttpServletRequest http) {
        String question = request.question() == null ? "" : request.question().strip();
        if (question.length() < MIN_QUESTION || question.length() > MAX_QUESTION) {
            return problem(HttpStatus.BAD_REQUEST, "/problems/invalid-question", "Invalid question",
                    "The question must have between " + MIN_QUESTION + " and " + MAX_QUESTION + " characters.", 0);
        }
        // O limite usa o hash do IP com a chave do dia (como o do chat): o IP em si nunca é guardado.
        long wait = probeLimit.tryAcquire(keys.ipHash(ClientIp.of(http), LocalDate.now(clock)));
        if (wait > 0) {
            return problem(HttpStatus.TOO_MANY_REQUESTS, "/problems/rate-limited", "Too many requests",
                    "Too many probes per minute.", wait);
        }
        String lang = "pt".equals(request.lang()) || "en".equals(request.lang()) ? request.lang()
                : LanguageGuess.of(question).equals("pt") ? "pt" : "en";
        return service.probe(question, lang)
                .<ResponseEntity<?>>map(body -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body))
                .orElseGet(() -> problem(HttpStatus.SERVICE_UNAVAILABLE, "/problems/maintenance", "Brain unavailable",
                        "The knowledge index is not loaded yet.", 60));
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String type, String title, String detail, long retryAfter) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(type));
        pd.setTitle(title);
        var response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).header("Cache-Control", "no-store");
        if (retryAfter > 0) {
            response.header("Retry-After", Long.toString(retryAfter));
        }
        return response.body(pd);
    }
}
