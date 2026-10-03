package br.com.gilvanborges.javai.admin.web;

import br.com.gilvanborges.javai.admin.publish.PublishInProgressException;
import br.com.gilvanborges.javai.admin.domain.publish.PublishRejectedException;
import org.postgresql.util.PSQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static br.com.gilvanborges.javai.admin.web.ProblemTypes.problem;

/**
 * Erros no formato Problem Details. Nunca devolve stack trace, SQL nem valores de linhas: no máximo o nome de
 * uma constraint, que é nosso e diz qual regra foi violada.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var body = problem(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION, "Dados inválidos.");
        body.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.of(
                        "field", e.getField(),
                        "message", Objects.requireNonNullElse(e.getDefaultMessage(), "inválido")))
                .toList());
        return ResponseEntity.badRequest().body(body);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // A mensagem do parser pode repetir trechos do corpo enviado, então ela não volta para o cliente.
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION,
                "Corpo inválido: JSON malformado, tipo errado ou campo desconhecido."));
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ProblemTypes.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(PublishRejectedException.class)
    ProblemDetail publishRejected(PublishRejectedException ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ProblemTypes.PUBLISH_REJECTED, ex.getMessage());
    }

    @ExceptionHandler(PublishInProgressException.class)
    ProblemDetail publishInProgress(PublishInProgressException ex) {
        return problem(HttpStatus.CONFLICT, ProblemTypes.PUBLISH_IN_PROGRESS, ex.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail database(DataAccessException ex) {
        var sql = sqlException(ex);
        String state = sql.map(SQLException::getSQLState).orElse("");
        ProblemDetail body = switch (state) {
            case "23514", "23502", "22001", "22007", "22008" -> problem(HttpStatus.UNPROCESSABLE_CONTENT,
                    ProblemTypes.CONSTRAINT, "Uma regra do banco recusou os dados. Para publicar, preencha os "
                            + "campos obrigatórios nos dois idiomas.");
            case "23505" -> problem(HttpStatus.CONFLICT, ProblemTypes.CONFLICT, "Já existe um registro com esse valor.");
            case "P0001" -> problem(HttpStatus.UNPROCESSABLE_CONTENT, ProblemTypes.INVALID_OPERATION,
                    sql.map(SQLException::getMessage).map(ApiExceptionHandler::firstLine).orElse("Operação recusada."));
            default -> null;
        };
        if (body == null) {
            LOG.error("Erro de banco não tratado (SQLState {})", state, ex);
            return problem(HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.INTERNAL, "Erro interno.");
        }
        constraint(sql).ifPresent(name -> body.setProperty("constraint", name));
        return body;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        LOG.error("Erro não tratado", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.INTERNAL, "Erro interno.");
    }

    private static Optional<SQLException> sqlException(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return Optional.of(sql);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> constraint(Optional<SQLException> sql) {
        return sql.filter(PSQLException.class::isInstance)
                .map(PSQLException.class::cast)
                .map(PSQLException::getServerErrorMessage)
                .map(m -> m.getConstraint());
    }

    private static String firstLine(String message) {
        // O PgJDBC acrescenta "Where:" e "Position:" em linhas seguintes; só a mensagem do RAISE interessa.
        String line = message.lines().findFirst().orElse(message);
        return line.startsWith("ERROR: ") ? line.substring("ERROR: ".length()) : line;
    }
}
