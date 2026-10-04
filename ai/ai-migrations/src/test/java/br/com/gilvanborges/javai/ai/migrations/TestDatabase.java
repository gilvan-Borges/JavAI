package br.com.gilvanborges.javai.ai.migrations;

import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Sobe o banco de testes da IA pelo mesmo caminho da produção: bootstrap dos papéis e depois o Flyway.
 * Vai num test-jar, então os testes do ai-indexer e do ai-api usam exatamente o mesmo banco.
 */
public final class TestDatabase {

    public static final String IMAGE = "postgres:18-alpine";

    private TestDatabase() {
    }

    public static PostgreSQLContainer newContainer() {
        return new PostgreSQLContainer(IMAGE).withPassword("superuser-test-password-0123456789");
    }

    public static DatabaseSettings settings(PostgreSQLContainer pg) {
        return new DatabaseSettings(
                pg.getHost(),
                pg.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                "ai",
                new Credentials(pg.getUsername(), pg.getPassword()),
                new Credentials(Roles.OWNER, "owner-test-password-0123456789"),
                new Credentials(Roles.INDEXER, "indexer-test-password-0123456789"),
                new Credentials(Roles.READER, "reader-test-password-0123456789-ab"));
    }

    public static DatabaseSettings prepare(PostgreSQLContainer pg) throws SQLException {
        var settings = settings(pg);
        Bootstrap.run(settings);
        Migrations.apply(settings);
        return settings;
    }

    public static Connection connect(DatabaseSettings settings, Credentials who) throws SQLException {
        return DriverManager.getConnection(settings.url(), who.user(), who.password());
    }

    /** Apaga os dados entre testes. TRUNCATE ignora RLS, por isso só o dono pode (os apps não têm TRUNCATE). */
    public static void truncateAll(DatabaseSettings settings) throws SQLException {
        try (var owner = connect(settings, settings.owner()); var st = owner.createStatement()) {
            st.execute("""
                    TRUNCATE index.version, index.chunk, index.skill_evidence, index.repo_commit, ops.index_job, ops.usage_daily,
                           ops.knowledge_heat, ops.knowledge_pair
                    CASCADE""");
        }
    }
}
