package br.com.gilvanborges.javai.ai.api.chat;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/** Erros do chat como Problem Details (RFC 9457): nunca trazem stack trace, SQL nem valores internos. */
@RestControllerAdvice(assignableTypes = ChatController.class)
class ChatAdvice {

    @ExceptionHandler(ChatProblem.class)
    ResponseEntity<ProblemDetail> problem(ChatProblem e) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(e.status()), e.getMessage());
        detail.setType(URI.create(e.type()));
        detail.setTitle(e.title());
        var response = ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header("Cache-Control", "no-store");
        if (e.retryAfterSeconds() > 0) {
            response.header("Retry-After", Long.toString(e.retryAfterSeconds()));
        }
        return response.body(detail);
    }
}
