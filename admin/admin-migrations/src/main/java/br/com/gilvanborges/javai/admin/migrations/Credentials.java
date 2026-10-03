package br.com.gilvanborges.javai.admin.migrations;

import java.util.Objects;

/** Usuário e senha de um papel do PostgreSQL. O {@code toString} nunca mostra a senha. */
public record Credentials(String user, String password) {

    public Credentials {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(password, "password");
    }

    @Override
    public String toString() {
        return "Credentials[user=" + user + ", password=***]";
    }
}
