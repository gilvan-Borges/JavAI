package br.com.gilvanborges.javai.admin.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

/** Os tipos de erro do admin (RFC 9457, spec 03). */
public final class ProblemTypes {

    public static final URI VALIDATION = URI.create("/problems/validation");
    public static final URI CONSTRAINT = URI.create("/problems/constraint");
    public static final URI CONFLICT = URI.create("/problems/conflict");
    public static final URI NOT_FOUND = URI.create("/problems/not-found");
    public static final URI PUBLISH_REJECTED = URI.create("/problems/publish-rejected");
    public static final URI PUBLISH_IN_PROGRESS = URI.create("/problems/publish-in-progress");
    public static final URI INVALID_OPERATION = URI.create("/problems/invalid-operation");
    public static final URI INTERNAL = URI.create("/problems/internal");

    private ProblemTypes() {
    }

    public static ProblemDetail problem(HttpStatus status, URI type, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        return problem;
    }
}
