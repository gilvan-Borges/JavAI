package br.com.gilvanborges.javai.admin.migrations;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Onde fica o banco do CRUD e as credenciais de cada papel, lidas do ambiente do contêiner admin-migrate.
 *
 * <p>Senhas fracas ou repetidas impedem o deploy: é melhor falhar aqui do que subir inseguro.
 */
public record DatabaseSettings(
        String host,
        int port,
        String database,
        Credentials superuser,
        Credentials owner,
        Credentials app) {

    static final int MIN_PASSWORD_LENGTH = 24;

    public DatabaseSettings {
        if (!database.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Nome de banco inválido.");
        }
        if (!owner.user().equals(Roles.OWNER) || !app.user().equals(Roles.APP)) {
            throw new IllegalArgumentException("Os papéis têm nomes fixos (spec 02).");
        }
        var passwords = List.of(superuser.password(), owner.password(), app.password());
        if (passwords.stream().anyMatch(p -> p.length() < MIN_PASSWORD_LENGTH)) {
            throw new IllegalArgumentException(
                    "Toda senha precisa ter pelo menos " + MIN_PASSWORD_LENGTH + " caracteres.");
        }
        if (passwords.stream().distinct().count() != passwords.size()) {
            throw new IllegalArgumentException("Cada papel precisa de uma senha diferente.");
        }
    }

    public static DatabaseSettings fromEnvironment(Map<String, String> env) {
        return new DatabaseSettings(
                required(env, "ADMIN_DB_HOST"),
                Integer.parseInt(optional(env, "ADMIN_DB_PORT").orElse("5432")),
                optional(env, "ADMIN_DB_NAME").orElse("admin"),
                new Credentials(optional(env, "POSTGRES_USER").orElse("postgres"), required(env, "POSTGRES_PASSWORD")),
                new Credentials(Roles.OWNER, required(env, "ADMIN_OWNER_PASSWORD")),
                new Credentials(Roles.APP, required(env, "ADMIN_APP_PASSWORD")));
    }

    /** O banco do CRUD. {@code logServerErrorDetail=false} evita que valores de linhas apareçam em exceções. */
    public String url() {
        return jdbcUrl(database);
    }

    /** O banco de manutenção, onde o superusuário cria os papéis e o banco do CRUD. */
    public String maintenanceUrl() {
        return jdbcUrl("postgres");
    }

    private String jdbcUrl(String db) {
        return "jdbc:postgresql://" + host + ":" + port + "/" + db
                + "?logServerErrorDetail=false&ApplicationName=admin-migrate";
    }

    private static String required(Map<String, String> env, String name) {
        return optional(env, name).orElseThrow(() -> new IllegalArgumentException("Variável obrigatória ausente: " + name));
    }

    private static Optional<String> optional(Map<String, String> env, String name) {
        return Optional.ofNullable(env.get(name)).map(String::strip).filter(v -> !v.isEmpty());
    }
}
