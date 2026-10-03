package br.com.gilvanborges.javai.admin.repo;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * O que o dono informa para ligar um repositório. O resto (fork, branch, tamanho) vem da API do GitHub na hora:
 * ninguém digita metadado que a fonte sabe melhor.
 */
public record RepositoryInput(
        @NotNull @Pattern(regexp = "^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$",
                message = "use o formato dono/nome, como gilvan-Borges/JavAI") String fullName,
        @NotNull Kind kind,
        @Size(min = 1, max = 500) String notePt,
        @Size(min = 1, max = 500) String noteEn,
        boolean enabled) {

    /** Os mesmos valores do CHECK de {@code content.github_repo.kind}. */
    public enum Kind {
        DESTAQUE("destaque"), TRABALHO("trabalho"), ESTUDO("estudo"), CLIENTE("cliente");

        private final String db;

        Kind(String db) {
            this.db = db;
        }

        public String db() {
            return db;
        }
    }
}
